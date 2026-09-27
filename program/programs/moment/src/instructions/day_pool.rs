use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::{day_of, pool_closes_at},
    error::MomentError,
    state::{Config, DayPool},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct OpenDayPool<'info> {
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
        MomentError::DayMismatch
    );
    ctx.accounts.day_pool.open(day, ctx.bumps.day_pool);
    Ok(())
}

#[derive(Accounts)]
#[instruction(day: i64, from_day: i64)]
pub struct RollOverDayPool<'info> {
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

pub fn handle_roll_over_day_pool(ctx: Context<RollOverDayPool>, day: i64, from_day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    require!(day == day_of(now), MomentError::DayMismatch);
    require!(from_day < day, MomentError::DayMismatch);
    require!(
        now >= pool_closes_at(from_day, ctx.accounts.config.pool_close_delay_seconds),
        MomentError::PoolStillOpen
    );
    let from = &mut ctx.accounts.from_pool;
    require!(
        from.total_stake == 0 && from.penalties > 0,
        MomentError::NothingToRollOver
    );
    let amount = from.penalties;
    from.penalties = 0;
    let pool = &mut ctx.accounts.day_pool;
    pool.open(day, ctx.bumps.day_pool);
    pool.penalties = pool
        .penalties
        .checked_add(amount)
        .ok_or(MomentError::InvalidAmount)?;
    Ok(())
}
