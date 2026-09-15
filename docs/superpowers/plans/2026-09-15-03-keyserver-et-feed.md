# Keyserver, chiffrement et feed — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre les photos réellement confidentielles et le feed réellement social : l'app chiffre le paquet images + manifeste + signature, le dépose dans un bucket privé via le keyserver, le keyserver vérifie la publication et co-signe la transaction `check_in`, puis distribue les clés aux membres qui ont eux-mêmes publié le jour même.

**Architecture:** Un backend TypeScript détient trois responsabilités distinctes et assumées (§8.4) : séquestre des clés, contrôle avant publication, autorité de publication qui co-signe. Il ne détient **jamais** l'autorité du vault : sa compromission ne permet pas de retirer les fonds des utilisateurs. Le client chiffre avec une clé tirée localement ; le serveur peut déchiffrer, c'est le compromis explicite du schéma §8.2. Le stockage est un `BlobStore` derrière une interface, système de fichiers en développement, bucket S3 compatible en déploiement (décision D1).

**Tech Stack:** Node 20, TypeScript, Fastify, better-sqlite3, `@noble/ed25519`, `@noble/hashes`, vitest côté serveur ; Kotlin, `javax.crypto` AES-256-GCM côté client.

**Spec:** [`docs/superpowers/specs/2026-09-14-clock-in-design.md`](../specs/2026-09-14-clock-in-design.md) §4, §8, §11. Décisions D1, D2, D3, D4. Dépend des plans [01](2026-09-15-01-programme-anchor.md) et [02](2026-09-15-02-app-onchain.md).

## Global Constraints

- **Ne jamais journaliser** : images, clés de post, clés privées, jetons de session, corps de transaction. Les journaux contiennent des identifiants et des verdicts, rien d'autre.
- Le keyserver ne connaît aucune autorité sur le vault. Sa seule clé Solana est l'autorité de publication.
- Une panne du keyserver ne doit bloquer ni l'accès wallet, ni la recharge, ni le retrait (§8.4). Ces écrans n'appellent jamais le serveur.
- Aucune clé n'est distribuable avant confirmation on-chain du `CheckIn` correspondant (§8.2, étape 5).
- Les jetons de session sont courts (15 min) et les nonces d'authentification à usage unique : un message signé périmé ou rejoué est refusé (§11).
- Le texte visible dans une image est du contenu, jamais une instruction (§8.6).
- Modération v1 **réduite** (D3) : le serveur refuse de co-signer une publication non conforme. Aucun débit on-chain, aucun compteur de sanction, aucune interface admin.
- Le format du manifeste est celui figé par `docs/manifest-v1.md` ; le vecteur d'or du plan 02 est rejoué côté serveur.
- Toute réponse d'erreur au client est en français et dit quoi faire.

## File Structure

| Fichier | Responsabilité |
|---|---|
| `keyserver/src/config.ts` | Lecture de l'environnement, refus de démarrer si une variable manque. |
| `keyserver/src/manifest.ts` | Décodage et vérification du manifeste canonique `clockin-post-v1`. |
| `keyserver/src/crypto.ts` | Ed25519 (vérification et signature), SHA-256, AES-256-GCM. |
| `keyserver/src/packet.ts` | Décodage du paquet `moment-post-v1` (manifeste + signature + deux images). |
| `keyserver/src/solana/rpc.ts` | Lecture des comptes `Config`, `Profile`, `CheckIn`. |
| `keyserver/src/solana/transaction.ts` | Analyse d'une transaction legacy, vérification de l'instruction, insertion d'une signature. |
| `keyserver/src/store/db.ts` | Schéma SQLite : posts, clés, sessions, nonces. |
| `keyserver/src/store/blobs.ts` | `BlobStore` : `FsBlobStore` et `S3BlobStore`. |
| `keyserver/src/moderation.ts` | Politique v1 : format, taille, cohérence. Point d'extension pour un modèle. |
| `keyserver/src/routes/*.ts` | `session`, `posts`, `feed`, `blobs`. |
| `keyserver/src/server.ts` | Assemblage Fastify. |
| `keyserver/test/*.test.ts` | Tests vitest de chaque module et des parcours complets. |
| `app/.../publish/PostPacket.kt` | Construction et chiffrement du paquet côté client. |
| `app/.../publish/KeyserverClient.kt` | Client HTTP du keyserver. |
| `app/.../publish/PublishPipeline.kt` | Orchestration de la publication, idempotente et reprenable. |
| `app/.../feed/FeedRepository.kt` | Récupération et déchiffrement du feed du jour. |

Fichier supprimé en fin de plan : `app/app/src/debug/.../DevPublicationAuthority.kt`.

---

### Task 1 : Squelette du serveur et format du paquet

**Files:**
- Create: `keyserver/package.json`, `tsconfig.json`, `vitest.config.ts`, `.env.example`, `src/config.ts`, `src/crypto.ts`, `src/packet.ts`
- Test: `keyserver/test/packet.test.ts`

**Interfaces:**
- Produces : `loadConfig(): Config`, `sha256(bytes): Uint8Array`, `verifyEd25519(sig, message, publicKey): boolean`, `signEd25519(message, secret64): Uint8Array`, `decryptPacket(blob, key): PostPacket`, `type PostPacket = { manifest: Uint8Array; signature: Uint8Array; rear: Uint8Array; front: Uint8Array }`.

- [ ] **Step 1 : Créer le projet**

```bash
mkdir -p keyserver/src keyserver/test
cd keyserver
npm init -y
npm install fastify better-sqlite3 @noble/ed25519 @noble/hashes
npm install -D typescript tsx vitest @types/node @types/better-sqlite3
npx tsc --init --target es2022 --module node16 --moduleResolution node16 --strict --outDir dist --rootDir src
```

`package.json`, section `scripts` :

```json
{
  "type": "module",
  "scripts": {
    "dev": "tsx watch src/server.ts",
    "build": "tsc",
    "start": "node dist/server.js",
    "test": "vitest run"
  }
}
```

