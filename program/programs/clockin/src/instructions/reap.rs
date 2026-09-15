use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::day_of,
    error::ClockInError,
    state::{Config, Profile},
};

#[derive(Accounts)]
pub struct Reap<'info> {
    /// Instruction permissionless : n'importe qui peut régler un profil en retard.
    /// Aucun bonus n'est versé à l'appelant en v1 (§6.4).
    pub caller: Signer<'info>,
    /// CHECK: propriétaire de la position, lié au profil par `has_one`.
    pub owner: UncheckedAccount<'info>,
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

pub fn handle_reap(ctx: Context<Reap>) -> Result<()> {
    let today = day_of(Clock::get()?.unix_timestamp);
    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);

    let bound = profile.settle_bound(today);
    require!(bound > profile.settled_day, ClockInError::NothingToReap);
    profile.settle_through(config, bound);
    Ok(())
}
