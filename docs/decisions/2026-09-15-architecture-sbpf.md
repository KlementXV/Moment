# Architecture SBPF épinglée à v0

**Date :** 2026-09-15. **Statut :** appliqué.

## Symptôme

`cargo test` échouait dès le test du scaffold, avant toute transaction :

```
thread 'test_initialize' panicked at tests/test_initialize.rs:29:40:
called `Result::unwrap()` on an `Err` value: Instruction(InvalidAccountData)
```

La ligne fautive est `svm.add_program(program_id, bytes).unwrap()` — le chargement
du programme, pas son exécution.

## Cause racine

`anchor build` 1.2.0 cible **SBPF v3 par défaut** (`--arch`, valeur par défaut `v3`).
Le binaire produit porte `e_flags = 0x3`.

litesvm 0.10.0 embarque le runtime **agave 3.1**, dont
`solana_bpf_loader_program::load_program_from_bytes` n'accepte pas les ELF v3 et
renvoie `InstructionError::InvalidAccountData` — une erreur qui ne dit rien de la
version SBPF, d'où le temps passé à la diagnostiquer.

Vérification : recompilé avec `cargo build-sbf --arch v0` (`e_flags = 0x0`), le même
test passe sans autre changement.

## Options examinées

| Option | Conclusion |
|---|---|
| Monter litesvm à 0.16 (agave 4.2.1, accepte v3) | **Rejetée.** Tire `solana-syscalls 4.2.2`, qui exige un Rust plus récent que le 1.89 épinglé par `rust-toolchain.toml`, plus six versions mineures de dérive d'API sur le harness de test. |
| Compiler les tests en v0 et déployer en v3 | **Rejetée.** Tester un binaire différent de celui déployé. |
| Épingler v0 des deux côtés | **Retenue.** |

## Décision

`anchor build --arch v0` partout : tests, déploiement devnet, documentation.
Aucune fonctionnalité du programme ne dépend de SBPF v2 ou v3 ; v0 est le format
historique, universellement supporté par les validateurs.

Le harness de test (`tests/common/mod.rs`) lit `e_flags` dans l'ELF embarqué et
échoue avec un message explicite si l'architecture n'est pas v0 — pour qu'un
`anchor build` sans drapeau produise une instruction, pas une énigme.

## À reconsidérer si

Le projet monte son toolchain Rust au-delà de 1.89 pour une autre raison : litesvm
0.16 redevient alors accessible, et l'épinglage v0 n'a plus lieu d'être.
