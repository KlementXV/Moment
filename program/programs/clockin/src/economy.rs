//! Calculs économiques purs (§6 de la spec).
//!
//! Aucune dépendance aux comptes Solana : ce module se teste sur l'hôte,
//! ce qui rend le TDD praticable sur la partie où les bugs coûtent le plus cher.

/// Longueur d'un jour UTC en secondes. Le jour est la seule unité de temps métier.
pub const DAY_SECONDS: i64 = 86_400;
/// Dénominateur des points de base.
pub const BPS_DENOMINATOR: u64 = 10_000;

/// Jour UTC contenant cet horodatage. `div_euclid` pour que les timestamps
/// négatifs (antérieurs à 1970, atteignables en test) restent monotones.
pub fn day_of(unix_timestamp: i64) -> i64 {
    unix_timestamp.div_euclid(DAY_SECONDS)
}

/// Résultat d'un decay : ce qui reste au profil, ce qui part au pool.
/// `remaining + lost == staked` par construction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Decayed {
    pub remaining: u64,
    pub lost: u64,
}

/// Applique `decay_bps` par jour manqué. Au-delà de `max_decay_days`, le solde
/// tombe à zéro sans itérer : c'est la borne d'itération de §6.1.
pub fn decay(staked: u64, missed_days: i64, decay_bps: u16, max_decay_days: u8) -> Decayed {
    if missed_days <= 0 {
        return Decayed { remaining: staked, lost: 0 };
    }
    if missed_days > max_decay_days as i64 {
        return Decayed { remaining: 0, lost: staked };
    }
    let keep = (BPS_DENOMINATOR - decay_bps as u64) as u128;
    let denominator = BPS_DENOMINATOR as u128;
    let mut remaining = staked as u128;
    for _ in 0..missed_days {
        remaining = remaining * keep / denominator;
    }
    let remaining = remaining as u64;
    Decayed { remaining, lost: staked - remaining }
}

/// Récompense d'un check-in : pourcentage du solde, borné par le plafond
/// anti-baleine puis par ce que le pool peut réellement payer (§6.1).
pub fn reward(staked: u64, reward_rate_bps: u16, reward_cap: u64, pool_balance: u64) -> u64 {
    let raw = (staked as u128 * reward_rate_bps as u128 / BPS_DENOMINATOR as u128) as u64;
    raw.min(reward_cap).min(pool_balance)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SKR: u64 = 1_000_000_000;

    #[test]
    fn day_of_divides_utc_days_and_handles_negatives() {
        assert_eq!(day_of(0), 0);
        assert_eq!(day_of(86_399), 0);
        assert_eq!(day_of(86_400), 1);
        assert_eq!(day_of(-1), -1);
    }

    #[test]
    fn no_missed_day_keeps_everything() {
        let out = decay(100 * SKR, 0, 2500, 30);
        assert_eq!(out.remaining, 100 * SKR);
        assert_eq!(out.lost, 0);
    }

    #[test]
    fn one_missed_day_at_25_percent() {
        let out = decay(100 * SKR, 1, 2500, 30);
        assert_eq!(out.remaining, 75 * SKR);
        assert_eq!(out.lost, 25 * SKR);
    }

    #[test]
    fn two_missed_days_compound() {
        let out = decay(100 * SKR, 2, 2500, 30);
        assert_eq!(out.remaining, 56_250_000_000);
        assert_eq!(out.lost, 43_750_000_000);
    }

    #[test]
    fn fifty_percent_variant_matches_the_spec_table() {
        assert_eq!(decay(100 * SKR, 1, 5000, 30).remaining, 50 * SKR);
        assert_eq!(decay(100 * SKR, 2, 5000, 30).remaining, 25 * SKR);
    }

    #[test]
    fn beyond_max_decay_days_everything_is_lost() {
        let out = decay(100 * SKR, 31, 2500, 30);
        assert_eq!(out.remaining, 0);
        assert_eq!(out.lost, 100 * SKR);
    }

    #[test]
    fn remaining_plus_lost_always_equals_the_input() {
        for missed in 0..40i64 {
            for staked in [0u64, 1, 7, 12_345_678, u64::MAX / 2] {
                let out = decay(staked, missed, 2500, 30);
                assert_eq!(out.remaining + out.lost, staked, "missed={missed} staked={staked}");
            }
        }
    }

    #[test]
    fn reward_is_a_percentage_of_the_stake() {
        assert_eq!(reward(100 * SKR, 100, 10 * SKR, 1000 * SKR), SKR);
    }

    #[test]
    fn reward_is_capped_by_reward_cap() {
        assert_eq!(reward(10_000 * SKR, 100, SKR, 1000 * SKR), SKR);
    }

    #[test]
    fn reward_is_capped_by_the_pool() {
        assert_eq!(reward(100 * SKR, 100, 10 * SKR, SKR / 2), SKR / 2);
    }

    #[test]
    fn empty_pool_pays_nothing() {
        assert_eq!(reward(100 * SKR, 100, 10 * SKR, 0), 0);
    }
}
