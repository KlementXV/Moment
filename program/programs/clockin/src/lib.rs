pub mod constants;
pub mod economy;
pub mod error;
pub mod instructions;
pub mod settlement;
pub mod state;

use anchor_lang::prelude::*;

pub use constants::*;
pub use economy::*;
pub use instructions::*;
pub use state::*;

declare_id!("7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1");

#[program]
pub mod clockin {
    use super::*;

    pub fn initialize_config(ctx: Context<InitializeConfig>, params: ConfigParams) -> Result<()> {
        instructions::initialize_config::handle_initialize_config(ctx, params)
    }

    pub fn update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
        instructions::initialize_config::handle_update_config(ctx, params)
    }

    pub fn set_publication_authority(
        ctx: Context<UpdateConfig>,
        new_authority: Pubkey,
    ) -> Result<()> {
        instructions::initialize_config::handle_set_publication_authority(ctx, new_authority)
    }

    pub fn seed_pool(ctx: Context<SeedPool>, day: i64, amount: u64) -> Result<()> {
        instructions::initialize_config::handle_seed_pool(ctx, day, amount)
    }

    pub fn create_profile(ctx: Context<CreateProfile>) -> Result<()> {
        instructions::profile::handle_create_profile(ctx)
    }

    pub fn faucet(ctx: Context<Faucet>) -> Result<()> {
        instructions::profile::handle_faucet(ctx)
    }

    pub fn stake(ctx: Context<Stake>, day: i64, amount: u64) -> Result<()> {
        instructions::stake::handle_stake(ctx, day, amount)
    }

    pub fn check_in(
        ctx: Context<CheckInAccounts>,
        day: i64,
        commitment: [u8; 32],
        blob_ref: [u8; 32],
    ) -> Result<()> {
        instructions::check_in::handle_check_in(ctx, day, commitment, blob_ref)
    }

    pub fn reap(ctx: Context<Reap>, day: i64) -> Result<()> {
        instructions::reap::handle_reap(ctx, day)
    }

    pub fn request_exit(ctx: Context<RequestExit>, day: i64) -> Result<()> {
        instructions::exit::handle_request_exit(ctx, day)
    }

    pub fn cancel_exit(ctx: Context<CancelExit>) -> Result<()> {
        instructions::exit::handle_cancel_exit(ctx)
    }

    pub fn finalize_exit(ctx: Context<FinalizeExit>, day: i64) -> Result<()> {
        instructions::exit::handle_finalize_exit(ctx, day)
    }
}
