use anchor_lang::prelude::*;
use anchor_spl::token::{mint_to, Mint, MintTo, Token, TokenAccount};

use crate::{
    constants::*,
    error::MomentError,
    state::{Config, Profile, NO_PENDING_DAY, PENDING_SLOTS},
};

#[derive(Accounts)]
pub struct CreateProfile<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(
        init,
        payer = owner,
        space = 8 + Profile::INIT_SPACE,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump
    )]
    pub profile: Account<'info, Profile>,
    pub system_program: Program<'info, System>,
}

pub fn handle_create_profile(ctx: Context<CreateProfile>) -> Result<()> {
    let profile = &mut ctx.accounts.profile;
    profile.owner = ctx.accounts.owner.key();
    profile.staked = 0;
    profile.settled_day = 0;
    profile.last_checkin_day = 0;
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    profile.total_checkins = 0;
    profile.streak = 0;
    profile.active = false;
    profile.faucet_claimed = false;
    profile.bump = ctx.bumps.profile;
    profile.pending_days = [NO_PENDING_DAY; PENDING_SLOTS];
    profile.pending_stakes = [0; PENDING_SLOTS];
    Ok(())
}

#[derive(Accounts)]
pub struct Faucet<'info> {
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
    #[account(mut, address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = owner_token_account.mint == config.skr_mint @ MomentError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.owner == owner.key() @ MomentError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

pub fn handle_faucet(ctx: Context<Faucet>) -> Result<()> {
    require!(ctx.accounts.config.faucet_enabled, MomentError::FaucetDisabled);
    require!(
        !ctx.accounts.profile.faucet_claimed,
        MomentError::FaucetAlreadyClaimed
    );

    let amount = ctx.accounts.config.faucet_amount;
    let bump = ctx.accounts.config.bump;
    let seeds: &[&[u8]] = &[CONFIG_SEED, &[bump]];
    mint_to(
        CpiContext::new_with_signer(
            ctx.accounts.token_program.key(),
            MintTo {
                mint: ctx.accounts.skr_mint.to_account_info(),
                to: ctx.accounts.owner_token_account.to_account_info(),
                authority: ctx.accounts.config.to_account_info(),
            },
            &[seeds],
        ),
        amount,
    )?;
    ctx.accounts.profile.faucet_claimed = true;
    Ok(())
}
