use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{
    constants::*,
    economy::day_of,
    error::ClockInError,
    state::{Config, Profile},
};

#[derive(Accounts)]
pub struct Stake<'info> {
    #[account(mut)]
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
}

pub fn handle_stake(ctx: Context<Stake>, amount: u64) -> Result<()> {
    require!(amount > 0, ClockInError::InvalidAmount);
    let today = day_of(Clock::get()?.unix_timestamp);

    {
        let profile = &mut ctx.accounts.profile;
        let config = &mut ctx.accounts.config;
        // Un dépôt pendant une sortie rendrait le montant de sortie illisible (§5).
        require!(profile.exit_unlock_at == 0, ClockInError::StakeDuringExit);
        if profile.active {
            // Régler AVANT d'ajouter : le dépôt neuf ne subit aucun decay rétroactif.
            let bound = profile.settle_bound(today);
            profile.settle_through(config, bound);
        } else {
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
