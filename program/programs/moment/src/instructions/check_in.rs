use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::day_of,
    error::MomentError,
    settlement::{settle, PoolAccounts},
    state::{CheckIn, Config, DayPool, Profile},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct CheckInAccounts<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(address = config.publication_authority @ MomentError::InvalidConfigParam)]
    pub publication_authority: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
    #[account(
        init_if_needed,
        payer = owner,
        space = 8 + DayPool::INIT_SPACE,
        seeds = [DAY_POOL_SEED, &day.to_le_bytes()],
        bump
    )]
    pub day_pool: Account<'info, DayPool>,
    #[account(
        init,
        payer = owner,
        space = 8 + CheckIn::INIT_SPACE,
        seeds = [CHECKIN_SEED, owner.key().as_ref(), &day.to_le_bytes()],
        bump
    )]
    pub check_in: Account<'info, CheckIn>,
    pub system_program: Program<'info, System>,
}

pub fn handle_check_in(
    ctx: Context<CheckInAccounts>,
    day: i64,
    commitment: [u8; 32],
    blob_ref: [u8; 32],
) -> Result<()> {
    let clock = Clock::get()?;
    let now = clock.unix_timestamp;
    let today = day_of(now);
    require!(day == today, MomentError::DayMismatch);

    let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
    let accounts = ctx.accounts;
    accounts.day_pool.open(today, ctx.bumps.day_pool);
    require!(accounts.profile.active, MomentError::PositionInactive);
    if accounts.profile.exit_unlock_at > 0 {
        require!(now < accounts.profile.exit_unlock_at, MomentError::ExitUnlocked);
    }

    let bound = accounts.profile.settle_bound(today);
    settle(
        &mut accounts.profile,
        &accounts.config,
        &mut accounts.day_pool,
        &pools,
        now,
        bound,
    )?;
    let staked = accounts.profile.staked;
    require!(
        staked >= accounts.config.min_stake,
        MomentError::InsufficientStake
    );

    let pool = &mut accounts.day_pool;
    pool.total_stake = pool
        .total_stake
        .checked_add(staked)
        .ok_or(MomentError::InvalidAmount)?;
    pool.winners_count = pool.winners_count.saturating_add(1);

    let profile = &mut accounts.profile;
    profile.add_claim(today, staked)?;
    profile.streak += 1;
    profile.settled_day = today;
    profile.last_checkin_day = today;
    profile.total_checkins += 1;

    let streak = profile.streak;
    let owner = profile.owner;
    let check_in = &mut accounts.check_in;
    check_in.owner = owner;
    check_in.day = today;
    check_in.commitment = commitment;
    check_in.blob_ref = blob_ref;
    check_in.slot = clock.slot;
    check_in.streak_at_checkin = streak;
    Ok(())
}
