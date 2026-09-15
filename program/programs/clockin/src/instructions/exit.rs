use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{
    constants::*,
    economy::day_of,
    error::ClockInError,
    state::{Config, Profile},
};

#[derive(Accounts)]
pub struct ExitRequest<'info> {
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
}

pub fn handle_request_exit(ctx: Context<ExitRequest>) -> Result<()> {
    let clock = Clock::get()?;
    let today = day_of(clock.unix_timestamp);
    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);
    require!(profile.exit_unlock_at == 0, ClockInError::ExitAlreadyRequested);

    // Régler d'abord : on ne sort pas d'une position en dissimulant ses absences.
    let bound = profile.settle_bound(today);
    profile.settle_through(config, bound);

    profile.exit_requested_at = clock.unix_timestamp;
    // Délai figé pour cette demande, même si la configuration change ensuite (§6.3).
    profile.exit_unlock_at = clock
        .unix_timestamp
        .checked_add(config.withdrawal_delay_seconds)
        .ok_or(ClockInError::InvalidConfigParam)?;
    Ok(())
}

pub fn handle_cancel_exit(ctx: Context<ExitRequest>) -> Result<()> {
    let clock = Clock::get()?;
    let profile = &mut ctx.accounts.profile;
    require!(profile.exit_unlock_at > 0, ClockInError::NoExitRequested);
    require!(
        clock.unix_timestamp < profile.exit_unlock_at,
        ClockInError::ExitUnlocked
    );
    // Annuler ne restitue jamais les pertes déjà appliquées (§6.3).
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    Ok(())
}

#[derive(Accounts)]
pub struct FinalizeExit<'info> {
    /// Permissionless : un keeper peut finaliser pour le propriétaire.
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
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    /// Le versement est toujours adressé au propriétaire, jamais à l'appelant.
    #[account(
        mut,
        constraint = owner_token_account.owner == owner.key() @ ClockInError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

pub fn handle_finalize_exit(ctx: Context<FinalizeExit>) -> Result<()> {
    let clock = Clock::get()?;
    {
        let config = &mut ctx.accounts.config;
        let profile = &mut ctx.accounts.profile;
        require!(profile.exit_unlock_at > 0, ClockInError::NoExitRequested);
        require!(
            clock.unix_timestamp >= profile.exit_unlock_at,
            ClockInError::ExitLocked
        );
        // Dernière journée pénalisable : celle entièrement terminée avant le
        // déblocage. Une finalisation tardive ne coûte donc rien de plus (§6.3).
        let bound = day_of(profile.exit_unlock_at) - 1;
        profile.settle_through(config, bound);
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
