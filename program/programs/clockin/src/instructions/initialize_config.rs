use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{constants::*, error::ClockInError, state::Config};

/// Paramètres économiques. Regroupés pour que l'ajout d'un paramètre ne change
/// pas la signature de l'instruction côté client.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct ConfigParams {
    pub min_stake: u64,
    pub reward_cap: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    pub reward_rate_bps: u16,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    pub faucet_enabled: bool,
}

impl ConfigParams {
    pub fn validate(&self) -> Result<()> {
        require!(self.decay_bps <= 10_000, ClockInError::InvalidConfigParam);
        require!(self.reward_rate_bps <= 10_000, ClockInError::InvalidConfigParam);
        require!(self.max_decay_days >= 1, ClockInError::InvalidConfigParam);
        require!(self.withdrawal_delay_seconds >= 0, ClockInError::InvalidConfigParam);
        Ok(())
    }
}

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    /// CHECK: simple clé enregistrée dans la configuration ; elle ne signe rien ici.
    pub publication_authority: UncheckedAccount<'info>,
    #[account(
        init,
        payer = admin,
        space = 8 + Config::INIT_SPACE,
        seeds = [CONFIG_SEED],
        bump
    )]
    pub config: Account<'info, Config>,
    pub skr_mint: Account<'info, Mint>,
    #[account(
        init,
        payer = admin,
        seeds = [VAULT_SEED],
        bump,
        token::mint = skr_mint,
        token::authority = config,
    )]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

pub fn handle_initialize_config(ctx: Context<InitializeConfig>, params: ConfigParams) -> Result<()> {
    params.validate()?;
    let config = &mut ctx.accounts.config;
    config.admin = ctx.accounts.admin.key();
    config.publication_authority = ctx.accounts.publication_authority.key();
    config.skr_mint = ctx.accounts.skr_mint.key();
    config.vault = ctx.accounts.vault.key();
    config.pool_balance = 0;
    config.min_stake = params.min_stake;
    config.reward_cap = params.reward_cap;
    config.faucet_amount = params.faucet_amount;
    config.withdrawal_delay_seconds = params.withdrawal_delay_seconds;
    config.reward_rate_bps = params.reward_rate_bps;
    config.decay_bps = params.decay_bps;
    config.max_decay_days = params.max_decay_days;
    config.faucet_enabled = params.faucet_enabled;
    config.bump = ctx.bumps.config;
    config.vault_bump = ctx.bumps.vault;
    Ok(())
}

#[derive(Accounts)]
pub struct UpdateConfig<'info> {
    #[account(address = config.admin @ ClockInError::InvalidConfigParam)]
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
}

pub fn handle_update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
    params.validate()?;
    let config = &mut ctx.accounts.config;
    config.min_stake = params.min_stake;
    config.reward_cap = params.reward_cap;
    config.faucet_amount = params.faucet_amount;
    config.withdrawal_delay_seconds = params.withdrawal_delay_seconds;
    config.reward_rate_bps = params.reward_rate_bps;
    config.decay_bps = params.decay_bps;
    config.max_decay_days = params.max_decay_days;
    config.faucet_enabled = params.faucet_enabled;
    Ok(())
}

/// Rotation de l'autorité de publication. Elle change au moins une fois : de la
/// clé de développement au keyserver, en semaine 2.
pub fn handle_set_publication_authority(
    ctx: Context<UpdateConfig>,
    new_authority: Pubkey,
) -> Result<()> {
    ctx.accounts.config.publication_authority = new_authority;
    Ok(())
}

#[derive(Accounts)]
pub struct SeedPool<'info> {
    #[account(mut, address = config.admin @ ClockInError::InvalidConfigParam)]
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = admin_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
        constraint = admin_token_account.owner == admin.key() @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub admin_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

/// Amorçage du pool (§6.2) : dépôt explicite de l'admin, présenté comme tel
/// dans le pitch. Aucun token n'est créé ici, seulement déplacé.
pub fn handle_seed_pool(ctx: Context<SeedPool>, amount: u64) -> Result<()> {
    require!(amount > 0, ClockInError::InvalidAmount);
    let decimals = ctx.accounts.skr_mint.decimals;
    transfer_checked(
        CpiContext::new(
            ctx.accounts.token_program.key(),
            TransferChecked {
                from: ctx.accounts.admin_token_account.to_account_info(),
                mint: ctx.accounts.skr_mint.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.admin.to_account_info(),
            },
        ),
        amount,
        decimals,
    )?;
    let config = &mut ctx.accounts.config;
    config.pool_balance = config
        .pool_balance
        .checked_add(amount)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(())
}
