use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{
    constants::*,
    economy::day_of,
    error::ClockInError,
    settlement::{settle, PoolAccounts},
    state::{Config, DayPool, Profile},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct Stake<'info> {
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
    /// Pool du jour : reçoit les pénalités routées vers aujourd'hui.
    #[account(
        init_if_needed,
        payer = owner,
        space = 8 + DayPool::INIT_SPACE,
        seeds = [DAY_POOL_SEED, &day.to_le_bytes()],
        bump
    )]
    pub day_pool: Account<'info, DayPool>,
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = owner_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.owner == owner.key() @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

pub fn handle_stake(mut ctx: Context<Stake>, day: i64, amount: u64) -> Result<()> {
    require!(amount > 0, ClockInError::InvalidAmount);
    let now = Clock::get()?.unix_timestamp;
    let today = day_of(now);
    require!(day == today, ClockInError::DayMismatch);

    {
        let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
        let accounts = &mut ctx.accounts;
        accounts.day_pool.open(today, ctx.bumps.day_pool);
        // Un dépôt pendant une sortie rendrait le montant de sortie illisible (§5).
        require!(
            accounts.profile.exit_unlock_at == 0,
            ClockInError::StakeDuringExit
        );
        if accounts.profile.active {
            // Régler AVANT d'ajouter : le dépôt neuf ne subit aucun decay rétroactif.
            let bound = accounts.profile.settle_bound(today);
            settle(
                &mut accounts.profile,
                &accounts.config,
                &mut accounts.day_pool,
                &pools,
                now,
                bound,
            )?;
        } else {
            let profile = &mut accounts.profile;
            profile.active = true;
            profile.settled_day = today - 1;
            profile.streak = 0;
        }
    }

    let decimals = ctx.accounts.skr_mint.decimals;
    transfer_checked(
        CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.owner_token_account.to_account_info(),
                mint: ctx.accounts.skr_mint.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.owner.to_account_info(),
            },
        ),
        amount,
        decimals,
    )?;

    let profile = &mut ctx.accounts.profile;
    profile.staked = profile
        .staked
        .checked_add(amount)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(())
}
