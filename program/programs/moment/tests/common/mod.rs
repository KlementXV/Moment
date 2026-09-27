#![allow(dead_code)]
#![allow(clippy::result_large_err)]

use {
    anchor_lang::{
        prelude::Pubkey,
        solana_program::{
            instruction::{AccountMeta, Instruction},
            system_program,
        },
        AccountDeserialize, InstructionData, ToAccountMetas,
    },
    moment::{
        economy::settle_bound,
        state::{CheckIn, Config, DayPool, Profile},
    },
    litesvm::{types::TransactionResult, LiteSVM},
    litesvm_token::{spl_token, CreateAssociatedTokenAccount, CreateMint},
    solana_clock::Clock,
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
};

pub const SKR: u64 = 1_000_000_000;
pub const MIN_STAKE: u64 = 10 * SKR;
pub const FAUCET_AMOUNT: u64 = 100 * SKR;
pub const DAY: i64 = 86_400;
pub const CLOSE_DELAY: i64 = 21_600;
const ORIGIN: i64 = 1_789_041_600;

fn assert_sbpf_v0(program_bytes: &[u8]) {
    let flags = u32::from_le_bytes(program_bytes[48..52].try_into().unwrap());
    assert_eq!(
        flags, 0,
        "moment.so est compilé en SBPF v{flags} : relance `anchor build --arch v0`"
    );
}

pub struct User {
    pub keypair: Keypair,
    pub profile: Pubkey,
    pub token_account: Pubkey,
}

impl User {
    pub fn pubkey(&self) -> Pubkey {
        self.keypair.pubkey()
    }
}

pub struct Ctx {
    pub svm: LiteSVM,
    pub admin: Keypair,
    pub authority: Keypair,
    pub mint: Pubkey,
    pub config: Pubkey,
    pub vault: Pubkey,
    pub admin_token: Option<Pubkey>,
    pub origin_day: i64,
}

impl Ctx {
    pub fn empty() -> Ctx {
        let mut svm = LiteSVM::new();
        let program_bytes = include_bytes!(concat!(
            env!("CARGO_TARGET_TMPDIR"),
            "/../deploy/moment.so"
        ));
        assert_sbpf_v0(program_bytes);
        svm.add_program(moment::id(), program_bytes).unwrap();

        let mut clock = svm.get_sysvar::<Clock>();
        clock.unix_timestamp = ORIGIN;
        svm.set_sysvar(&clock);

        let admin = Keypair::new();
        let authority = Keypair::new();
        // LiteSVM installs an immutable program by default. Model a real deployment.
        let program_data = Pubkey::find_program_address(
            &[moment::id().as_ref()],
            &anchor_lang::solana_program::bpf_loader_upgradeable::ID,
        ).0;
        let mut data_account = svm.get_account(&program_data).unwrap();
        data_account.data[12] = 1; // Some(upgrade_authority)
        data_account.data[13..45].copy_from_slice(admin.pubkey().as_ref());
        svm.set_account(program_data, data_account).unwrap();
        svm.airdrop(&admin.pubkey(), 100 * 1_000_000_000).unwrap();
        svm.airdrop(&authority.pubkey(), 1_000_000_000).unwrap();

        let (config, _) =
            Pubkey::find_program_address(&[moment::constants::CONFIG_SEED], &moment::id());
        let (vault, _) =
            Pubkey::find_program_address(&[moment::constants::VAULT_SEED], &moment::id());

        let mint = CreateMint::new(&mut svm, &admin)
            .authority(&config)
            .decimals(9)
            .send()
            .unwrap();

        Ctx {
            svm,
            admin,
            authority,
            mint,
            config,
            vault,
            admin_token: None,
            origin_day: ORIGIN.div_euclid(DAY),
        }
    }

    pub fn new() -> Ctx {
        let mut ctx = Ctx::empty();
        ctx.initialize_config_with(|_| {}).unwrap();
        ctx
    }

