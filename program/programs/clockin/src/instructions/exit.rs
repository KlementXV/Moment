use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::day_of,
    error::ClockInError,
    state::{Config, Profile},
};

#[derive(Accounts)]
pub struct ExitRequest<'info> {
    pub owner: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
}

pub fn handle_request_exit(ctx: Context<ExitRequest>) -> Result<()> {
    let clock = Clock::get()?;
    let today = day_of(clock.unix_timestamp);
    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);
    require!(profile.exit_unlock_at == 0, ClockInError::ExitAlreadyRequested);

    // Régler d'abord : on ne sort pas d'une position en dissimulant ses absences.
    let bound = profile.settle_bound(today);
    profile.settle_through(config, bound);

    profile.exit_requested_at = clock.unix_timestamp;
    // Délai figé pour cette demande, même si la configuration change ensuite (§6.3).
    profile.exit_unlock_at = clock
        .unix_timestamp
        .checked_add(config.withdrawal_delay_seconds)
        .ok_or(ClockInError::InvalidConfigParam)?;
    Ok(())
}

pub fn handle_cancel_exit(ctx: Context<ExitRequest>) -> Result<()> {
    let clock = Clock::get()?;
    let profile = &mut ctx.accounts.profile;
    require!(profile.exit_unlock_at > 0, ClockInError::NoExitRequested);
    require!(
        clock.unix_timestamp < profile.exit_unlock_at,
        ClockInError::ExitUnlocked
    );
    // Annuler ne restitue jamais les pertes déjà appliquées (§6.3).
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    Ok(())
}