- [ ] **Step 2 : Écrire le test du paquet**

`keyserver/test/packet.test.ts` :

```ts
import { describe, expect, it } from 'vitest';
import { randomBytes, createCipheriv } from 'node:crypto';
import { decryptPacket, encodePacket, PACKET_DOMAIN } from '../src/packet.js';

function seal(plain: Uint8Array, key: Uint8Array): Uint8Array {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(PACKET_DOMAIN, 'ascii'));
  const body = Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]);
  return Buffer.concat([iv, body]);
}

describe('paquet moment-post-v1', () => {
  const manifest = new Uint8Array(175).fill(1);
  const signature = new Uint8Array(64).fill(2);
  const rear = new Uint8Array([10, 11, 12]);
  const front = new Uint8Array([20, 21]);

  it('fait un aller-retour complet', () => {
    const key = randomBytes(32);
    const packet = decryptPacket(seal(encodePacket({ manifest, signature, rear, front }), key), key);
    expect(Buffer.from(packet.manifest)).toEqual(Buffer.from(manifest));
    expect(Buffer.from(packet.signature)).toEqual(Buffer.from(signature));
    expect(Buffer.from(packet.rear)).toEqual(Buffer.from(rear));
    expect(Buffer.from(packet.front)).toEqual(Buffer.from(front));
  });

  it('refuse une mauvaise clé', () => {
    const packet = seal(encodePacket({ manifest, signature, rear, front }), randomBytes(32));
    expect(() => decryptPacket(packet, randomBytes(32))).toThrow();
  });

  it('refuse un ciphertext altéré', () => {
    const key = randomBytes(32);
    const packet = seal(encodePacket({ manifest, signature, rear, front }), key);
    packet[packet.length - 1] ^= 1;
    expect(() => decryptPacket(packet, key)).toThrow();
  });

  it('refuse un paquet plus gros que la limite', () => {
    const key = randomBytes(32);
    const huge = new Uint8Array(5 * 1024 * 1024);
    expect(() => decryptPacket(seal(encodePacket({ manifest, signature, rear: huge, front }), key), key))
      .toThrow(/trop volumineux/);
  });
});
```

- [ ] **Step 3 : Vérifier l'échec**

Run : `cd keyserver && npm test`
Expected : FAIL — module `../src/packet.js` introuvable.

- [ ] **Step 4 : Implémenter**

`keyserver/src/packet.ts` :

```ts
import { createDecipheriv } from 'node:crypto';

/** Domaine du paquet chiffré. Utilisé comme donnée authentifiée : un blob d'un
 * autre format ne peut pas être déchiffré par erreur avec une clé de post. */
export const PACKET_DOMAIN = 'moment-post-v1';
export const MAX_PHOTO_BYTES = 4 * 1024 * 1024;
export const MAX_PACKET_BYTES = 2 * MAX_PHOTO_BYTES + 4096;

export type PostPacket = {
  manifest: Uint8Array;
  signature: Uint8Array;
  rear: Uint8Array;
  front: Uint8Array;
};

/** Champs préfixés en longueur, dans l'ordre arrière puis selfie. */
export function encodePacket(packet: PostPacket): Uint8Array {
  const parts: Buffer[] = [Buffer.from(PACKET_DOMAIN, 'ascii'), Buffer.from([1])];
  for (const field of [packet.manifest, packet.signature, packet.rear, packet.front]) {
    const length = Buffer.alloc(4);
    length.writeUInt32LE(field.length);
    parts.push(length, Buffer.from(field));
  }
  return new Uint8Array(Buffer.concat(parts));
}

export function decryptPacket(blob: Uint8Array, key: Uint8Array): PostPacket {
  if (blob.length < 12 + 16) throw new Error('blob tronqué');
  if (blob.length > MAX_PACKET_BYTES + 128) throw new Error('blob trop volumineux');
  const iv = blob.subarray(0, 12);
  const tag = blob.subarray(blob.length - 16);
  const body = blob.subarray(12, blob.length - 16);
  const decipher = createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAAD(Buffer.from(PACKET_DOMAIN, 'ascii'));
  decipher.setAuthTag(tag);
  const plain = Buffer.concat([decipher.update(body), decipher.final()]);
  return decodePacket(new Uint8Array(plain));
}

export function decodePacket(plain: Uint8Array): PostPacket {
  const view = Buffer.from(plain);
  const domain = view.subarray(0, PACKET_DOMAIN.length).toString('ascii');
  if (domain !== PACKET_DOMAIN) throw new Error('domaine de paquet inattendu');
  let offset = PACKET_DOMAIN.length;
  if (view[offset++] !== 1) throw new Error('version de paquet inattendue');

  const field = (max: number): Uint8Array => {
    const length = view.readUInt32LE(offset);
    offset += 4;
    if (length > max) throw new Error('champ trop volumineux');
    if (offset + length > view.length) throw new Error('paquet tronqué');
    const value = new Uint8Array(view.subarray(offset, offset + length));
    offset += length;
    return value;
  };

  const manifest = field(1024);
  const signature = field(64);
  const rear = field(MAX_PHOTO_BYTES);
  const front = field(MAX_PHOTO_BYTES);
  if (offset !== view.length) throw new Error('octets excédentaires dans le paquet');
  if (signature.length !== 64) throw new Error('signature de taille inattendue');
  return { manifest, signature, rear, front };
}
```

`keyserver/src/crypto.ts` : réexporte `sha256` de `@noble/hashes/sha256`, et
`verifyEd25519` / `signEd25519` de `@noble/ed25519` après avoir branché SHA-512 :

