#!/usr/bin/env bash
# Déploiement devnet complet de clockin.
#
# L'architecture SBPF est épinglée à v0 : voir
# docs/decisions/2026-09-15-architecture-sbpf.md.
#
# L'ordre des étapes 2 à 5 n'est pas cosmétique. L'admin doit pouvoir créer du
# SKR pour amorcer le pool, puis céder cette autorité au PDA Config. Une fois
# cédée, plus personne ne peut créer de SKR hors du faucet du programme.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${PUBLICATION_AUTHORITY:?cle publique de publication requise}"
SEED_AMOUNT="${SEED_AMOUNT:-500}"
MINT_SUPPLY="${MINT_SUPPLY:-1000}"

solana config set --url devnet >/dev/null

echo "== 1. compilation et deploiement =="
anchor build --arch v0
PROGRAM_ID=$(solana address -k target/deploy/clockin-keypair.json)

# Redeployer coute la rente une seconde fois : on ne le fait que si besoin.
if solana program show "$PROGRAM_ID" --url devnet >/dev/null 2>&1; then
  echo "Programme deja deploye, etape sautee."
else
  anchor deploy --provider.cluster devnet
fi

# Derivation par le CLI Solana : @solana/web3.js v1 ne se charge pas en
# CommonJS sous Node 20 (rpc-websockets tire uuid en ESM).
CONFIG_PDA=$(solana find-program-derived-address "$PROGRAM_ID" string:config)

echo "== 2. création du mint SKR de test (autorité : l'admin, provisoirement) =="
MINT=$(spl-token create-token --decimals 9 --url devnet | awk '/Address:/ {print $2}')
spl-token create-account "$MINT" --url devnet >/dev/null

echo "== 3. approvisionnement de l'admin pour amorcer le pool =="
spl-token mint "$MINT" "$MINT_SUPPLY" --url devnet >/dev/null

echo "== 4. initialize_config et seed_pool =="
(cd scripts && npx tsx bootstrap-devnet.ts \
  --program "$PROGRAM_ID" --mint "$MINT" \
  --authority "$PUBLICATION_AUTHORITY" --seed "$SEED_AMOUNT")

echo "== 5. l'autorité de mint passe au programme =="
spl-token authorize "$MINT" mint "$CONFIG_PDA" --url devnet >/dev/null
echo "Seul le faucet du programme peut désormais créer du SKR."

echo
echo "Programme : $PROGRAM_ID"
echo "Mint SKR  : $MINT"
echo "Config    : $CONFIG_PDA"
echo
echo "Dans app/local.properties :"
echo "  clockin.skrMint=$MINT"