    pub fn initialize_config_with(
        &mut self,
        adjust: impl FnOnce(&mut moment::instructions::ConfigParams),
    ) -> TransactionResult {
        let mut params = moment::instructions::ConfigParams {
            min_stake: MIN_STAKE,
            faucet_amount: FAUCET_AMOUNT,
            withdrawal_delay_seconds: 172_800,
            pool_close_delay_seconds: CLOSE_DELAY,
            decay_bps: 2500,
            max_decay_days: 30,
            faucet_enabled: true,
        };
        adjust(&mut params);
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::InitializeConfig {
                program_data: Pubkey::find_program_address(
                    &[moment::id().as_ref()],
                    &anchor_lang::solana_program::bpf_loader_upgradeable::ID,
                ).0,
                admin: self.admin.pubkey(),
                publication_authority: self.authority.pubkey(),
                config: self.config,
                skr_mint: self.mint,
                vault: self.vault,
                token_program: spl_token::ID,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: moment::instruction::InitializeConfig { params }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn admin_token_account(&mut self) -> Pubkey {
        if let Some(existing) = self.admin_token {
            return existing;
        }
        let admin = self.admin.insecure_clone();
        let mint = self.mint;
        let owner = admin.pubkey();
        let account = CreateAssociatedTokenAccount::new(&mut self.svm, &admin, &mint)
            .owner(&owner)
            .send()
            .unwrap();
        self.admin_token = Some(account);
        account
    }

    pub fn seed_pool(&mut self, amount: u64) -> TransactionResult {
        let admin_token = self.admin_token_account();
        self.mint_for_tests(&admin_token, amount);
        let day = self.today();
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::SeedPool {
                admin: self.admin.pubkey(),
                config: self.config,
                day_pool: self.day_pool_address(day),
                skr_mint: self.mint,
                admin_token_account: admin_token,
                vault: self.vault,
                token_program: spl_token::ID,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: moment::instruction::SeedPool { day, amount }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn mint_for_tests(&mut self, token_account: &Pubkey, amount: u64) {
        use solana_program_pack::Pack;
        let mut account = self
            .svm
            .get_account(token_account)
            .expect("compte de tokens absent");
        let length = spl_token::state::Account::LEN;
        let mut state = spl_token::state::Account::unpack(&account.data[..length]).unwrap();
        state.amount += amount;
        spl_token::state::Account::pack(state, &mut account.data[..length]).unwrap();
        self.svm.set_account(*token_account, account).unwrap();
    }

    pub fn update_config(
        &mut self,
        adjust: impl FnOnce(&mut moment::instructions::ConfigParams),
    ) -> TransactionResult {
        let admin = self.admin.insecure_clone();
        self.update_config_as(&admin, adjust)
    }

    pub fn update_config_as(
        &mut self,
        signer: &Keypair,
        adjust: impl FnOnce(&mut moment::instructions::ConfigParams),
    ) -> TransactionResult {
        let current = self.config_state();
        let mut params = moment::instructions::ConfigParams {
            min_stake: current.min_stake,
            faucet_amount: current.faucet_amount,
            withdrawal_delay_seconds: current.withdrawal_delay_seconds,
            pool_close_delay_seconds: current.pool_close_delay_seconds,
            decay_bps: current.decay_bps,
            max_decay_days: current.max_decay_days,
            faucet_enabled: current.faucet_enabled,
        };
        adjust(&mut params);
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::UpdateConfig {
                admin: signer.pubkey(),
                config: self.config,
            }
            .to_account_metas(None),
            data: moment::instruction::UpdateConfig { params }.data(),
        };
        let signer = signer.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn set_publication_authority(&mut self, new_authority: &Pubkey) -> TransactionResult {
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::UpdateConfig {
                admin: self.admin.pubkey(),
                config: self.config,
            }
            .to_account_metas(None),
            data: moment::instruction::SetPublicationAuthority {
                new_authority: *new_authority,
            }
            .data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn send(&mut self, instructions: &[Instruction], signers: &[&Keypair]) -> TransactionResult {
        let payer = signers[0].pubkey();
        let blockhash = self.svm.latest_blockhash();
        let message = Message::new_with_blockhash(instructions, Some(&payer), &blockhash);
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(message), signers).unwrap();
        let result = self.svm.send_transaction(tx);
        self.svm.expire_blockhash();
        result
    }

    pub fn now(&self) -> i64 {
        self.svm.get_sysvar::<Clock>().unix_timestamp
    }

    pub fn today(&self) -> i64 {
        self.now().div_euclid(DAY)
    }

    pub fn warp_days(&mut self, days: i64) {
        self.warp_seconds(days * DAY);
    }

    pub fn warp_seconds(&mut self, seconds: i64) {
        let mut clock = self.svm.get_sysvar::<Clock>();
        clock.unix_timestamp += seconds;
        clock.slot += (seconds.max(0) as u64) * 1000 / 400;
        self.svm.set_sysvar(&clock);
        self.svm.expire_blockhash();
    }

    pub fn config_state(&self) -> Config {
        let account = self.svm.get_account(&self.config).expect("config absente");
        Config::try_deserialize(&mut account.data.as_slice()).unwrap()
    }

    pub fn profile_state(&self, profile: &Pubkey) -> Profile {
        let account = self.svm.get_account(profile).expect("profil absent");
        Profile::try_deserialize(&mut account.data.as_slice()).unwrap()
    }

    pub fn check_in_state(&self, check_in: &Pubkey) -> CheckIn {
        let account = self.svm.get_account(check_in).expect("check-in absent");
        CheckIn::try_deserialize(&mut account.data.as_slice()).unwrap()
    }

    pub fn token_balance(&self, token_account: &Pubkey) -> u64 {
        litesvm_token::get_spl_account::<spl_token::state::Account>(&self.svm, token_account)
            .map(|account| account.amount)
            .unwrap_or(0)
    }

    pub fn vault_balance(&self) -> u64 {
        self.token_balance(&self.vault)
    }

    pub fn profile_address(&self, owner: &Pubkey) -> Pubkey {
        Pubkey::find_program_address(
            &[moment::constants::PROFILE_SEED, owner.as_ref()],
            &moment::id(),
        )
        .0
    }

    pub fn check_in_address(&self, owner: &Pubkey, day: i64) -> Pubkey {
        Pubkey::find_program_address(
            &[moment::constants::CHECKIN_SEED, owner.as_ref(), &day.to_le_bytes()],
            &moment::id(),
        )
        .0
    }

    pub fn day_pool_address(&self, day: i64) -> Pubkey {
        Pubkey::find_program_address(
            &[moment::constants::DAY_POOL_SEED, &day.to_le_bytes()],
            &moment::id(),
        )
        .0
    }

    pub fn day_pool_state(&self, day: i64) -> Option<DayPool> {
        let account = self.svm.get_account(&self.day_pool_address(day))?;
        if account.data.is_empty() {
            return None;
        }
        Some(DayPool::try_deserialize(&mut account.data.as_slice()).unwrap())
    }

    pub fn pooled(&self) -> u64 {
        (self.origin_day - 1..=self.today())
            .filter_map(|day| self.day_pool_state(day))
            .map(|pool| pool.penalties)
            .sum()
    }

    pub fn warp_to_next(&mut self, second_of_day: i64) {
        let now = self.now();
        let mut target = now.div_euclid(DAY) * DAY + second_of_day;
        if target <= now {
            target += DAY;
        }
        self.warp_seconds(target - now);
    }

    fn stored_profile(&self, owner: &Pubkey) -> Option<Profile> {
        let account = self.svm.get_account(&self.profile_address(owner))?;
        if account.data.is_empty() {
            return None;
        }
        Some(Profile::try_deserialize(&mut account.data.as_slice()).unwrap())
    }

    pub fn pool_metas(&self, owner: &Pubkey, bound: i64) -> Vec<AccountMeta> {
        let Some(profile) = self.stored_profile(owner) else {
            return vec![];
        };
        let mut metas: Vec<AccountMeta> = profile
            .pending_days
            .iter()
            .filter(|day| **day >= 0)
            .map(|day| AccountMeta::new_readonly(self.day_pool_address(*day), false))
            .collect();
        if profile.active && bound > profile.settled_day {
            metas.push(AccountMeta::new(self.day_pool_address(bound), false));
        }
        metas
    }

    pub fn honest_pools(&self, owner: &Pubkey) -> Vec<AccountMeta> {
        let unlock = self.stored_profile(owner).map_or(0, |profile| profile.exit_unlock_at);
        self.pool_metas(owner, settle_bound(unlock, self.today()))
    }

    pub fn create_profile(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::CreateProfile {
                owner: user.pubkey(),
                profile: user.profile,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: moment::instruction::CreateProfile {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn faucet(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::Faucet {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
                skr_mint: self.mint,
                owner_token_account: user.token_account,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: moment::instruction::Faucet {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn stake(&mut self, user: &User, amount: u64) -> TransactionResult {
        let day = self.today();
        let mut accounts = moment::accounts::Stake {
            owner: user.pubkey(),
            config: self.config,
            profile: user.profile,
            day_pool: self.day_pool_address(day),
            skr_mint: self.mint,
            owner_token_account: user.token_account,
            vault: self.vault,
            token_program: spl_token::ID,
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(self.honest_pools(&user.pubkey()));
        let instruction = Instruction {
            program_id: moment::id(),
            accounts,
            data: moment::instruction::Stake { day, amount }.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn check_in(&mut self, user: &User) -> TransactionResult {
        let authority = self.authority.insecure_clone();
        let day = self.today();
        self.check_in_full(user, &authority, day, [7u8; 32], [9u8; 32])
    }

    pub fn check_in_signed_by(&mut self, user: &User, authority: &Keypair) -> TransactionResult {
        let day = self.today();
        self.check_in_full(user, authority, day, [7u8; 32], [9u8; 32])
    }

    pub fn check_in_on_day(&mut self, user: &User, day: i64) -> TransactionResult {
        let authority = self.authority.insecure_clone();
        self.check_in_full(user, &authority, day, [7u8; 32], [9u8; 32])
    }

    pub fn check_in_full(
        &mut self,
        user: &User,
        authority: &Keypair,
        day: i64,
        commitment: [u8; 32],
        blob_ref: [u8; 32],
    ) -> TransactionResult {
        let mut accounts = moment::accounts::CheckInAccounts {
            owner: user.pubkey(),
            publication_authority: authority.pubkey(),
            config: self.config,
            profile: user.profile,
            day_pool: self.day_pool_address(day),
            check_in: self.check_in_address(&user.pubkey(), day),
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(self.honest_pools(&user.pubkey()));
        let instruction = Instruction {
            program_id: moment::id(),
            accounts,
            data: moment::instruction::CheckIn { day, commitment, blob_ref }.data(),
        };
        let owner = user.keypair.insecure_clone();
        let authority = authority.insecure_clone();
        self.send(&[instruction], &[&owner, &authority])
    }

    pub fn reap(&mut self, owner: &Pubkey) -> TransactionResult {
        let day = self.today();
        let pools = self.honest_pools(owner);
        self.reap_with(owner, day, pools)
    }

    pub fn reap_with(&mut self, owner: &Pubkey, day: i64, pools: Vec<AccountMeta>) -> TransactionResult {
        let mut accounts = moment::accounts::Reap {
            caller: self.admin.pubkey(),
            owner: *owner,
            config: self.config,
            profile: self.profile_address(owner),
            day_pool: self.day_pool_address(day),
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(pools);
        let instruction = Instruction {
            program_id: moment::id(),
            accounts,
            data: moment::instruction::Reap { day }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn open_day_pool(&mut self) -> TransactionResult {
        let day = self.today();
        self.open_day_pool_on(day)
    }

    pub fn open_day_pool_on(&mut self, day: i64) -> TransactionResult {
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::OpenDayPool {
                caller: self.admin.pubkey(),
                day_pool: self.day_pool_address(day),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: moment::instruction::OpenDayPool { day }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn roll_over(&mut self, from_day: i64) -> TransactionResult {
        let day = self.today();
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::RollOverDayPool {
                caller: self.admin.pubkey(),
                config: self.config,
                from_pool: self.day_pool_address(from_day),
                day_pool: self.day_pool_address(day),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: moment::instruction::RollOverDayPool { day, from_day }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn close_check_in(&mut self, owner: &Pubkey, day: i64) -> TransactionResult {
        self.close_check_in_to(owner, owner, day)
    }

    pub fn close_check_in_to(&mut self, owner: &Pubkey, recipient: &Pubkey, day: i64) -> TransactionResult {
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::CloseCheckIn {
                caller: self.admin.pubkey(),
                owner: *recipient,
                check_in: self.check_in_address(owner, day),
            }
            .to_account_metas(None),
            data: moment::instruction::CloseCheckIn { day }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn request_exit(&mut self, user: &User) -> TransactionResult {
        let day = self.today();
        let mut accounts = moment::accounts::RequestExit {
            owner: user.pubkey(),
            config: self.config,
            profile: user.profile,
            day_pool: self.day_pool_address(day),
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(self.honest_pools(&user.pubkey()));
        let instruction = Instruction {
            program_id: moment::id(),
            accounts,
            data: moment::instruction::RequestExit { day }.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn cancel_exit(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: moment::id(),
            accounts: moment::accounts::CancelExit {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
            }
            .to_account_metas(None),
            data: moment::instruction::CancelExit {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn finalize_exit(&mut self, owner: &Pubkey, owner_token_account: &Pubkey) -> TransactionResult {
        let admin = self.admin.insecure_clone();
        self.finalize_exit_as(&admin, owner, owner_token_account)
    }

    pub fn finalize_exit_as(
        &mut self,
        caller: &Keypair,
        owner: &Pubkey,
        owner_token_account: &Pubkey,
    ) -> TransactionResult {
        let day = self.today();
        let bound = self
            .profile_state(&self.profile_address(owner))
            .exit_unlock_at
            .div_euclid(DAY)
            - 1;
        let mut accounts = moment::accounts::FinalizeExit {
            caller: caller.pubkey(),
            owner: *owner,
            config: self.config,
            profile: self.profile_address(owner),
            day_pool: self.day_pool_address(day),
            skr_mint: self.mint,
            owner_token_account: *owner_token_account,
            vault: self.vault,
            token_program: spl_token::ID,
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(self.pool_metas(owner, bound));
        let instruction = Instruction {
            program_id: moment::id(),
            accounts,
            data: moment::instruction::FinalizeExit { day }.data(),
        };
        let caller = caller.insecure_clone();
        self.send(&[instruction], &[&caller])
    }

    pub fn new_user(&mut self) -> User {
        let user = self.new_wallet();
        self.create_profile(&user).unwrap();
        self.faucet(&user).unwrap();
        user
    }

    pub fn new_wallet(&mut self) -> User {
        let keypair = Keypair::new();
        self.svm.airdrop(&keypair.pubkey(), 10 * 1_000_000_000).unwrap();
        let owner = keypair.pubkey();
        let profile = self.profile_address(&owner);
        let admin = self.admin.insecure_clone();
        let mint = self.mint;
        let token_account = CreateAssociatedTokenAccount::new(&mut self.svm, &admin, &mint)
            .owner(&owner)
            .send()
            .unwrap();
        User { profile, keypair, token_account }
    }
}
