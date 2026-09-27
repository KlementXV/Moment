use anchor_lang::prelude::*;

use crate::{
    constants::DAY_POOL_SEED,
    economy::{day_of, pool_closes_at, route_penalty, share, split_decay, Dest},
    error::MomentError,
    state::{Config, DayPool, Profile, NO_PENDING_DAY, PENDING_SLOTS},
};

pub struct PoolAccounts<'a, 'info> {
    accounts: &'a [AccountInfo<'info>],
    program_id: &'a Pubkey,
}

impl<'a, 'info> PoolAccounts<'a, 'info> {
    pub fn new(accounts: &'a [AccountInfo<'info>], program_id: &'a Pubkey) -> Self {
        Self { accounts, program_id }
    }

    fn created(&self, day: i64) -> Result<Option<(&'a AccountInfo<'info>, DayPool)>> {
        for info in self.accounts {
            if info.owner != self.program_id || info.data_is_empty() {
                continue;
            }
            let Ok(pool) = DayPool::try_deserialize(&mut &info.try_borrow_data()?[..]) else {
                continue;
            };
            if pool.day != day {
                continue;
            }
            let expected = Pubkey::create_program_address(
                &[DAY_POOL_SEED, &day.to_le_bytes(), &[pool.bump]],
                self.program_id,
            )
            .map_err(|_| error!(MomentError::MissingDayPool))?;
            require_keys_eq!(*info.key, expected, MomentError::MissingDayPool);
            return Ok(Some((info, pool)));
        }
        Ok(None)
    }

    fn read(&self, day: i64) -> Result<Option<(&'a AccountInfo<'info>, DayPool)>> {
        if let Some(found) = self.created(day)? {
            return Ok(Some(found));
        }
        let (address, _) =
            Pubkey::find_program_address(&[DAY_POOL_SEED, &day.to_le_bytes()], self.program_id);
        require!(
            self.accounts.iter().any(|info| info.key == &address),
            MomentError::MissingDayPool
        );
        Ok(None)
    }

    fn add_penalty(info: &AccountInfo, mut pool: DayPool, amount: u64) -> Result<()> {
        require!(info.is_writable, MomentError::MissingDayPool);
        pool.penalties = pool
            .penalties
            .checked_add(amount)
            .ok_or(MomentError::InvalidAmount)?;
        let mut data = info.try_borrow_mut_data()?;
        pool.try_serialize(&mut &mut data[..])?;
        Ok(())
    }
}

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
        let (_, pool) = pools
            .read(day)?
            .ok_or_else(|| error!(MomentError::MissingDayPool))?;
        let gain = share(pool.penalties, profile.pending_stakes[slot], pool.total_stake);
        profile.staked = profile
            .staked
            .checked_add(gain)
            .ok_or(MomentError::InvalidAmount)?;
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

    let penalty_pool = if now < pool_closes_at(through_day, delay) {
        pools.read(through_day)?
    } else {
        None
    };
    let total_stake = penalty_pool.as_ref().map_or(0, |(_, pool)| pool.total_stake);
    let mut to_today = older;
    match route_penalty(through_day, day_of(now), now, delay, total_stake) {
        Dest::Day(_) => {
            let (info, pool) = penalty_pool.ok_or_else(|| error!(MomentError::MissingDayPool))?;
            PoolAccounts::add_penalty(info, pool, last)?
        }
        Dest::Today => {
            to_today = to_today
                .checked_add(last)
                .ok_or(MomentError::InvalidAmount)?
        }
    }
    today_pool.penalties = today_pool
        .penalties
        .checked_add(to_today)
        .ok_or(MomentError::InvalidAmount)?;
    Ok(work + 1)
}
