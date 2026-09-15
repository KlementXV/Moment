use anchor_lang::prelude::*;

use crate::economy::{decay, settle_bound};

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
    /// Part du vault qui appartient au pool de redistribution.
    pub pool_balance: u64,
    pub min_stake: u64,
    pub reward_cap: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    pub reward_rate_bps: u16,
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

impl Profile {
    pub fn settle_bound(&self, today: i64) -> i64 {
        settle_bound(self.exit_unlock_at, today)
    }

    /// Applique le decay des jours non réglés jusqu'à `through_day` inclus.
    /// Le montant perdu alimente le pool ; aucun token SPL ne bouge.
    /// Séparer `settled_day` de `last_checkin_day` est ce qui rend `reap` sûr
    /// contre le double-decay : un jour réglé ne l'est jamais deux fois.
    pub fn settle_through(&mut self, config: &mut Config, through_day: i64) {
        if !self.active {
            return;
        }
        let missed = through_day - self.settled_day;
        if missed <= 0 {
            return;
        }
        let outcome = decay(self.staked, missed, config.decay_bps, config.max_decay_days);
        self.staked = outcome.remaining;
        config.pool_balance = config.pool_balance.saturating_add(outcome.lost);
        self.settled_day = through_day;
        self.streak = 0;
    }
}
