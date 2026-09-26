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

/// Dernier jour pénalisable. Hier en temps normal ; pendant une sortie, jamais
/// au-delà du dernier jour entièrement terminé avant le déblocage (§6.3, §6.4).
pub fn settle_bound(exit_unlock_at: i64, today: i64) -> i64 {
    let by_today = today - 1;
    if exit_unlock_at > 0 {
        by_today.min(day_of(exit_unlock_at) - 1)
    } else {
        by_today
    }
}

/// Destination d'une pénalité réglée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dest {
    /// Le pool du jour pénalisé, encore ouvert et doté de publieurs.
    Day(i64),
    /// Le pool du jour courant.
    Today,
}

/// Part d'un publieur : prorata de sa mise, arrondi à l'inférieur. La poussière
/// reste dans le vault, non attribuée. Bornée par `penalties` : une mise
/// incohérente ne peut jamais faire payer plus que le pool.
pub fn share(penalties: u64, stake: u64, total_stake: u64) -> u64 {
    if total_stake == 0 {
        return 0;
    }
    let raw = penalties as u128 * stake as u128 / total_stake as u128;
    raw.min(penalties as u128) as u64
}

/// Clôture du pool de `day` : après elle, on réclame ; avant, on y verse.
pub fn pool_closes_at(day: i64, delay: i64) -> i64 {
    day.saturating_add(1)
        .saturating_mul(DAY_SECONDS)
        .saturating_add(delay)
}

/// Où verser la pénalité du jour `penalty_day`. Le total des mises d'un jour
/// passé est définitif : on ne publie pour D que pendant D.
pub fn route_penalty(
    penalty_day: i64,
    today: i64,
    now: i64,
    delay: i64,
    total_stake_of_day: u64,
) -> Dest {
    let open = penalty_day < today && now < pool_closes_at(penalty_day, delay);
    if open && total_stake_of_day > 0 {
        Dest::Day(penalty_day)
    } else {
        Dest::Today
    }
}

/// Sépare la perte du dernier jour manqué de celle des jours précédents :
/// seul le dernier peut encore avoir un pool ouvert.
/// `lost_older + lost_last_day == decay(...).lost`.
pub fn split_decay(staked: u64, missed_days: i64, decay_bps: u16, max_decay_days: u8) -> (u64, u64) {
    if missed_days <= 0 {
        return (0, 0);
    }
    let before_last = decay(staked, missed_days - 1, decay_bps, max_decay_days).remaining;
    let after = decay(staked, missed_days, decay_bps, max_decay_days).remaining;
    (staked - before_last, before_last - after)
}

/// Un check-in se ferme à partir de J+2 : le keyserver relit celui du jour
/// (feed) et celui de la veille (confirmation à cheval sur minuit).
pub fn check_in_closable(check_in_day: i64, today: i64) -> bool {
    check_in_day <= today - 2
}

/// Délai minimal de clôture : le crank passe à 00:05 puis toutes les 15 min et
/// doit pouvoir régler les absents (et réessayer) avant que le pool se ferme.
pub const MIN_CLOSE_DELAY: i64 = 3_600;

