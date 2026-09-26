/**
 * Configure un déploiement devnet de clockin : initialize_config puis seed_pool.
 *
 * Ces deux instructions ne sont appelables ni par le CLI Solana ni par
 * `anchor run` : elles portent des arguments Borsh et des PDA.
 *
 * Séquence complète d'un déploiement, dont ce script est la partie centrale :
 *
 *   1. anchor deploy
 *   2. spl-token create-token --decimals 9        (l'admin est autorité de mint)
 *   3. spl-token mint <MINT> <montant>            (de quoi amorcer le pool)
 *   4. ce script                                   (initialize_config + seed_pool)
 *   5. spl-token authorize <MINT> mint <CONFIG_PDA>
 *
 * `seed_pool` verse l'amorçage au pool du jour UTC courant : il est partagé par
 * ceux qui publient ce jour-là, après la clôture (D+1 06:00 UTC). Si personne ne
 * publie ce jour-là, il reste dans le vault, non attribué (spec pool journalier).
 *
 * Mainnet (vrai SKR, 6 décimales, pas de faucet ni de mint à créer) :
 *
 *   npx tsx bootstrap-devnet.ts --rpc <RPC mainnet> --program <ID> \
 *     --mint SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3 --authority <clé> \
 *     --faucet-enabled false --seed 0
 *
 * Les montants (--seed, --min-stake, --faucet) sont en SKR entiers : le script
 * lit les décimales sur le mint.
 *
 * L'étape 5 est ce qui ferme la porte : après elle, plus personne ne peut créer
 * de SKR sauf le programme lui-même, par son faucet. L'ordre compte — l'admin
 * doit pouvoir mint avant de céder cette autorité, sinon il n'a rien à déposer.
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import {
  Connection,
  Keypair,
  PublicKey,
  SystemProgram,
  Transaction,
  TransactionInstruction,
} from "@solana/web3.js";

const TOKEN_PROGRAM = new PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
const ASSOCIATED_TOKEN_PROGRAM = new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");

function discriminator(name: string): Buffer {
  return createHash("sha256").update(`global:${name}`).digest().subarray(0, 8);
}

function u64(value: bigint): Buffer {
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64LE(value);
  return buffer;
}

function i64(value: bigint): Buffer {
  const buffer = Buffer.alloc(8);
  buffer.writeBigInt64LE(value);
  return buffer;
}

function u16(value: number): Buffer {
  const buffer = Buffer.alloc(2);
  buffer.writeUInt16LE(value);
  return buffer;
}

function argument(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index > 0 ? process.argv[index + 1] : undefined;
}

function required(name: string): string {
  const value = argument(name);
  if (!value) throw new Error(`argument --${name} manquant`);
  return value;
}

const programId = new PublicKey(
  argument("program") ?? "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6",
);
const mint = new PublicKey(required("mint"));
const publicationAuthority = new PublicKey(required("authority"));
const rpcUrl = argument("rpc") ?? "https://api.devnet.solana.com";
const connection = new Connection(rpcUrl, "confirmed");

// Le mint fait foi sur l'unité : 9 décimales pour le mint de test devnet, 6 pour
// le vrai SKR. Supposer 9 sur mainnet multiplierait chaque montant par 1 000.
const mintAccount = await connection.getAccountInfo(mint);
if (!mintAccount || !mintAccount.owner.equals(TOKEN_PROGRAM) || mintAccount.data.length < 82) {
  throw new Error(`${mint.toBase58()} n'est pas un mint SPL Token`);
}
if (mintAccount.data[45] !== 1) throw new Error("mint non initialisé");
const decimals = mintAccount.data[44];
const SKR = 10n ** BigInt(decimals);

/** Montant entier en SKR (argument), converti dans l'unité du mint. */
function skr(name: string, fallback: string): bigint {
  const value = argument(name) ?? fallback;
  if (!/^\d+$/.test(value)) throw new Error(`--${name} doit être un nombre entier de SKR`);
  return BigInt(value) * SKR;
}

const seedAmount = skr("seed", "500");
const minStake = skr("min-stake", "500");
const faucetAmount = skr("faucet", "1000");
const faucetFlag = argument("faucet-enabled") ?? "true";
if (faucetFlag !== "true" && faucetFlag !== "false") {
  throw new Error("--faucet-enabled vaut true ou false");
}
const faucetEnabled = faucetFlag === "true";

