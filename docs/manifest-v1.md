# Manifeste canonique `clockin-post-v1`

Format figé. Toute modification exige une **version 2** : ce format est partagé
entre l'app Android et le keyserver, et verrouillé par un vecteur d'or rejoué
des deux côtés.

## Disposition

Chaque champ est de taille fixe ou préfixé en longueur. Aucune concaténation
ambiguë n'est possible : deux jeux de valeurs différents ne peuvent pas produire
les mêmes octets.

| Offset | Taille | Champ | Encodage |
|---:|---:|---|---|
| 0 | 15 | domaine | ASCII `clockin-post-v1` |
| 15 | 1 | version | `u8`, vaut 1 |
| 16 | 1 | longueur du réseau | `u8` |
| 17 | n | réseau | ASCII imprimable (`devnet`, `mainnet`) |
| 17+n | 32 | identifiant du programme | octets bruts |
| 49+n | 32 | wallet du publieur | octets bruts |
| 81+n | 8 | jour UTC | `i64` little-endian |
| 89+n | 16 | nonce | aléatoire |
| 105+n | 32 | SHA-256 de l'image **arrière** | |
| 137+n | 32 | SHA-256 du **selfie** | |

Sur devnet (`n = 6`), le manifeste fait **175 octets**.

**L'ordre est toujours arrière puis selfie**, partout : capture, manifeste, blob,
affichage. L'intervertir change le commitment, et un test le vérifie.

Les hashes portent sur les images **nettoyées** — celles produites par
`PhotoSanitizer`, sans métadonnées — jamais sur les octets bruts du capteur.

## Commitment

`commitment = SHA-256(octets du manifeste)`

C'est ce que la transaction `check_in` inscrit on-chain, et ce que le wallet
signe via MWA en signature détachée.

## Vecteur d'or

Entrées :

- `programId` = 32 octets `0x01`
- `wallet` = 32 octets `0x02`
- `nonce` = 16 octets `0x03`
- `day` = 20706
- réseau = `devnet`
- image arrière = `"arrière"` en UTF-8, selfie = `"selfie"` en UTF-8

Commitment attendu :

```
cd6e5720f11bf646617ef9ebf69bf2cfdf656cc36f1421e9cf6954fd8e69f093
```

Reproduction indépendante, sans passer par le code du projet :

```python
import hashlib, struct
manifest = (b"clockin-post-v1" + bytes([1]) + bytes([6]) + b"devnet"
            + bytes([1])*32 + bytes([2])*32 + struct.pack("<q", 20706) + bytes([3])*16
            + hashlib.sha256("arrière".encode()).digest()
            + hashlib.sha256("selfie".encode()).digest())
print(hashlib.sha256(manifest).hexdigest())
```

Ce vecteur est rejoué par `PostManifestTest` (app) et le sera par le test
correspondant du keyserver.

## Ce qui est prouvé, et ce qui ne l'est pas

**Prouvé :** cette paire d'images exacte existait à cet instant et a été engagée
par ce wallet, horodatée par le réseau. Toute modification ultérieure d'un pixel
exige une nouvelle signature.

**Non prouvé :** qu'un capteur a observé la réalité. Un appareil rooté ou une
caméra virtuelle peut injecter des images. La double capture élève le coût de la
fraude sans l'éliminer.

La suppression des métadonnées ne masque pas une adresse, un badge ou un lieu
reconnaissable **visible dans les pixels**. Le wallet et le jour de publication
restent des données publiques corrélables.
