use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::{check_in_closable, day_of},
    error::MomentError,
    state::CheckIn,
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct CloseCheckIn<'info> {
    pub caller: Signer<'info>,
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

pub fn handle_close_check_in(ctx: Context<CloseCheckIn>, day: i64) -> Result<()> {
    let today = day_of(Clock::get()?.unix_timestamp);
    require!(
        ctx.accounts.check_in.day == day && check_in_closable(day, today),
        MomentError::CheckInTooRecent
    );
    Ok(())
}
