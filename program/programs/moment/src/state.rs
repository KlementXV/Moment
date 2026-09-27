use anchor_lang::prelude::*;

use crate::{economy::settle_bound, error::MomentError};

pub const PENDING_SLOTS: usize = 2;
pub const NO_PENDING_DAY: i64 = -1;

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub publication_authority: Pubkey,
    pub skr_mint: Pubkey,
    pub vault: Pubkey,
    pub min_stake: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    pub pool_close_delay_seconds: i64,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    pub faucet_enabled: bool,
    pub bump: u8,
    pub vault_bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Profile {
    pub owner: Pubkey,
    pub staked: u64,
    pub settled_day: i64,
    pub last_checkin_day: i64,
    pub exit_requested_at: i64,
    pub exit_unlock_at: i64,
    pub total_checkins: u64,
    pub streak: u32,
    pub active: bool,
    pub faucet_claimed: bool,
    pub bump: u8,
    pub pending_days: [i64; PENDING_SLOTS],
    pub pending_stakes: [u64; PENDING_SLOTS],
}

#[account]
#[derive(InitSpace)]
pub struct CheckIn {
    pub owner: Pubkey,
    pub day: i64,
    pub commitment: [u8; 32],
    pub blob_ref: [u8; 32],
    pub slot: u64,
    pub streak_at_checkin: u32,
}

#[account]
#[derive(InitSpace)]
pub struct DayPool {
    pub day: i64,
    pub penalties: u64,
    pub total_stake: u64,
    pub winners_count: u32,
    pub bump: u8,
}

impl DayPool {
    pub fn open(&mut self, day: i64, bump: u8) {
        self.day = day;
        self.bump = bump;
    }
}

impl Profile {
    pub fn settle_bound(&self, today: i64) -> i64 {
        settle_bound(self.exit_unlock_at, today)
    }

    pub fn has_pending(&self) -> bool {
        self.pending_days.iter().any(|day| *day != NO_PENDING_DAY)
    }

    pub fn add_claim(&mut self, day: i64, stake: u64) -> Result<()> {
        let slot = self
            .pending_days
            .iter()
            .position(|d| *d == NO_PENDING_DAY)
            .ok_or(MomentError::TooManyPendingClaims)?;
        self.pending_days[slot] = day;
        self.pending_stakes[slot] = stake;
        Ok(())
    }
}
