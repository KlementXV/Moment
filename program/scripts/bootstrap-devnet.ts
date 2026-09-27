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

const mintAccount = await connection.getAccountInfo(mint);
if (!mintAccount || !mintAccount.owner.equals(TOKEN_PROGRAM) || mintAccount.data.length < 82) {
  throw new Error(`${mint.toBase58()} n'est pas un mint SPL Token`);
}
if (mintAccount.data[45] !== 1) throw new Error("mint non initialisé");
const decimals = mintAccount.data[44];
const SKR = 10n ** BigInt(decimals);

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

const params = Buffer.concat([
  u64(minStake),
  u64(faucetAmount),
  i64(172_800n),
  i64(21_600n),
  u16(1000),
  Buffer.from([30]),
  Buffer.from([faucetEnabled ? 1 : 0]),
]);

const [programData] = PublicKey.findProgramAddressSync(
  [programId.toBuffer()], new PublicKey("BPFLoaderUpgradeab1e11111111111111111111111"),
);

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
    { pubkey: programData, isSigner: false, isWritable: false },
  ],
  data: Buffer.concat([discriminator("initialize_config"), params]),
});

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
console.log(`moment.skrMint=${mint.toBase58()}`);

console.log("\nPuis fermer le robinet :");
console.log(`  spl-token authorize ${mint.toBase58()} mint ${config.toBase58()} --url devnet`);
