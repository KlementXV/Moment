use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{
    constants::*,
    economy::{day_of, valid_close_delay},
    error::MomentError,
    state::{Config, DayPool},
};

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct ConfigParams {
    pub min_stake: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    pub pool_close_delay_seconds: i64,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    pub faucet_enabled: bool,
}

impl ConfigParams {
    pub fn validate(&self) -> Result<()> {
        require!(self.decay_bps <= 10_000, MomentError::InvalidConfigParam);
        require!(self.max_decay_days >= 1, MomentError::InvalidConfigParam);
        require!(self.withdrawal_delay_seconds >= 0, MomentError::InvalidConfigParam);
        require!(
            valid_close_delay(self.pool_close_delay_seconds),
            MomentError::InvalidConfigParam
        );
        Ok(())
    }
}

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
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
    #[account(
        seeds = [crate::ID.as_ref()],
        bump,
        seeds::program = anchor_lang::solana_program::bpf_loader_upgradeable::ID,
        constraint = program_data.upgrade_authority_address == Some(admin.key()) @ MomentError::InvalidConfigParam
    )]
    pub program_data: Account<'info, ProgramData>,
}

pub fn handle_initialize_config(ctx: Context<InitializeConfig>, params: ConfigParams) -> Result<()> {
    params.validate()?;
    let config = &mut ctx.accounts.config;
    config.admin = ctx.accounts.admin.key();
    config.publication_authority = ctx.accounts.publication_authority.key();
    config.skr_mint = ctx.accounts.skr_mint.key();
    config.vault = ctx.accounts.vault.key();
    config.min_stake = params.min_stake;
    config.faucet_amount = params.faucet_amount;
    config.withdrawal_delay_seconds = params.withdrawal_delay_seconds;
    config.pool_close_delay_seconds = params.pool_close_delay_seconds;
    config.decay_bps = params.decay_bps;
    config.max_decay_days = params.max_decay_days;
    config.faucet_enabled = params.faucet_enabled;
    config.bump = ctx.bumps.config;
    config.vault_bump = ctx.bumps.vault;
    Ok(())
}

#[derive(Accounts)]
pub struct UpdateConfig<'info> {
    #[account(address = config.admin @ MomentError::InvalidConfigParam)]
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
}

pub fn handle_update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
    params.validate()?;
    let config = &mut ctx.accounts.config;
    require!(
        params.pool_close_delay_seconds == config.pool_close_delay_seconds,
        MomentError::InvalidConfigParam
    );
    config.min_stake = params.min_stake;
    config.faucet_amount = params.faucet_amount;
    config.withdrawal_delay_seconds = params.withdrawal_delay_seconds;
    config.pool_close_delay_seconds = params.pool_close_delay_seconds;
    config.decay_bps = params.decay_bps;
    config.max_decay_days = params.max_decay_days;
    config.faucet_enabled = params.faucet_enabled;
    Ok(())
}

pub fn handle_set_publication_authority(
    ctx: Context<UpdateConfig>,
    new_authority: Pubkey,
) -> Result<()> {
    ctx.accounts.config.publication_authority = new_authority;
    Ok(())
}

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct SeedPool<'info> {
    #[account(mut, address = config.admin @ MomentError::InvalidConfigParam)]
    pub admin: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        init_if_needed,
        payer = admin,
        space = 8 + DayPool::INIT_SPACE,
        seeds = [DAY_POOL_SEED, &day.to_le_bytes()],
        bump
    )]
    pub day_pool: Account<'info, DayPool>,
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = admin_token_account.mint == config.skr_mint @ MomentError::InvalidOwnerTokenAccount,
        constraint = admin_token_account.owner == admin.key() @ MomentError::InvalidOwnerTokenAccount,
    )]
    pub admin_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

pub fn handle_seed_pool(ctx: Context<SeedPool>, day: i64, amount: u64) -> Result<()> {
    require!(amount > 0, MomentError::InvalidAmount);
    require!(
        day == day_of(Clock::get()?.unix_timestamp),
        MomentError::DayMismatch
    );
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
    let pool = &mut ctx.accounts.day_pool;
    pool.open(day, ctx.bumps.day_pool);
    pool.penalties = pool
        .penalties
        .checked_add(amount)
        .ok_or(MomentError::InvalidAmount)?;
    Ok(())
}
