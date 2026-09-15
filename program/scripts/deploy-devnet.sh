#!/usr/bin/env bash
# Déploie clockin sur devnet. L'architecture SBPF est épinglée à v0 : voir
# docs/decisions/2026-09-15-architecture-sbpf.md.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${PUBLICATION_AUTHORITY:?adresse publique de l'autorité de publication requise}"

solana config set --url devnet
anchor build --arch v0
anchor deploy --provider.cluster devnet

echo
echo "Programme : $(solana address -k target/deploy/clockin-keypair.json)"
echo "Autorité de publication : ${PUBLICATION_AUTHORITY}"
echo "IDL : target/idl/clockin.json"
echo
echo "Étapes restantes, une seule fois :"
echo "  1. spl-token create-token --decimals 9 --mint-authority <PDA Config> --url devnet"
echo "  2. initialize_config avec les paramètres de la feuille de route"
echo "  3. seed_pool pour amorcer le pool"
