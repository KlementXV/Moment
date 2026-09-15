pub mod constants;
pub mod economy;
pub mod error;
pub mod instructions;
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

    pub fn seed_pool(ctx: Context<SeedPool>, amount: u64) -> Result<()> {
        instructions::initialize_config::handle_seed_pool(ctx, amount)
    }

    pub fn create_profile(ctx: Context<CreateProfile>) -> Result<()> {
        instructions::profile::handle_create_profile(ctx)
    }

    pub fn faucet(ctx: Context<Faucet>) -> Result<()> {
        instructions::profile::handle_faucet(ctx)
    }
}