const admin = Keypair.fromSecretKey(
  Uint8Array.from(JSON.parse(readFileSync(`${homedir()}/.config/solana/id.json`, "utf8"))),
);

const [config] = PublicKey.findProgramAddressSync([Buffer.from("config")], programId);
const [vault] = PublicKey.findProgramAddressSync([Buffer.from("vault")], programId);
const [adminTokenAccount] = PublicKey.findProgramAddressSync(
  [admin.publicKey.toBuffer(), TOKEN_PROGRAM.toBuffer(), mint.toBuffer()],
  ASSOCIATED_TOKEN_PROGRAM,
);

/** Paramètres de travail (spec pool journalier). */
const params = Buffer.concat([
  u64(minStake), // min_stake
  u64(faucetAmount), // faucet_amount
  i64(172_800n), // withdrawal_delay_seconds : 48 h
  i64(21_600n), // pool_close_delay_seconds : pool de D clôturé à D+1 06:00 UTC
  u16(1000), // decay_bps : 10 % par jour manqué
  Buffer.from([30]), // max_decay_days
  Buffer.from([faucetEnabled ? 1 : 0]), // faucet_enabled : false sur mainnet
]);

const initializeConfig = new TransactionInstruction({
  programId,
  keys: [
    { pubkey: admin.publicKey, isSigner: true, isWritable: true },
    { pubkey: publicationAuthority, isSigner: false, isWritable: false },
    { pubkey: config, isSigner: false, isWritable: true },
    { pubkey: mint, isSigner: false, isWritable: false },
    { pubkey: vault, isSigner: false, isWritable: true },
    { pubkey: TOKEN_PROGRAM, isSigner: false, isWritable: false },
    { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
  ],
  data: Buffer.concat([discriminator("initialize_config"), params]),
});

// Jour UTC vérifié par le programme : un envoi à cheval sur minuit échoue, relancer.
const today = BigInt(Math.floor(Date.now() / 1000 / 86_400));
const [dayPool] = PublicKey.findProgramAddressSync(
  [Buffer.from("day_pool"), i64(today)],
  programId,
);

const seedPool = new TransactionInstruction({
  programId,
  keys: [
    { pubkey: admin.publicKey, isSigner: true, isWritable: true },
    { pubkey: config, isSigner: false, isWritable: false },
    { pubkey: dayPool, isSigner: false, isWritable: true },
    { pubkey: mint, isSigner: false, isWritable: false },
    { pubkey: adminTokenAccount, isSigner: false, isWritable: true },
    { pubkey: vault, isSigner: false, isWritable: true },
    { pubkey: TOKEN_PROGRAM, isSigner: false, isWritable: false },
    { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
  ],
  data: Buffer.concat([discriminator("seed_pool"), i64(today), u64(seedAmount)]),
});

console.log("programme  :", programId.toBase58());
console.log("admin      :", admin.publicKey.toBase58());
console.log("mint SKR   :", mint.toBase58(), `(${decimals} décimales)`);
console.log("min_stake  :", minStake / SKR, "SKR ; faucet :", faucetEnabled ? `${faucetAmount / SKR} SKR` : "désactivé");
console.log("config PDA :", config.toBase58());
console.log("vault PDA  :", vault.toBase58());
console.log("autorité   :", publicationAuthority.toBase58());

const existing = await connection.getAccountInfo(config);
if (existing) {
  console.log("\nConfig existe déjà : initialize_config est sauté.");
} else {
  const signature = await connection.sendTransaction(
    new Transaction().add(initializeConfig),
    [admin],
  );
  await connection.confirmTransaction(signature, "confirmed");
  console.log("\ninitialize_config :", signature);
}

if (seedAmount > 0n) {
  const signature = await connection.sendTransaction(new Transaction().add(seedPool), [admin]);
  await connection.confirmTransaction(signature, "confirmed");
  console.log(`seed_pool (${seedAmount / SKR} SKR) :`, signature);
}

console.log("\nÀ mettre dans app/local.properties :");
console.log(`clockin.skrMint=${mint.toBase58()}`);
console.log(`clockin.publicationAuthority=${publicationAuthority.toBase58()}`);
console.log("\nPuis fermer le robinet :");
console.log(`  spl-token authorize ${mint.toBase58()} mint ${config.toBase58()} --url devnet`);
