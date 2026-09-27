use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{
    constants::*,
    economy::day_of,
    error::MomentError,
    settlement::{settle, PoolAccounts},
    state::{Config, DayPool, Profile},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct RequestExit<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
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
    pub system_program: Program<'info, System>,
}

pub fn handle_request_exit(ctx: Context<RequestExit>, day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let today = day_of(now);
    require!(day == today, MomentError::DayMismatch);
    let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
    let accounts = ctx.accounts;
    accounts.day_pool.open(today, ctx.bumps.day_pool);
    require!(accounts.profile.active, MomentError::PositionInactive);
    require!(
        accounts.profile.exit_unlock_at == 0,
        MomentError::ExitAlreadyRequested
    );

    let bound = accounts.profile.settle_bound(today);
    settle(
        &mut accounts.profile,
        &accounts.config,
        &mut accounts.day_pool,
        &pools,
        now,
        bound,
    )?;

    let profile = &mut accounts.profile;
    profile.exit_requested_at = now;
    profile.exit_unlock_at = now
        .checked_add(accounts.config.withdrawal_delay_seconds)
        .ok_or(MomentError::InvalidConfigParam)?;
    Ok(())
}

#[derive(Accounts)]
pub struct CancelExit<'info> {
    pub owner: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
}

pub fn handle_cancel_exit(ctx: Context<CancelExit>) -> Result<()> {
    let clock = Clock::get()?;
    let profile = &mut ctx.accounts.profile;
    require!(profile.exit_unlock_at > 0, MomentError::NoExitRequested);
    require!(
        clock.unix_timestamp < profile.exit_unlock_at,
        MomentError::ExitUnlocked
    );
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    Ok(())
}

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct FinalizeExit<'info> {
    #[account(mut)]
    pub caller: Signer<'info>,
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
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = owner_token_account.owner == owner.key() @ MomentError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.mint == config.skr_mint @ MomentError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

pub fn handle_finalize_exit(mut ctx: Context<FinalizeExit>, day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let today = day_of(now);
    require!(day == today, MomentError::DayMismatch);
    {
        let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
        let accounts = &mut ctx.accounts;
        accounts.day_pool.open(today, ctx.bumps.day_pool);
        require!(
            accounts.profile.exit_unlock_at > 0,
            MomentError::NoExitRequested
        );
        require!(
            now >= accounts.profile.exit_unlock_at,
            MomentError::ExitLocked
        );
        let bound = day_of(accounts.profile.exit_unlock_at) - 1;
        settle(
            &mut accounts.profile,
            &accounts.config,
            &mut accounts.day_pool,
            &pools,
            now,
            bound,
        )?;
        require!(
            !accounts.profile.has_pending(),
            MomentError::ClaimStillOpen
        );
    }

    let amount = ctx.accounts.profile.staked;
    if amount > 0 {
        let bump = ctx.accounts.config.bump;
        let decimals = ctx.accounts.skr_mint.decimals;
        let seeds: &[&[u8]] = &[CONFIG_SEED, &[bump]];
        transfer_checked(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.key(),
                TransferChecked {
                    from: ctx.accounts.vault.to_account_info(),
                    mint: ctx.accounts.skr_mint.to_account_info(),
                    to: ctx.accounts.owner_token_account.to_account_info(),
                    authority: ctx.accounts.config.to_account_info(),
                },
                &[seeds],
            ),
            amount,
            decimals,
        )?;
    }

    let profile = &mut ctx.accounts.profile;
    profile.staked = 0;
    profile.active = false;
    profile.streak = 0;
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    Ok(())
}