```ts
import { sha512 } from '@noble/hashes/sha512';
import * as ed from '@noble/ed25519';

ed.etc.sha512Sync = (...messages) => sha512(ed.etc.concatBytes(...messages));

export { sha256 } from '@noble/hashes/sha256';

export function verifyEd25519(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array) {
  try {
    return ed.verify(signature, message, publicKey);
  } catch {
    return false;
  }
}

/** `secret` est le format des fichiers solana-keygen : graine puis clé publique. */
export function signEd25519(message: Uint8Array, secret: Uint8Array): Uint8Array {
  if (secret.length !== 64) throw new Error('clé secrète de 64 octets attendue');
  return ed.sign(message, secret.subarray(0, 32));
}
```

`keyserver/src/config.ts` : lit `PORT`, `RPC_URL`, `PROGRAM_ID`, `NETWORK`,
`PUBLICATION_AUTHORITY_KEYPAIR` (chemin du fichier JSON), `BLOB_STORE` (`fs` ou `s3`),
`BLOB_DIR`, les variables S3, et `DATABASE_PATH`. Toute variable manquante fait
échouer le démarrage avec un message explicite — jamais de valeur par défaut silencieuse
pour une clé ou une URL.

- [ ] **Step 5 : Lancer les tests**

Run : `cd keyserver && npm test`
Expected : PASS, 4 tests.

- [ ] **Step 6 : Commit**

```bash
git add keyserver/
git commit -m "feat(keyserver): squelette, format de paquet chiffre et primitives"
```

---

### Task 2 : Manifeste côté serveur et vecteur d'or partagé

**Files:**
- Create: `keyserver/src/manifest.ts`
- Test: `keyserver/test/manifest.test.ts`

**Interfaces:**
- Consumes : `sha256`, `verifyEd25519`.
- Produces : `parseManifest(bytes): Manifest`, `verifyManifest(packet, expected): void` qui lève une erreur explicite en cas d'écart, `type Manifest = { network, programId, wallet, day, nonce, rearHash, frontHash }`.

- [ ] **Step 1 : Écrire le test**

```ts
import { describe, expect, it } from 'vitest';
import { buildManifest, parseManifest, verifyManifest } from '../src/manifest.js';
import { sha256 } from '../src/crypto.js';

const programId = new Uint8Array(32).fill(1);
const wallet = new Uint8Array(32).fill(2);
const nonce = new Uint8Array(16).fill(3);
const rear = Buffer.from('arrière', 'utf8');
const front = Buffer.from('selfie', 'utf8');

describe('manifeste clockin-post-v1', () => {
  it('rejoue le vecteur d\'or de l\'app', () => {
    const bytes = buildManifest({ network: 'devnet', programId, wallet, day: 20706n, nonce, rear, front });
    expect(bytes.length).toBe(175);
    expect(Buffer.from(sha256(bytes)).toString('hex')).toBe(
      // Même constante que PostManifestTest.GOLDEN_COMMITMENT (plan 02, tâche 5).
      process.env.GOLDEN_COMMITMENT ?? 'À RENSEIGNER DEPUIS docs/manifest-v1.md',
    );
  });

  it('relit les champs qu\'il a écrits', () => {
    const parsed = parseManifest(buildManifest({ network: 'devnet', programId, wallet, day: 20706n, nonce, rear, front }));
    expect(parsed.network).toBe('devnet');
    expect(parsed.day).toBe(20706n);
    expect(Buffer.from(parsed.rearHash)).toEqual(Buffer.from(sha256(rear)));
  });

  it('refuse un manifeste dont les hashes ne correspondent pas aux images', () => {
    const bytes = buildManifest({ network: 'devnet', programId, wallet, day: 20706n, nonce, rear, front });
    expect(() =>
      verifyManifest(
        { manifest: bytes, signature: new Uint8Array(64), rear: Buffer.from('autre'), front },
        { wallet, programId, network: 'devnet', day: 20706n },
      ),
    ).toThrow(/image arrière/);
  });

  it('refuse un manifeste signé pour un autre jour ou un autre wallet', () => {
    const bytes = buildManifest({ network: 'devnet', programId, wallet, day: 20706n, nonce, rear, front });
    const packet = { manifest: bytes, signature: new Uint8Array(64), rear, front };
    expect(() => verifyManifest(packet, { wallet, programId, network: 'devnet', day: 20707n })).toThrow(/jour/);
    expect(() => verifyManifest(packet, { wallet: new Uint8Array(32).fill(9), programId, network: 'devnet', day: 20706n })).toThrow(/wallet/);
  });
});
```

- [ ] **Step 2 : Vérifier l'échec, puis implémenter**

`parseManifest` lit exactement la disposition figée par `docs/manifest-v1.md` :
domaine 15 octets, version 1 octet, longueur de réseau 1 octet, réseau, programme 32,
wallet 32, jour 8 en little-endian, nonce 16, hash arrière 32, hash selfie 32.
`buildManifest` écrit la même chose. `verifyManifest` vérifie, dans cet ordre, en
levant une erreur nommant le champ fautif : domaine, version, réseau attendu,
identifiant de programme, wallet, jour, puis `sha256(rear)` et `sha256(front)`.

- [ ] **Step 3 : Figer le vecteur d'or**

