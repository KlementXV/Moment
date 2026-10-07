use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::day_of,
    error::MomentError,
    settlement::{settle, PoolAccounts},
    state::{Config, DayPool, Profile},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct Reap<'info> {
    #[account(mut)]
    pub caller: Signer<'info>,
    /// CHECK: `profile` binds this address through its owner field and PDA seeds.
    pub owner: UncheckedAccount<'info>,
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
        payer = caller,
        space = 8 + DayPool::INIT_SPACE,
        seeds = [DAY_POOL_SEED, &day.to_le_bytes()],
        bump
    )]
    pub day_pool: Account<'info, DayPool>,
    pub system_program: Program<'info, System>,
}

pub fn handle_reap(ctx: Context<Reap>, day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let today = day_of(now);
    require!(day == today, MomentError::DayMismatch);
    let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
    let accounts = ctx.accounts;
    accounts.day_pool.open(today, ctx.bumps.day_pool);
    require!(accounts.profile.active, MomentError::PositionInactive);

    let bound = accounts.profile.settle_bound(today);
    let work = settle(
        &mut accounts.profile,
        &accounts.config,
        &mut accounts.day_pool,
        &pools,
        now,
        bound,
    )?;
    require!(work > 0, MomentError::NothingToReap);
    Ok(())
}
