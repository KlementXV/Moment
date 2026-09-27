#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

: "${PUBLICATION_AUTHORITY:?cle publique de publication requise}"
SEED_AMOUNT="${SEED_AMOUNT:-500}"
MINT_SUPPLY="${MINT_SUPPLY:-1000}"

solana config set --url devnet >/dev/null

echo "== 1. compilation et deploiement =="
anchor build --arch v0
PROGRAM_ID=$(solana address -k target/deploy/moment-keypair.json)

if solana program show "$PROGRAM_ID" --url devnet >/dev/null 2>&1; then
  echo "Programme deja deploye, etape sautee."
else
  anchor deploy --provider.cluster devnet
fi

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
echo "  moment.skrMint=$MINT"