Reporter la constante de `docs/manifest-v1.md` dans le test (remplacer la lecture
d'environnement par la valeur littérale). Les deux implémentations sont alors
verrouillées l'une sur l'autre : un changement de format côté app casse ce test.

- [ ] **Step 4 : Lancer et commiter**

Run : `cd keyserver && npm test`
Expected : PASS, 4 tests.

```bash
git add keyserver/ docs/manifest-v1.md
git commit -m "feat(keyserver): manifeste canonique verifie et vecteur d'or partage"
```

---

### Task 3 : Sessions wallet

**Files:**
- Create: `keyserver/src/store/db.ts`, `keyserver/src/routes/session.ts`
- Test: `keyserver/test/session.test.ts`

**Interfaces:**
- Consumes : `verifyEd25519`, `loadConfig`.
- Produces : `openDatabase(path): Database`, `POST /v1/session/challenge`, `POST /v1/session/verify`, `requireSession(request): { wallet: Uint8Array }`.

- [ ] **Step 1 : Écrire le test**

Les cas qui comptent : un nonce inconnu, un nonce déjà utilisé, un nonce périmé, une
signature d'un autre wallet, un jeton expiré, un jeton absent.

```ts
import { describe, expect, it, beforeEach } from 'vitest';
import { buildTestServer, testKeypair, signChallenge } from './helpers.js';

describe('sessions wallet', () => {
  let app: Awaited<ReturnType<typeof buildTestServer>>;
  beforeEach(async () => { app = await buildTestServer(); });

  it('délivre un jeton court contre un nonce signé', async () => {
    const user = testKeypair();
    const challenge = await app.post('/v1/session/challenge', { wallet: user.address });
    const verified = await app.post('/v1/session/verify', {
      wallet: user.address,
      nonce: challenge.body.nonce,
      signature: signChallenge(user, challenge.body.message),
    });
    expect(verified.status).toBe(200);
    expect(verified.body.token).toMatch(/^[A-Za-z0-9_-]{32,}$/);
  });

  it('refuse un nonce rejoué', async () => {
    const user = testKeypair();
    const challenge = await app.post('/v1/session/challenge', { wallet: user.address });
    const signature = signChallenge(user, challenge.body.message);
    await app.post('/v1/session/verify', { wallet: user.address, nonce: challenge.body.nonce, signature });
    const replayed = await app.post('/v1/session/verify', { wallet: user.address, nonce: challenge.body.nonce, signature });
    expect(replayed.status).toBe(401);
  });

  it('refuse la signature d\'un autre wallet', async () => {
    const user = testKeypair();
    const other = testKeypair();
    const challenge = await app.post('/v1/session/challenge', { wallet: user.address });
    const verified = await app.post('/v1/session/verify', {
      wallet: user.address,
      nonce: challenge.body.nonce,
      signature: signChallenge(other, challenge.body.message),
    });
    expect(verified.status).toBe(401);
  });

  it('refuse un nonce périmé', async () => {
    const user = testKeypair();
    const challenge = await app.post('/v1/session/challenge', { wallet: user.address });
    app.advanceClock(6 * 60_000);
    const verified = await app.post('/v1/session/verify', {
      wallet: user.address,
      nonce: challenge.body.nonce,
      signature: signChallenge(user, challenge.body.message),
    });
    expect(verified.status).toBe(401);
  });

  it('refuse un jeton expiré sur une route protégée', async () => {
    const user = await app.authenticate();
    app.advanceClock(16 * 60_000);
    const feed = await app.get('/v1/feed?day=20706', user.token);
    expect(feed.status).toBe(401);
  });
});
```

`keyserver/test/helpers.js` fournit `buildTestServer` (Fastify en mémoire, base SQLite
`:memory:`, `BlobStore` en mémoire, horloge injectable), `testKeypair`, `signChallenge`
et `authenticate`. L'horloge est injectée comme dépendance (`() => number`) pour que
les tests d'expiration ne dépendent pas d'attentes réelles.

- [ ] **Step 2 : Implémenter**

Schéma SQLite :

```sql
CREATE TABLE IF NOT EXISTS nonces (
  nonce TEXT PRIMARY KEY, wallet TEXT NOT NULL, message TEXT NOT NULL,
  expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY, wallet TEXT NOT NULL, expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS posts (
  commitment TEXT PRIMARY KEY, wallet TEXT NOT NULL, day INTEGER NOT NULL,
  blob_ref TEXT NOT NULL, post_key BLOB NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending_review','authorized','published','refused')),
  reason TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS posts_wallet_day ON posts (wallet, day);
```

Le message du challenge est lisible par l'utilisateur dans son wallet et lie le
domaine, le réseau, le wallet, le nonce et l'expiration :

```
Moment — connexion au serveur de clés
réseau: devnet
wallet: <base58>
nonce: <base64url>
expire: <ISO 8601>
```

`POST /v1/session/verify` marque le nonce `used` **dans la même transaction SQLite**
que la création du jeton : un rejeu concurrent ne peut pas créer deux sessions.

- [ ] **Step 3 : Lancer et commiter**

Run : `cd keyserver && npm test`
Expected : PASS, 5 tests de session.

```bash
git add keyserver/
git commit -m "feat(keyserver): sessions wallet avec nonces a usage unique"
```

---

### Task 4 : Vérification et co-signature de la transaction

Le cœur sécurité du backend. Le serveur signe une transaction qu'il n'a pas construite :
il doit donc prouver, avant de signer, qu'elle ne fait exactement que ce qu'il croit.

**Files:**
- Create: `keyserver/src/solana/transaction.ts`
- Test: `keyserver/test/transaction.test.ts`

**Interfaces:**
- Consumes : `signEd25519`, `loadConfig`.
- Produces : `parseTransaction(bytes): ParsedTransaction`, `assertIsExpectedCheckIn(parsed, expectation): void`, `coSign(bytes, secret): Uint8Array`.

- [ ] **Step 1 : Écrire le test**

```ts
import { describe, expect, it } from 'vitest';
import { assertIsExpectedCheckIn, coSign, parseTransaction } from '../src/solana/transaction.js';
import { buildCheckInTransaction, authority, owner, programId } from './fixtures.js';

const expectation = {
  programId,
  owner: owner.publicKey,
  publicationAuthority: authority.publicKey,
  day: 20706n,
  commitment: new Uint8Array(32).fill(7),
  blobRef: new Uint8Array(32).fill(9),
};

describe('co-signature de check_in', () => {
  it('accepte la transaction attendue', () => {
    const parsed = parseTransaction(buildCheckInTransaction(expectation));
    expect(() => assertIsExpectedCheckIn(parsed, expectation)).not.toThrow();
  });

  it('refuse une transaction qui contient une instruction supplémentaire', () => {
    const parsed = parseTransaction(buildCheckInTransaction(expectation, { extraTransfer: true }));
    expect(() => assertIsExpectedCheckIn(parsed, expectation)).toThrow(/une seule instruction/);
  });

  it('refuse un autre programme', () => {
    const parsed = parseTransaction(buildCheckInTransaction({ ...expectation, programId: new Uint8Array(32).fill(4) }));
    expect(() => assertIsExpectedCheckIn(parsed, expectation)).toThrow(/programme/);
  });

  it('refuse un commitment ou un blob_ref différent de celui déposé', () => {
    const parsed = parseTransaction(buildCheckInTransaction({ ...expectation, commitment: new Uint8Array(32).fill(8) }));
    expect(() => assertIsExpectedCheckIn(parsed, expectation)).toThrow(/commitment/);
  });

  it('refuse un jour différent du jour de la soumission', () => {
    const parsed = parseTransaction(buildCheckInTransaction({ ...expectation, day: 20707n }));
    expect(() => assertIsExpectedCheckIn(parsed, expectation)).toThrow(/jour/);
  });

  it('refuse un propriétaire différent du wallet de la session', () => {
    const parsed = parseTransaction(buildCheckInTransaction({ ...expectation, owner: new Uint8Array(32).fill(5) }));
    expect(() => assertIsExpectedCheckIn(parsed, expectation)).toThrow(/wallet/);
  });

  it('place la signature à l\'index de l\'autorité et laisse les autres intactes', () => {
    const raw = buildCheckInTransaction(expectation);
    const signed = coSign(raw, authority.secretKey);
    const parsed = parseTransaction(signed);
    const index = parsed.accounts.findIndex((account) => Buffer.from(account).equals(Buffer.from(authority.publicKey)));
    expect(parsed.signatures[index].some((byte) => byte !== 0)).toBe(true);
    const ownerIndex = parsed.accounts.findIndex((account) => Buffer.from(account).equals(Buffer.from(owner.publicKey)));
    expect(parsed.signatures[ownerIndex].every((byte) => byte === 0)).toBe(true);
  });
});
```

`keyserver/test/fixtures.ts` construit une transaction legacy à la main : en-tête,
comptes, blockhash, instruction. C'est vingt lignes et cela évite d'embarquer un SDK
Solana complet dans le serveur pour un seul format.

- [ ] **Step 2 : Implémenter**

`parseTransaction` lit le format legacy : compact-u16 du nombre de signatures, les
signatures de 64 octets, puis l'en-tête (`numRequiredSignatures`,
`numReadonlySigned`, `numReadonlyUnsigned`), la liste compacte des comptes de 32 octets,
le blockhash, puis les instructions (`programIdIndex`, indices de comptes, données).

