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
const SKR = 1_000_000_000n;

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
  argument("program") ?? "7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1",
);
const mint = new PublicKey(required("mint"));
const publicationAuthority = new PublicKey(required("authority"));
const seedAmount = BigInt(argument("seed") ?? "500") * SKR;
const rpcUrl = argument("rpc") ?? "https://api.devnet.solana.com";

const admin = Keypair.fromSecretKey(
  Uint8Array.from(JSON.parse(readFileSync(`${homedir()}/.config/solana/id.json`, "utf8"))),
);

const [config] = PublicKey.findProgramAddressSync([Buffer.from("config")], programId);
const [vault] = PublicKey.findProgramAddressSync([Buffer.from("vault")], programId);
const [adminTokenAccount] = PublicKey.findProgramAddressSync(
  [admin.publicKey.toBuffer(), TOKEN_PROGRAM.toBuffer(), mint.toBuffer()],
  ASSOCIATED_TOKEN_PROGRAM,
);

/** Paramètres de travail de la feuille de route (§6 de la spec). */
const params = Buffer.concat([
  u64(500n * SKR), // min_stake
  u64(1n * SKR), // reward_cap
  u64(1000n * SKR), // faucet_amount
  i64(172_800n), // withdrawal_delay_seconds : 48 h
  u16(100), // reward_rate_bps : 1 %/jour
  u16(2500), // decay_bps : 25 % par jour manqué
  Buffer.from([30]), // max_decay_days
  Buffer.from([1]), // faucet_enabled
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

const seedPool = new TransactionInstruction({
  programId,
  keys: [
    { pubkey: admin.publicKey, isSigner: true, isWritable: true },
    { pubkey: config, isSigner: false, isWritable: true },
    { pubkey: mint, isSigner: false, isWritable: false },
    { pubkey: adminTokenAccount, isSigner: false, isWritable: true },
    { pubkey: vault, isSigner: false, isWritable: true },
    { pubkey: TOKEN_PROGRAM, isSigner: false, isWritable: false },
  ],
  data: Buffer.concat([discriminator("seed_pool"), u64(seedAmount)]),
});

const connection = new Connection(rpcUrl, "confirmed");

console.log("programme  :", programId.toBase58());
console.log("admin      :", admin.publicKey.toBase58());
console.log("mint SKR   :", mint.toBase58());
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
