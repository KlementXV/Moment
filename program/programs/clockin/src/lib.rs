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

declare_id!("ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6");

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

    pub fn open_day_pool(ctx: Context<OpenDayPool>, day: i64) -> Result<()> {
        instructions::day_pool::handle_open_day_pool(ctx, day)
    }

    pub fn roll_over_day_pool(ctx: Context<RollOverDayPool>, day: i64, from_day: i64) -> Result<()> {
        instructions::day_pool::handle_roll_over_day_pool(ctx, day, from_day)
    }

    pub fn close_check_in(ctx: Context<CloseCheckIn>, day: i64) -> Result<()> {
        instructions::close_check_in::handle_close_check_in(ctx, day)
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