`assertIsExpectedCheckIn` vérifie dans cet ordre, chaque échec nommant sa cause :

1. exactement une instruction — sinon « une seule instruction est autorisée » ;
2. le programme visé est `PROGRAM_ID` ;
3. les 8 premiers octets des données sont le discriminant `check_in` ;
4. `day` (8 octets little-endian) est le jour UTC courant **et** celui de la soumission ;
5. `commitment` et `blob_ref` sont exactement ceux déposés ;
6. le compte `owner` est le wallet de la session et il est signataire ;
7. le compte autorité est notre clé publique et il est signataire ;
8. tous les autres comptes sont ceux attendus (PDA `Config`, `Profile`, `CheckIn`, programme système).

`coSign` insère `signEd25519(messageBytes, secret)` à l'index de l'autorité — jamais
ailleurs — et laisse les autres emplacements inchangés.

- [ ] **Step 3 : Lancer et commiter**

Run : `cd keyserver && npm test`
Expected : PASS, 7 tests.

```bash
git add keyserver/
git commit -m "feat(keyserver): verification stricte et co-signature de check_in"
```

---

### Task 5 : Dépôt, contrôle et autorisation de publication

**Files:**
- Create: `keyserver/src/store/blobs.ts`, `keyserver/src/moderation.ts`, `keyserver/src/solana/rpc.ts`, `keyserver/src/routes/posts.ts`
- Test: `keyserver/test/posts.test.ts`

**Interfaces:**
- Consumes : tout ce qui précède.
- Produces : `POST /v1/posts` → `{ transaction: base64 }`, `POST /v1/posts/:commitment/confirm` → `{ state: 'published' }`, `BlobStore { put(ref, bytes), get(ref), has(ref) }`, `reviewPhotos(packet): { ok: true } | { ok: false, reason: string }`.

- [ ] **Step 1 : Écrire le test du parcours**

```ts
describe('dépôt d\'un post', () => {
  it('stocke le blob, séquestre la clé et rend une transaction co-signée', async () => {
    const app = await buildTestServer();
    const user = await app.authenticate();
    const post = await app.submitPost(user, { day: 20706n });

    expect(post.status).toBe(200);
    expect(post.body.transaction).toBeTypeOf('string');
    expect(app.db.postState(post.commitment)).toBe('authorized');
    expect(await app.blobs.has(post.blobRef)).toBe(true);
  });

  it('refuse un blob dont le hash ne vaut pas le blob_ref annoncé', async () => {
    const app = await buildTestServer();
    const user = await app.authenticate();
    const post = await app.submitPost(user, { corruptBlobRef: true });
    expect(post.status).toBe(400);
    expect(post.body.error).toMatch(/référence du blob/);
  });

  it('refuse une signature de manifeste qui n\'est pas celle du wallet', async () => {
    const app = await buildTestServer();
    const user = await app.authenticate();
    const post = await app.submitPost(user, { signWithAnotherWallet: true });
    expect(post.status).toBe(400);
    expect(post.body.error).toMatch(/signature/);
  });

  it('refuse un second post le même jour', async () => {
    const app = await buildTestServer();
    const user = await app.authenticate();
    await app.submitPost(user, { day: 20706n });
    const again = await app.submitPost(user, { day: 20706n });
    expect(again.status).toBe(409);
  });

  it('ne distribue rien tant que la confirmation on-chain n\'est pas faite', async () => {
    const app = await buildTestServer();
    const user = await app.authenticate();
    const post = await app.submitPost(user, { day: 20706n });
    expect(app.db.postState(post.commitment)).toBe('authorized');

    app.chain.setCheckIn(user.wallet, 20706n, { commitment: post.commitment, blobRef: post.blobRef });
    await app.post(`/v1/posts/${post.commitment}/confirm`, {}, user.token);
    expect(app.db.postState(post.commitment)).toBe('published');
  });

  it('refuse de confirmer si le compte on-chain porte un autre commitment', async () => {
    const app = await buildTestServer();
    const user = await app.authenticate();
    const post = await app.submitPost(user, { day: 20706n });
    app.chain.setCheckIn(user.wallet, 20706n, { commitment: 'ff'.repeat(32), blobRef: post.blobRef });
    const confirmed = await app.post(`/v1/posts/${post.commitment}/confirm`, {}, user.token);
    expect(confirmed.status).toBe(409);
    expect(app.db.postState(post.commitment)).toBe('authorized');
  });

  it('refuse une publication dont le contrôle échoue, sans co-signer', async () => {
    const app = await buildTestServer({ moderation: () => ({ ok: false, reason: 'contenu refusé' }) });
    const user = await app.authenticate();
    const post = await app.submitPost(user, { day: 20706n });
    expect(post.status).toBe(422);
    expect(post.body.transaction).toBeUndefined();
    expect(app.db.postState(post.commitment)).toBe('refused');
  });
});
```

