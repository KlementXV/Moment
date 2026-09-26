use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::{day_of, pool_closes_at},
    error::ClockInError,
    state::{Config, DayPool},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct OpenDayPool<'info> {
    /// Permissionless : le crank crée le pool du jour à 00:05, et le premier
    /// publieur ne paie plus sa rente.
    #[account(mut)]
    pub caller: Signer<'info>,
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

pub fn handle_open_day_pool(ctx: Context<OpenDayPool>, day: i64) -> Result<()> {
    require!(
        day == day_of(Clock::get()?.unix_timestamp),
        ClockInError::DayMismatch
    );
    ctx.accounts.day_pool.open(day, ctx.bumps.day_pool);
    Ok(())
}

#[derive(Accounts)]
#[instruction(day: i64, from_day: i64)]
pub struct RollOverDayPool<'info> {
    /// Permissionless : le crank reporte les pools orphelins.
    #[account(mut)]
    pub caller: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [DAY_POOL_SEED, &from_day.to_le_bytes()],
        bump = from_pool.bump
    )]
    pub from_pool: Account<'info, DayPool>,
    /// Pool du jour : reçoit le montant reporté.
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

/// Un pool clôturé sans aucun publieur ne peut plus rien verser : son contenu
/// (amorçage ou pénalités) passe au pool du jour au lieu de rester bloqué.
/// Après la clôture plus rien n'y entre, et sans publieur personne n'y a de
/// créance : le vider ne retire rien à personne.
pub fn handle_roll_over_day_pool(ctx: Context<RollOverDayPool>, day: i64, from_day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    require!(day == day_of(now), ClockInError::DayMismatch);
    require!(from_day < day, ClockInError::DayMismatch);
    require!(
        now >= pool_closes_at(from_day, ctx.accounts.config.pool_close_delay_seconds),
        ClockInError::PoolStillOpen
    );
    let from = &mut ctx.accounts.from_pool;
    require!(
        from.total_stake == 0 && from.penalties > 0,
        ClockInError::NothingToRollOver
    );
    let amount = from.penalties;
    from.penalties = 0;
    let pool = &mut ctx.accounts.day_pool;
    pool.open(day, ctx.bumps.day_pool);
    pool.penalties = pool
        .penalties
        .checked_add(amount)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(())
}
