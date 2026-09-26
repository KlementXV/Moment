use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::{check_in_closable, day_of},
    error::ClockInError,
    state::CheckIn,
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct CloseCheckIn<'info> {
    /// Permissionless : le crank ferme les check-ins passés. Il paie les frais,
    /// la rente revient toujours au propriétaire.
    pub caller: Signer<'info>,
    /// CHECK: propriétaire du check-in et seul destinataire de la rente, lié au
    /// compte par `has_one` et par les graines du PDA.
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,
    #[account(
        mut,
        close = owner,
        has_one = owner,
        seeds = [CHECKIN_SEED, owner.key().as_ref(), &day.to_le_bytes()],
        bump
    )]
    pub check_in: Account<'info, CheckIn>,
}

/// Le check-in n'est qu'une preuve de publication du jour : passé J+1, plus
/// rien ne le relit, et sa rente (≈ 0,0013 SOL) retourne au propriétaire.
pub fn handle_close_check_in(ctx: Context<CloseCheckIn>, day: i64) -> Result<()> {
    let today = day_of(Clock::get()?.unix_timestamp);
    require!(
        ctx.accounts.check_in.day == day && check_in_closable(day, today),
        ClockInError::CheckInTooRecent
    );
    Ok(())
}