/// La clôture doit tomber avant la fin du lendemain : c'est ce qui borne à deux
/// le nombre de créances ouvertes d'un profil.
pub fn valid_close_delay(delay: i64) -> bool {
    (MIN_CLOSE_DELAY..DAY_SECONDS).contains(&delay)
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
    fn settle_bound_stops_at_yesterday_without_a_pending_exit() {
        assert_eq!(settle_bound(0, 100), 99);
    }

    #[test]
    fn settle_bound_never_passes_the_last_full_day_before_unlock() {
        // Déblocage au milieu du jour 101 : le dernier jour entièrement terminé
        // avant le déblocage est le jour 100 (§6.3).
        let unlock_at = 101 * DAY_SECONDS + 3_600;
        assert_eq!(settle_bound(unlock_at, 105), 100);
        assert_eq!(settle_bound(unlock_at, 100), 99, "on ne pénalise jamais au-delà d'hier");
    }

    #[test]
    fn share_is_pro_rata_of_the_stake() {
        assert_eq!(share(10 * SKR, 100 * SKR, 200 * SKR), 5 * SKR);
    }

    #[test]
    fn share_rounds_down_and_leaves_the_dust_in_the_vault() {
        assert_eq!(share(10, 1, 3), 3);
        let paid: u64 = (0..3).map(|_| share(10, 1, 3)).sum();
        assert!(paid <= 10);
        assert_eq!(share(1, 1, 3), 0);
    }

    #[test]
    fn share_of_a_day_without_publishers_is_zero() {
        assert_eq!(share(10 * SKR, 0, 0), 0);
        assert_eq!(share(10 * SKR, 5 * SKR, 0), 0);
    }

    #[test]
    fn share_never_exceeds_the_pool() {
        // Mise incohérente (supérieure au total) : on ne paie jamais plus que le pool.
        assert_eq!(share(10, 5, 3), 10);
        assert_eq!(share(u64::MAX, u64::MAX, u64::MAX), u64::MAX);
    }

    #[test]
    fn a_pool_closes_the_next_morning() {
        assert_eq!(pool_closes_at(100, 21_600), 101 * DAY_SECONDS + 21_600);
        assert_eq!(pool_closes_at(100, 0), 101 * DAY_SECONDS);
    }

    #[test]
    fn a_penalty_goes_to_its_own_day_while_the_pool_is_open() {
        let closes = pool_closes_at(100, 21_600);
        assert_eq!(route_penalty(100, 101, closes - 1, 21_600, 50), Dest::Day(100));
    }

    #[test]
    fn a_penalty_settled_after_closure_goes_to_today() {
        let closes = pool_closes_at(100, 21_600);
        assert_eq!(route_penalty(100, 101, closes, 21_600, 50), Dest::Today);
        assert_eq!(route_penalty(100, 102, closes + DAY_SECONDS, 21_600, 50), Dest::Today);
    }

    #[test]
    fn a_penalty_of_a_day_without_publishers_goes_to_today() {
        let closes = pool_closes_at(100, 21_600);
        assert_eq!(route_penalty(100, 101, closes - 1, 21_600, 0), Dest::Today);
    }

    #[test]
    fn today_is_never_a_penalty_destination_for_itself() {
        assert_eq!(route_penalty(101, 101, 101 * DAY_SECONDS, 21_600, 50), Dest::Today);
    }

    #[test]
    fn split_decay_isolates_the_last_missed_day() {
        assert_eq!(split_decay(100 * SKR, 0, 1000, 30), (0, 0));
        assert_eq!(split_decay(100 * SKR, 1, 1000, 30), (0, 10 * SKR));
        // 100 → 90 → 81 : 10 SKR pour les jours anciens, 9 pour le dernier.
        assert_eq!(split_decay(100 * SKR, 2, 1000, 30), (10 * SKR, 9 * SKR));
    }

    #[test]
    fn split_decay_always_sums_to_the_decay() {
        for missed in 0..40i64 {
            for staked in [0u64, 1, 7, 12_345_678, u64::MAX / 2] {
                let (older, last) = split_decay(staked, missed, 1000, 30);
                assert_eq!(
                    older + last,
                    decay(staked, missed, 1000, 30).lost,
                    "missed={missed} staked={staked}"
                );
            }
        }
    }

    #[test]
    fn a_check_in_closes_from_the_day_after_tomorrow() {
        assert!(!check_in_closable(100, 100));
        assert!(!check_in_closable(100, 101));
        assert!(check_in_closable(100, 102));
        assert!(check_in_closable(100, 150));
    }

    #[test]
    fn the_close_delay_must_stay_within_a_day() {
        // Au moins une heure : le crank passe à 00:05 puis toutes les 15 min, il
        // doit pouvoir régler les absents (et réessayer) avant la clôture.
        assert!(!valid_close_delay(0));
        assert!(!valid_close_delay(3_599));
        assert!(valid_close_delay(3_600));
        assert!(valid_close_delay(21_600));
        assert!(valid_close_delay(DAY_SECONDS - 1));
        assert!(!valid_close_delay(DAY_SECONDS));
        assert!(!valid_close_delay(-1));
    }
}
