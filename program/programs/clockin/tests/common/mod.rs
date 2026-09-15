#![allow(dead_code)]

use {
    anchor_lang::{
        prelude::Pubkey,
        solana_program::{instruction::Instruction, system_program},
        AccountDeserialize, InstructionData, ToAccountMetas,
    },
    clockin::state::{CheckIn, Config, Profile},
    litesvm::{types::TransactionResult, LiteSVM},
    litesvm_token::{spl_token, CreateAssociatedTokenAccount, CreateMint},
    solana_clock::Clock,
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
};

/// Unité de travail : mint de test à 9 décimales.
pub const SKR: u64 = 1_000_000_000;
pub const MIN_STAKE: u64 = 10 * SKR;
pub const FAUCET_AMOUNT: u64 = 100 * SKR;
pub const DAY: i64 = 86_400;

/// litesvm 0.10 embarque le runtime agave 3.1, qui ne charge pas les ELF SBPF v3
/// produits par défaut par `anchor build`. Sans cette garde, l'échec est un
/// `InvalidAccountData` opaque au chargement du programme.
fn assert_sbpf_v0(program_bytes: &[u8]) {
    let flags = u32::from_le_bytes(program_bytes[48..52].try_into().unwrap());
    assert_eq!(
        flags, 0,
        "clockin.so est compilé en SBPF v{flags} : relance `anchor build --arch v0`"
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
    /// Autorité de publication : co-signe `check_in` (D4).
    pub authority: Keypair,
    pub mint: Pubkey,
    pub config: Pubkey,
    pub vault: Pubkey,
    pub admin_token: Option<Pubkey>,
}

impl Ctx {
    /// Déploie le programme et crée le mint, sans initialiser la configuration.
    pub fn empty() -> Ctx {
        let mut svm = LiteSVM::new();
        let program_bytes = include_bytes!(concat!(
            env!("CARGO_TARGET_TMPDIR"),
            "/../deploy/clockin.so"
        ));
        assert_sbpf_v0(program_bytes);
        svm.add_program(clockin::id(), program_bytes).unwrap();

        // Horloge figée au 2026-09-10 à 12 h UTC. La position de l'heure dans la
        // journée décide quel jour est « entièrement terminé avant le déblocage »
        // (§6.3) : la laisser dépendre de l'heure réelle rendrait ces tests instables.
        let mut clock = svm.get_sysvar::<Clock>();
        clock.unix_timestamp = 1_789_041_600;
        svm.set_sysvar(&clock);

        let admin = Keypair::new();
        let authority = Keypair::new();
        svm.airdrop(&admin.pubkey(), 100 * 1_000_000_000).unwrap();
        svm.airdrop(&authority.pubkey(), 1_000_000_000).unwrap();

        let (config, _) =
            Pubkey::find_program_address(&[clockin::constants::CONFIG_SEED], &clockin::id());
        let (vault, _) =
            Pubkey::find_program_address(&[clockin::constants::VAULT_SEED], &clockin::id());

        // L'autorité de mint est le PDA Config : seul le programme peut créer du SKR de test.
        let mint = CreateMint::new(&mut svm, &admin)
            .authority(&config)
            .decimals(9)
            .send()
            .unwrap();

        Ctx { svm, admin, authority, mint, config, vault, admin_token: None }
    }

    /// Déploie et initialise avec les paramètres de travail de la feuille de route.
    pub fn new() -> Ctx {
        let mut ctx = Ctx::empty();
        ctx.initialize_config_with(|_| {}).unwrap();
        ctx
    }

    pub fn initialize_config_with(
        &mut self,
        adjust: impl FnOnce(&mut clockin::instructions::ConfigParams),
    ) -> TransactionResult {
        let mut params = clockin::instructions::ConfigParams {
            min_stake: MIN_STAKE,
            reward_cap: SKR,
            faucet_amount: FAUCET_AMOUNT,
            withdrawal_delay_seconds: 172_800,
            reward_rate_bps: 100,
            decay_bps: 2500,
            max_decay_days: 30,
            faucet_enabled: true,
        };
        adjust(&mut params);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::InitializeConfig {
                admin: self.admin.pubkey(),
                publication_authority: self.authority.pubkey(),
                config: self.config,
                skr_mint: self.mint,
                vault: self.vault,
                token_program: spl_token::ID,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::InitializeConfig { params }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    /// Compte de tokens de l'admin, créé à la demande une seule fois.
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

    /// Amorce le pool : approvisionne l'admin puis dépose dans le vault.
    pub fn seed_pool(&mut self, amount: u64) -> TransactionResult {
        let admin_token = self.admin_token_account();
        self.mint_for_tests(&admin_token, amount);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::SeedPool {
                admin: self.admin.pubkey(),
                config: self.config,
                skr_mint: self.mint,
                admin_token_account: admin_token,
                vault: self.vault,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::SeedPool { amount }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    /// Écrit directement un solde de tokens dans un compte existant.
    /// L'autorité de mint appartenant au programme, c'est le seul moyen pour un
    /// test de fabriquer un solde arbitraire sans passer par le faucet métier.
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
        adjust: impl FnOnce(&mut clockin::instructions::ConfigParams),
    ) -> TransactionResult {
        let admin = self.admin.insecure_clone();
        self.update_config_as(&admin, adjust)
    }

    pub fn update_config_as(
        &mut self,
        signer: &Keypair,
        adjust: impl FnOnce(&mut clockin::instructions::ConfigParams),
    ) -> TransactionResult {
        let current = self.config_state();
        let mut params = clockin::instructions::ConfigParams {
            min_stake: current.min_stake,
            reward_cap: current.reward_cap,
            faucet_amount: current.faucet_amount,
            withdrawal_delay_seconds: current.withdrawal_delay_seconds,
            reward_rate_bps: current.reward_rate_bps,
            decay_bps: current.decay_bps,
            max_decay_days: current.max_decay_days,
            faucet_enabled: current.faucet_enabled,
        };
        adjust(&mut params);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::UpdateConfig {
                admin: signer.pubkey(),
                config: self.config,
            }
            .to_account_metas(None),
            data: clockin::instruction::UpdateConfig { params }.data(),
        };
        let signer = signer.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn set_publication_authority(&mut self, new_authority: &Pubkey) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::UpdateConfig {
                admin: self.admin.pubkey(),
                config: self.config,
            }
            .to_account_metas(None),
            data: clockin::instruction::SetPublicationAuthority {
                new_authority: *new_authority,
            }
            .data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    /// Envoie une transaction signée par `signers`, le premier payant les frais.
    pub fn send(&mut self, instructions: &[Instruction], signers: &[&Keypair]) -> TransactionResult {
        let payer = signers[0].pubkey();
        let blockhash = self.svm.latest_blockhash();
        let message = Message::new_with_blockhash(instructions, Some(&payer), &blockhash);
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(message), signers).unwrap();
        let result = self.svm.send_transaction(tx);
        // Deux transactions identiques dans le même blockhash seraient rejetées
        // comme doublons : on force un nouveau blockhash après chaque envoi.
        self.svm.expire_blockhash();
        result
    }

    pub fn now(&self) -> i64 {
        self.svm.get_sysvar::<Clock>().unix_timestamp
    }

    pub fn today(&self) -> i64 {
        self.now().div_euclid(DAY)
    }

    /// Avance l'horloge de `days` jours et le slot en conséquence.
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
            &[clockin::constants::PROFILE_SEED, owner.as_ref()],
            &clockin::id(),
        )
        .0
    }

    pub fn check_in_address(&self, owner: &Pubkey, day: i64) -> Pubkey {
        Pubkey::find_program_address(
            &[clockin::constants::CHECKIN_SEED, owner.as_ref(), &day.to_le_bytes()],
            &clockin::id(),
        )
        .0
    }

    pub fn create_profile(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::CreateProfile {
                owner: user.pubkey(),
                profile: user.profile,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::CreateProfile {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn faucet(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::Faucet {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
                skr_mint: self.mint,
                owner_token_account: user.token_account,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::Faucet {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn stake(&mut self, user: &User, amount: u64) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::Stake {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
                skr_mint: self.mint,
                owner_token_account: user.token_account,
                vault: self.vault,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::Stake { amount }.data(),
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
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::CheckInAccounts {
                owner: user.pubkey(),
                publication_authority: authority.pubkey(),
                config: self.config,
                profile: user.profile,
                check_in: self.check_in_address(&user.pubkey(), day),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::CheckIn { day, commitment, blob_ref }.data(),
        };
        let owner = user.keypair.insecure_clone();
        let authority = authority.insecure_clone();
        self.send(&[instruction], &[&owner, &authority])
    }

    /// Appelé par l'admin, qui n'est ni le propriétaire ni un bénéficiaire :
    /// c'est bien un tiers qui déclenche le règlement.
    pub fn reap(&mut self, owner: &Pubkey) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::Reap {
                caller: self.admin.pubkey(),
                owner: *owner,
                config: self.config,
                profile: self.profile_address(owner),
            }
            .to_account_metas(None),
            data: clockin::instruction::Reap {}.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn request_exit(&mut self, user: &User) -> TransactionResult {
        let data = clockin::instruction::RequestExit {}.data();
        self.exit_request_instruction(user, data)
    }

    pub fn cancel_exit(&mut self, user: &User) -> TransactionResult {
        let data = clockin::instruction::CancelExit {}.data();
        self.exit_request_instruction(user, data)
    }

    fn exit_request_instruction(&mut self, user: &User, data: Vec<u8>) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::ExitRequest {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
            }
            .to_account_metas(None),
            data,
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    /// Wallet + profil + faucet : l'utilisateur type des tests suivants.
    pub fn new_user(&mut self) -> User {
        let user = self.new_wallet();
        self.create_profile(&user).unwrap();
        self.faucet(&user).unwrap();
        user
    }

    /// Crée un wallet avec des lamports et un compte de tokens SKR vide.
    /// Le profil et l'approvisionnement arrivent aux tâches 4 et 5.
    pub fn new_wallet(&mut self) -> User {
        let keypair = Keypair::new();
        self.svm.airdrop(&keypair.pubkey(), 10 * 1_000_000_000).unwrap();
        let owner = keypair.pubkey();
        let profile = self.profile_address(&owner);
        // Copies locales : `self.svm` est emprunté en mutable par le builder,
        // donc on ne peut pas lire `self.mint` ni `self.admin` au même moment.
        let admin = self.admin.insecure_clone();
        let mint = self.mint;
        let token_account = CreateAssociatedTokenAccount::new(&mut self.svm, &admin, &mint)
            .owner(&owner)
            .send()
            .unwrap();
        User { profile, keypair, token_account }
    }
}
