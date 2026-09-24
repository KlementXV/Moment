//! Encaissement des créances et routage des pénalités (spec pool journalier).
use anchor_lang::prelude::*;

use crate::{
    constants::DAY_POOL_SEED,
    economy::{day_of, pool_closes_at, route_penalty, share, split_decay, Dest},
    error::ClockInError,
    state::{Config, DayPool, Profile, NO_PENDING_DAY, PENDING_SLOTS},
};

/// Pools passés en `remaining_accounts` : ceux des créances clôturées (lus) et
/// celui du dernier jour manqué (écrit). L'adresse fait foi, pas l'ordre.
pub struct PoolAccounts<'a, 'info> {
    accounts: &'a [AccountInfo<'info>],
    program_id: &'a Pubkey,
}

impl<'a, 'info> PoolAccounts<'a, 'info> {
    pub fn new(accounts: &'a [AccountInfo<'info>], program_id: &'a Pubkey) -> Self {
        Self { accounts, program_id }
    }

    /// Un compte attendu et absent est une erreur : sauter une créance ou
    /// dérouter une pénalité en silence serait pire qu'un échec.
    fn find(&self, day: i64) -> Result<&'a AccountInfo<'info>> {
        let (address, _) =
            Pubkey::find_program_address(&[DAY_POOL_SEED, &day.to_le_bytes()], self.program_id);
        self.accounts
            .iter()
            .find(|info| info.key == &address)
            .ok_or_else(|| error!(ClockInError::MissingDayPool))
    }

    /// `None` : personne n'a publié ce jour-là, le pool n'a jamais été créé.
    fn read(&self, day: i64) -> Result<Option<DayPool>> {
        let info = self.find(day)?;
        if info.owner != self.program_id || info.data_is_empty() {
            return Ok(None);
        }
        let data = info.try_borrow_data()?;
        Ok(Some(DayPool::try_deserialize(&mut &data[..])?))
    }

    fn add_penalty(&self, day: i64, amount: u64) -> Result<()> {
        let info = self.find(day)?;
        require!(info.is_writable, ClockInError::MissingDayPool);
        let mut pool = self
            .read(day)?
            .ok_or_else(|| error!(ClockInError::MissingDayPool))?;
        pool.penalties = pool
            .penalties
            .checked_add(amount)
            .ok_or(ClockInError::InvalidAmount)?;
        let mut data = info.try_borrow_mut_data()?;
        pool.try_serialize(&mut &mut data[..])?;
        Ok(())
    }
}

/// Encaisse les créances clôturées, puis règle les jours manqués jusqu'à
/// `through_day`. Encaisser d'abord : le gain de D arrive le matin de D+1 et
/// subit donc, comme le reste du solde, la pénalité d'une absence en D+1.
/// Renvoie le nombre d'opérations faites ; `reap` refuse zéro.
pub fn settle(
    profile: &mut Profile,
    config: &Config,
    today_pool: &mut DayPool,
    pools: &PoolAccounts,
    now: i64,
    through_day: i64,
) -> Result<u32> {
    let delay = config.pool_close_delay_seconds;
    let mut work = 0;
    for slot in 0..PENDING_SLOTS {
        let day = profile.pending_days[slot];
        if day == NO_PENDING_DAY || now < pool_closes_at(day, delay) {
            continue;
        }
        let pool = pools
            .read(day)?
            .ok_or_else(|| error!(ClockInError::MissingDayPool))?;
        let gain = share(pool.penalties, profile.pending_stakes[slot], pool.total_stake);
        profile.staked = profile
            .staked
            .checked_add(gain)
            .ok_or(ClockInError::InvalidAmount)?;
        profile.pending_days[slot] = NO_PENDING_DAY;
        profile.pending_stakes[slot] = 0;
        work += 1;
    }

    let missed = through_day - profile.settled_day;
    if !profile.active || missed <= 0 {
        return Ok(work);
    }
    let (older, last) = split_decay(
        profile.staked,
        missed,
        config.decay_bps,
        config.max_decay_days,
    );
    profile.staked -= older + last;
    profile.settled_day = through_day;
    profile.streak = 0;

    // Un pool clôturé ne reçoit plus rien : inutile d'exiger son compte.
    let total_stake = if now < pool_closes_at(through_day, delay) {
        pools.read(through_day)?.map_or(0, |pool| pool.total_stake)
    } else {
        0
    };
    let mut to_today = older;
    match route_penalty(through_day, day_of(now), now, delay, total_stake) {
        Dest::Day(day) => pools.add_penalty(day, last)?,
        Dest::Today => {
            to_today = to_today
                .checked_add(last)
                .ok_or(ClockInError::InvalidAmount)?
        }
    }
    today_pool.penalties = today_pool
        .penalties
        .checked_add(to_today)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(work + 1)
}
