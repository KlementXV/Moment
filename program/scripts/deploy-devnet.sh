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

: "${PUBLICATION_AUTHORITY:?adresse publique de l'autorité de publication requise}"
SEED_AMOUNT="${SEED_AMOUNT:-500}"
MINT_SUPPLY="${MINT_SUPPLY:-1000}"

solana config set --url devnet >/dev/null

echo "== 1. compilation et déploiement =="
anchor build --arch v0
anchor deploy --provider.cluster devnet

PROGRAM_ID=$(solana address -k target/deploy/clockin-keypair.json)
CONFIG_PDA=$(node -e "
const {PublicKey}=require('./scripts/node_modules/@solana/web3.js');
console.log(PublicKey.findProgramAddressSync([Buffer.from('config')], new PublicKey('$PROGRAM_ID'))[0].toBase58());
")

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
