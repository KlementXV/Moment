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
    /// Pool du jour : reçoit les pénalités routées vers aujourd'hui.
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
    require!(day == today, ClockInError::DayMismatch);
    let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
    let accounts = ctx.accounts;
    accounts.day_pool.open(today, ctx.bumps.day_pool);
    require!(accounts.profile.active, ClockInError::PositionInactive);
    require!(
        accounts.profile.exit_unlock_at == 0,
        ClockInError::ExitAlreadyRequested
    );

    // Régler d'abord : on ne sort pas d'une position en dissimulant ses absences.
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
    // Délai figé pour cette demande, même si la configuration change ensuite (§6.3).
    profile.exit_unlock_at = now
        .checked_add(accounts.config.withdrawal_delay_seconds)
        .ok_or(ClockInError::InvalidConfigParam)?;
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
#[instruction(day: i64)]
pub struct FinalizeExit<'info> {
    /// Permissionless : un keeper peut finaliser pour le propriétaire. Il paie
    /// seulement le pool du jour s'il est à créer.
    #[account(mut)]
    pub caller: Signer<'info>,
    /// CHECK: propriétaire de la position, lié au profil par `has_one`.
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
    /// Pool du jour : reçoit les pénalités routées vers aujourd'hui.
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
    pub system_program: Program<'info, System>,
}

pub fn handle_finalize_exit(mut ctx: Context<FinalizeExit>, day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let today = day_of(now);
    require!(day == today, ClockInError::DayMismatch);
    {
        let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
        let accounts = &mut ctx.accounts;
        accounts.day_pool.open(today, ctx.bumps.day_pool);
        require!(
            accounts.profile.exit_unlock_at > 0,
            ClockInError::NoExitRequested
        );
        require!(
            now >= accounts.profile.exit_unlock_at,
            ClockInError::ExitLocked
        );
        // Dernière journée pénalisable : celle entièrement terminée avant le
        // déblocage. Une finalisation tardive ne coûte donc rien de plus (§6.3).
        let bound = day_of(accounts.profile.exit_unlock_at) - 1;
        settle(
            &mut accounts.profile,
            &accounts.config,
            &mut accounts.day_pool,
            &pools,
            now,
            bound,
        )?;
        // Une part encore ouverte serait perdue avec la position : on attend sa clôture.
        require!(
            !accounts.profile.has_pending(),
            ClockInError::ClaimStillOpen
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