- [ ] **Step 2 : Implémenter la route `POST /v1/posts`**

Corps attendu (JSON, valeurs binaires en base64) : `day`, `commitment`, `blobRef`,
`postKey`, `blob`, `transaction`. Séquence, dans cet ordre, en refusant au premier écart :

1. session valide, wallet extrait du jeton ;
2. `sha256(blob) === blobRef` — sinon « la référence du blob ne correspond pas » ;
3. taille du blob sous la limite ;
4. `decryptPacket(blob, postKey)` réussit ;
5. `verifyManifest(packet, { wallet, programId, network, day })` ;
6. `sha256(packet.manifest) === commitment` ;
7. `verifyEd25519(packet.signature, packet.manifest, wallet)` ;
8. `reviewPhotos(packet)` ;
9. insertion du post en `pending_review` (échec 409 si un post existe déjà pour ce
   couple wallet × jour) et écriture du blob ;
10. `assertIsExpectedCheckIn(parseTransaction(transaction), …)` puis `coSign` ;
11. passage à `authorized`, réponse `{ transaction }`.

Les étapes 9 à 11 sont **idempotentes** : renvoyer la même soumission (même
`commitment`, même `blobRef`) renvoie la même transaction co-signée plutôt qu'une
erreur, pour qu'une coupure réseau côté client soit reprenable.

`reviewPhotos` en v1 (D3) : les deux images sont des JPEG de taille bornée, sans
segment APP/COM autre que JFIF, et de dimensions plausibles. La signature de la
fonction est celle qu'un modèle utilisera plus tard ; le point d'extension est prêt,
le modèle ne l'est pas (plan 04).

- [ ] **Step 3 : Implémenter `POST /v1/posts/:commitment/confirm`**

Lit le compte `CheckIn` du couple wallet × jour par RPC, vérifie que `commitment` et
`blob_ref` correspondent exactement, puis passe l'état à `published`. Sinon 409, et
l'état ne bouge pas. C'est ce qui garantit qu'aucune clé n'est distribuable avant
l'inscription on-chain (§8.2).

- [ ] **Step 4 : Implémenter `BlobStore`**

`FsBlobStore` écrit `BLOB_DIR/<blobRef>.bin` de façon atomique (écriture dans un
fichier temporaire puis `rename`). `S3BlobStore` utilise l'API S3 compatible du
fournisseur. Les deux refusent d'écraser un blob existant : une référence est un hash,
donc deux contenus différents ne peuvent pas la partager.

- [ ] **Step 5 : Lancer et commiter**

Run : `cd keyserver && npm test`
Expected : PASS, 7 tests de dépôt.

```bash
git add keyserver/
git commit -m "feat(keyserver): depot, controle et autorisation de publication"
```

---

### Task 6 : Distribution des clés et feed

**Files:**
- Create: `keyserver/src/routes/feed.ts`, `keyserver/src/routes/blobs.ts`
- Test: `keyserver/test/feed.test.ts`

**Interfaces:**
- Consumes : `requireSession`, `BlobStore`, RPC.
- Produces : `GET /v1/feed?day=` → `[{ wallet, commitment, blobRef, postKey, blobUrl }]`, `GET /v1/blobs/:ref`.

- [ ] **Step 1 : Écrire le test**

Les règles de §8.2 à la lecture, une par test :

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { buildTestServer } from './helpers.js';

