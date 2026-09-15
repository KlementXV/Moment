use anchor_lang::prelude::*;

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
