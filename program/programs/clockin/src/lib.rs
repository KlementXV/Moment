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
}