describe('feed', () => {
  let app: Awaited<ReturnType<typeof buildTestServer>>;
  const day = 20706n;

  beforeEach(async () => { app = await buildTestServer(); });

  it('donne les clés du jour à un membre qui a publié', async () => {
    const alice = await app.authenticate();
    const bob = await app.authenticate();
    const alicePost = await app.publishFully(alice, { day });
    await app.publishFully(bob, { day });

    const feed = await app.get(`/v1/feed?day=${day}`, bob.token);
    expect(feed.status).toBe(200);
    const mine = feed.body.find((post) => post.commitment === alicePost.commitment);
    expect(mine.postKey).toBeTypeOf('string');
    expect(mine.blobRef).toBe(alicePost.blobRef);
  });

  it('refuse tout à un membre qui n\'a pas publié aujourd\'hui', async () => {
    const alice = await app.authenticate();
    const lurker = await app.authenticate();
    await app.publishFully(alice, { day });
    app.chain.setProfile(lurker.wallet, { active: true, staked: 50_000_000_000n, settledDay: day - 1n });

    const feed = await app.get(`/v1/feed?day=${day}`, lurker.token);
    expect(feed.status).toBe(403);
    expect(feed.body.error).toMatch(/publie/);
  });

  it('refuse un membre dont le solde effectif est sous le minimum', async () => {
    const poor = await app.authenticate();
    await app.publishFully(poor, { day });
    // Profil en retard de trois jours : 11 SKR tombent sous les 10 SKR requis.
    app.chain.setProfile(poor.wallet, { active: true, staked: 11_000_000_000n, settledDay: day - 4n });

    const feed = await app.get(`/v1/feed?day=${day}`, poor.token);
    expect(feed.status).toBe(403);
  });

  it('ne renvoie que les posts publiés, jamais ceux en attente ou refusés', async () => {
    const alice = await app.authenticate();
    const bob = await app.authenticate();
    const pending = await app.submitPost(alice, { day });   // autorisé mais non confirmé
    await app.publishFully(bob, { day });

    const feed = await app.get(`/v1/feed?day=${day}`, bob.token);
    expect(feed.body.map((post) => post.commitment)).not.toContain(pending.commitment);
  });

  it('refuse un blob sans jeton de session', async () => {
    const alice = await app.authenticate();
    const post = await app.publishFully(alice, { day });
    expect((await app.get(`/v1/blobs/${post.blobRef}`)).status).toBe(401);
  });

  it('refuse un blob à un membre qui n\'a pas publié', async () => {
    const alice = await app.authenticate();
    const lurker = await app.authenticate();
    const post = await app.publishFully(alice, { day });
    expect((await app.get(`/v1/blobs/${post.blobRef}`, lurker.token)).status).toBe(403);
  });
});
```

Le harnais de test fournit `publishFully` (dépôt puis confirmation, avec un `CheckIn`
simulé côté chaîne) et `app.chain.setProfile` / `setCheckIn` pour piloter l'état
on-chain que le serveur lit.

Le solde effectif est calculé comme dans le programme : `Profile.staked` après le decay
dû jusqu'à la borne du jour. Le test le vérifie avec un profil volontairement en retard.

- [ ] **Step 2 : Implémenter**

`GET /v1/feed` : session valide → lecture du `Profile` et du `CheckIn` du jour du
demandeur → position active, solde effectif ≥ `min_stake`, `CheckIn` du jour présent →
liste des posts `published` du jour, avec leur clé et l'URL de leur blob. Une clé déjà
délivrée n'est jamais reprise (§8.2) : c'est une propriété du monde, pas du code, et
elle est écrite telle quelle dans la documentation.

`GET /v1/blobs/:ref` applique exactement les mêmes contrôles avant de servir les
octets chiffrés. Un blob servi sans sa clé n'apprend rien : c'est ce que la démo
montre par le négatif (§14).

- [ ] **Step 3 : Lancer et commiter**

Run : `cd keyserver && npm test`
Expected : PASS, 6 tests de feed.

```bash
git add keyserver/
git commit -m "feat(keyserver): distribution des cles du feed apres check-in"
```

---

### Task 7 : Chiffrement du paquet côté client

**Files:**
- Create: `app/.../publish/PostPacket.kt`
- Test: `app/app/src/test/java/com/clockin/hackathon/publish/PostPacketTest.kt`

**Interfaces:**
- Consumes : `PostManifest`, `PhotoPair`.
- Produces : `PostPacket.encode(manifest, signature, photos): ByteArray`, `PostPacket.seal(plain, key): ByteArray`, `PostPacket.newKey(): ByteArray`, `PostPacket.blobRef(blob): ByteArray`.

- [ ] **Step 1 : Écrire le test**

Miroir exact des tests serveur : aller-retour, mauvaise clé, ciphertext altéré,
taille bornée — plus un test de compatibilité qui chiffre un paquet et vérifie que
les octets produits sont **identiques en structure** à ceux qu'attend le serveur
(domaine, version, quatre champs préfixés en longueur, ordre arrière puis selfie).

- [ ] **Step 2 : Implémenter**

AES-256-GCM, nonce 96 bits tiré par `SecureRandom`, donnée authentifiée
`"moment-post-v1"`, sortie `nonce || ciphertext || tag`. La clé est tirée par
`KeyGenerator.getInstance("AES")` à 256 bits — **pas** par le Keystore Android : cette
clé doit pouvoir être transmise au serveur et, éventuellement, révélée volontairement
par son auteur (§8.3).

- [ ] **Step 3 : Lancer et commiter**

Run : `cd app && ./gradlew :app:testDebugUnitTest --tests '*PostPacketTest'`

```bash
git add app/
git commit -m "feat(app): paquet de publication chiffre AES-256-GCM"
```

---

### Task 8 : Pipeline de publication et retrait de la béquille

**Files:**
- Create: `app/.../publish/KeyserverClient.kt`, `app/.../publish/PublishPipeline.kt`
- Delete: `app/app/src/debug/java/com/clockin/hackathon/wallet/DevPublicationAuthority.kt`
- Modify: `app/.../ClockInModel.kt`, `app/app/build.gradle.kts`, `app/.../capture/LocalDraft.kt`

**Interfaces:**
- Consumes : `PostPacket`, `PostManifest`, `WalletSession`, `SolanaRpc`, `TransactionBuilder`.
- Produces : `KeyserverClient` (`authenticate`, `submitPost`, `confirmPost`, `feed`, `blob`), `PublishPipeline.publish(photos): Result<String>` (signature de transaction), `LocalDraft` enrichi de l'état de reprise.

- [ ] **Step 1 : Décrire la séquence dans le code**

```
1. manifeste ← PostManifest.of(devnet, programId, wallet, jour, arrière, selfie)
2. signature ← wallet.signManifest(manifeste)            [MWA]
3. paquet    ← PostPacket.encode(manifeste, signature, photos)
4. clé       ← PostPacket.newKey() ; blob ← seal(paquet, clé) ; blobRef ← sha256(blob)
5. transaction non signée ← check_in(jour, commitment, blobRef) + blockhash
6. POST /v1/posts {jour, commitment, blobRef, clé, blob, transaction}
      → transaction co-signée
