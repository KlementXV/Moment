use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::{day_of, reward},
    error::ClockInError,
    state::{CheckIn, Config, Profile},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct CheckInAccounts<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    /// Autorisation de publication du backend (D4, §8.6). Sa signature porte sur
    /// cette transaction exacte : mêmes données d'instruction, même blockhash,
    /// donc une expiration naturelle et aucun rejeu possible.
    #[account(address = config.publication_authority @ ClockInError::InvalidConfigParam)]
    pub publication_authority: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
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
    let today = day_of(clock.unix_timestamp);
    // Le jour n'est en paramètre que pour dériver le PDA ; il est vérifié (D6).
    require!(day == today, ClockInError::DayMismatch);

    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);
    if profile.exit_unlock_at > 0 {
        require!(
            clock.unix_timestamp < profile.exit_unlock_at,
            ClockInError::ExitUnlocked
        );
    }

    let bound = profile.settle_bound(today);
    profile.settle_through(config, bound);
    require!(
        profile.staked >= config.min_stake,
        ClockInError::InsufficientStake
    );

    let paid = reward(
        profile.staked,
        config.reward_rate_bps,
        config.reward_cap,
        config.pool_balance,
    );
    config.pool_balance -= paid;
    profile.staked += paid;
    profile.streak += 1;
    profile.settled_day = today;
    profile.last_checkin_day = today;
    profile.total_checkins += 1;

    let streak = profile.streak;
    let owner = profile.owner;
    let check_in = &mut ctx.accounts.check_in;
    check_in.owner = owner;
    check_in.day = today;
    check_in.commitment = commitment;
    check_in.blob_ref = blob_ref;
    check_in.slot = clock.slot;
    check_in.streak_at_checkin = streak;
    Ok(())
}
