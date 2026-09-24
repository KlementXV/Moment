use anchor_lang::prelude::*;

use crate::{economy::settle_bound, error::ClockInError};

pub const PENDING_SLOTS: usize = 2;
pub const NO_PENDING_DAY: i64 = -1;

/// PDA singleton, graine `CONFIG_SEED`. Autorité du vault et porteur des
/// paramètres économiques, calibrables sans redéploiement (§15.2).
#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    /// Clé qui co-signe `check_in` : autorisation de publication du backend (D4).
    pub publication_authority: Pubkey,
    pub skr_mint: Pubkey,
    pub vault: Pubkey,
    pub min_stake: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    /// Délai après la fin du jour D avant la clôture de son pool (spec pool journalier).
    pub pool_close_delay_seconds: i64,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    pub faucet_enabled: bool,
    pub bump: u8,
    pub vault_bump: u8,
}

/// PDA par wallet, graines `PROFILE_SEED || owner`.
#[account]
#[derive(InitSpace)]
pub struct Profile {
    pub owner: Pubkey,
    /// Solde unique : sert de mise ET de compteur de gains.
    pub staked: u64,
    /// Dernier jour dont la comptabilité est à jour : pilote le decay.
    pub settled_day: i64,
    /// Dernier jour réellement posté : affichage et streak.
    pub last_checkin_day: i64,
    pub exit_requested_at: i64,
    /// Figé à la demande, même si la configuration change ensuite (§6.3).
    pub exit_unlock_at: i64,
    pub total_checkins: u64,
    pub streak: u32,
    pub active: bool,
    pub faucet_claimed: bool,
    pub bump: u8,
    /// Créances sur les pools des jours publiés : `NO_PENDING_DAY` = emplacement
    /// libre. Deux suffisent : J-1 pas encore clôturé et J.
    pub pending_days: [i64; PENDING_SLOTS],
    pub pending_stakes: [u64; PENDING_SLOTS],
}

/// PDA par couple wallet × jour : rend le double check-in structurellement
/// impossible, le compte existe déjà.
#[account]
#[derive(InitSpace)]
pub struct CheckIn {
    pub owner: Pubkey,
    pub day: i64,
    /// SHA-256 du manifeste canonique signé par le wallet (§7).
    pub commitment: [u8; 32],
    /// SHA-256 du blob chiffré stocké côté serveur (décision D1).
    pub blob_ref: [u8; 32],
    /// Slot d'inscription : l'ancre temporelle.
    pub slot: u64,
    pub streak_at_checkin: u32,
}

/// PDA par jour, graines `DAY_POOL_SEED || day` : mises des publieurs du jour
/// et pénalités à leur partager.
#[account]
#[derive(InitSpace)]
pub struct DayPool {
    pub day: i64,
    pub penalties: u64,
    pub total_stake: u64,
    /// Affichage seulement.
    pub winners_count: u32,
    pub bump: u8,
}

impl DayPool {
    /// Idempotent : `init_if_needed` ne dit pas si le compte vient d'être créé.
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
            .ok_or(ClockInError::TooManyPendingClaims)?;
        self.pending_days[slot] = day;
        self.pending_stakes[slot] = stake;
        Ok(())
    }
}