7. wallet.signTransaction(transaction co-signée)          [MWA]
8. rpc.sendTransaction + awaitConfirmation
9. POST /v1/posts/{commitment}/confirm
10. brouillon local effacé
```

L'état de reprise est enregistré dans `LocalDraft` après les étapes 4, 6 et 8 : une
coupure à n'importe quel point reprend à l'étape suivante sans redemander de capture,
et les étapes 6 et 9 sont idempotentes côté serveur.

- [ ] **Step 2 : Écrire les tests**

Client HTTP testé avec un serveur factice injecté (même approche que `FakeDriver` du
plan 02) : réponse 200, 409 (post déjà déposé → reprendre avec la même transaction),
422 (refus de publication → message utilisateur, brouillon conservé), 401 (session
expirée → ré-authentification transparente puis une seule nouvelle tentative).

- [ ] **Step 3 : Supprimer l'autorité de développement**

```bash
rm app/app/src/debug/java/com/clockin/hackathon/wallet/DevPublicationAuthority.kt
grep -r "DEV_AUTHORITY_SECRET\|DevPublicationAuthority" app/ || echo "béquille retirée"
```

Retirer aussi `clockin.devAuthoritySecret` de `local.properties` et le champ
`buildConfigField` correspondant. À partir d'ici, **seul le keyserver** peut autoriser
une publication : c'est exactement la propriété annoncée au §8.6.

- [ ] **Step 4 : Lancer et commiter**

Run : `cd app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

```bash
git add app/
git commit -m "feat(app): pipeline de publication via le keyserver, bequille retiree"
```

---

### Task 9 : Feed déchiffré

**Files:**
- Create: `app/.../feed/FeedRepository.kt`
- Modify: `app/.../ui/MomentApp.kt`, `app/.../ClockInModel.kt`

**Interfaces:**
- Consumes : `KeyserverClient`, `PostPacket`, `PostManifest`.
- Produces : `data class FeedPost(wallet: String, rear: ByteArray, front: ByteArray, streak: Long, verified: Boolean)`, `FeedRepository.today(): List<FeedPost>`.

- [ ] **Step 1 : Écrire les tests**

- Un blob dont le hash ne vaut pas `blobRef` est **rejeté**, pas affiché.
- Un paquet dont les hashes d'images ne correspondent pas au manifeste est rejeté.
- Un manifeste dont la signature ne vérifie pas contre le wallet annoncé est marqué
  `verified = false` et affiché comme non vérifié plutôt que masqué silencieusement.
- Le feed est vide et verrouillé tant que `todayCheckIn == null`.

Le client refait donc **toute** la vérification que le serveur a faite. C'est ce qui
permet de dire, en démo, que la confiance au serveur porte sur la confidentialité et
la modération, pas sur l'authenticité du contenu affiché.

- [ ] **Step 2 : Implémenter et brancher l'écran**

Le feed affiche les deux images par post (selfie en médaillon, comme aujourd'hui),
l'adresse abrégée, le streak au moment du check-in, et une pastille « vérifié » liée
au résultat de la vérification locale. Les images restent en mémoire et dans le cache
chiffré local ; aucune écriture en clair sur le disque.

- [ ] **Step 3 : Lancer et commiter**

```bash
git add app/
git commit -m "feat(app): feed du jour dechiffre et verifie localement"
```

---

### Task 10 : Déploiement et parcours à deux wallets

**Files:**
- Create: `keyserver/README.md`, `keyserver/Dockerfile`
- Modify: `README.md`, `docs/devnet-run.md`

- [ ] **Step 1 : Déployer le keyserver**

Hébergement : n'importe quel service Node avec un disque persistant et HTTPS. Variables
d'environnement selon `.env.example`. La clé de l'autorité de publication est fournie
par un secret du fournisseur, jamais par le dépôt.

Vérifier après déploiement : `GET /health` répond ; `POST /v1/posts` sans session
répond 401 ; `GET /v1/blobs/<ref>` sans session répond 401.

- [ ] **Step 2 : Mettre l'autorité de publication à jour on-chain**

L'adresse de l'autorité change entre la clé de développement du plan 02 et celle du
serveur. L'instruction admin `set_publication_authority` existe pour ça (plan 01,
tâche 3) : l'appeler avec la clé publique du keyserver, puis vérifier que
`Config.publication_authority` a bien changé avant de tester une publication.

Après cette rotation, un build debug portant encore l'ancienne clé échoue à publier :
c'est le comportement attendu, et la preuve que la béquille est bien morte.

- [ ] **Step 3 : Jouer le parcours à deux wallets**

Deux appareils (ou un appareil et un émulateur), deux wallets distincts, le même jour :

1. Chacun mise puis publie.
2. Avant son propre check-in, chacun voit le feed verrouillé.
3. Après son check-in, chacun voit le post de l'autre, déchiffré.
4. Télécharger le blob brut avec `curl` et un jeton de session, constater qu'il est
   illisible sans la clé — la démonstration par le négatif de §14.

- [ ] **Step 4 : Documenter et commiter**

```bash
git add keyserver/ README.md docs/
git commit -m "docs: deploiement du keyserver et parcours a deux wallets"
```

---

## Self-Review

**Couverture :** §8.1 problème → tâches 5 et 6 ; §8.2 schéma retenu → tâches 5, 7 et 8 ;
§8.3 divulgation sélective → plan 04 (écran de vérification), la clé est déjà détenue
par le client depuis la tâche 7 ; §8.4 modèle de menace → contraintes globales et
tâche 6 ; §8.5 implémentation → tâches 1 à 6 ; §8.6 contrôle → tâche 5, version réduite
assumée (D3) ; §11 tests serveur → tâches 3, 5 et 6.

**Écarts assumés :** aucune interface admin, aucun compteur de refus, aucune sanction
financière (D3). Le classifieur embarqué est un point d'extension prêt mais vide : il
relève du plan 04 et peut être abandonné sans casser ce plan.
