# Marqo local dans Moment

Implémenté le 20 septembre 2026. Le modèle FP32 est embarqué dans l’APK ; aucune photo ni aucun score n’est envoyé par le composant de modération.

## Modèle et moteur retenus

- Modèle : `Marqo/nsfw-image-detection-384`, révision `0c26ec22111b83f106d72a55f611ec35962bcb65`.
- ONNX FP32, opset 17, 22 484 016 octets. SHA-256 : `2d4f2ae32c5c201c620182566da7495014bdb47a2b66bf5de9d4ab8cff225bfc`.
- ONNX Runtime Android **1.30.0**, CPU EP, **4 threads intra-op**, exécution séquentielle, spinning désactivé.
- Une instance `LocalModerator` appartient à `MomentApplication`. Chargement et chauffe démarrent hors UI au lancement ; les analyses sont sérialisées sur son worker. Les tenseurs/résultats sont fermés après chaque inférence. Les outils de test peuvent fermer leur session et ses options.
- Hash, contrat, forme/type d’entrée/sortie et politique sont vérifiés avant toute analyse. Aucun téléchargement de modèle à l’exécution.

Le choix CPU est mesuré, pas supposé. XNNPACK est conservé comme candidat dans le benchmark et utilise ORT intra-op=1, spinning=0, son propre pool à 2 ou 4 threads.

## Mesures sur Seeker / Android 16

30 paires consécutives par configuration ; deux JPEG publics de test, prétraitement et deux inférences compris. Une inférence de chauffe et une paire non mesurée précèdent les mesures. Le processus contient également l’interface et la session applicative ; la mémoire ci-dessous est donc celle du processus entier, pas celle du modèle seul.

| Configuration | Chargement + chauffe | Paire p50 | Paire p95 | PSS maximal échantillonné du processus |
| --- | ---: | ---: | ---: | ---: |
| CPU / 2 threads | 484 ms | 492 ms | 499 ms | 384 007 Ko |
| **CPU / 4 threads** | **407 ms** | **329 ms** | **343 ms** | 357 234 Ko |
| XNNPACK / 2 threads | 615 ms | 846 ms | 853 ms | 379 390 Ko |
| XNNPACK / 4 threads | 617 ms | 838 ms | 845 ms | 418 420 Ko |

Données brutes : [marqo-seeker.json](benchmarks/marqo-seeker.json). Le chargement n’est pas une mesure du démarrage complet de l’app. Le PSS est échantillonné entre les paires et ne capture pas forcément un pic transitoire. Ce test court et cet ordre fixe ne caractérisent ni toute la variabilité des appareils ni la chauffe prolongée. Il faut refaire une campagne longue sur un corpus représentatif avant une garantie de latence/batterie.

## Contrat pixels

L’entrée `image` est UINT8 RGB NHWC `[1,384,384,3]`. Transposition, conversion float, division par 127,5, soustraction de 1 et softmax sont **dans le graphe**. La sortie `nsfw` est FLOAT `[1]`, classe 0. Les poids restent FP32 : UINT8 désigne uniquement les pixels d’entrée.

Avant le graphe : `pad-rgb128-bilinear-v1`, implémenté dans `ModerationImage.kt` et `scripts/nsfw/preprocess.py`.

1. Partir des JPEG assainis de `PhotoPair`, orientés et sans EXIF, bord maximal 1280. Ne pas redécoder le fichier brut du capteur pour la modération.
2. Lire les dimensions avant décodage ; le décodeur Android augmente `inSampleSize` si le bord dépasse 1536 (protection supplémentaire pour les entrées inattendues).
3. Conserver le ratio, ajuster le bord long à 384, arrondir les dimensions par `floor(x + 0.5)`.
4. Interpolation bilinéaire explicite aux centres des pixels, bords bornés, arrondi des canaux par `floor(x + 0.5)`. Centrer dans du RGB `(128,128,128)` ; l’éventuel pixel supplémentaire va à droite/en bas.
5. Copier explicitement R/G/B vers un `ByteBuffer` direct. Pas de `copyPixelsToBuffer` ARGB.

Une fixture RGB asymétrique vérifie l’égalité octet par octet Android/Python. Une autre vérifie la parité du modèle desktop/Android à 1e-5 sur les quatre moteurs. Un contrôle JPEG séparé tolère de petits écarts de décodage ; cette tolérance ne remplace pas la calibration de la chaîne réelle.

Le padding est un choix expérimental de préservation du cadrage portrait. Il diffère du center-crop bicubique de référence timm. L’outil de calibration évalue les deux ; aucune supériorité du padding en précision n’est affirmée.

## Capture, verdict et publication

`replaceDraft` et la restauration d’un brouillon déclenchent l’analyse. `ModerationCoordinator` annule le job précédent et vérifie une génération : même si un appel natif termine tard, il ne peut pas remplacer le verdict d’une nouvelle paire ou d’une remise à zéro.

Score du Moment = `max(arrière, avant)`. Les états visibles sont : analyse en cours, accepté, reprise conseillée, bloqué, indisponible. Une erreur de chargement, de décodeur ou de runtime n’est jamais transformée en score sûr. Le bouton « Réessayer l’analyse » relance la paire courante.

Le contrôle s’applique dans l’interface **et avant le chemin wallet**. Une zone intermédiaire exige une confirmation après vérification des deux photos ; le blocage ne peut pas être acquitté. Aucune sanction automatique n’est ajoutée.


## Politique expérimentale et calibration

`app/app/src/main/assets/moderation/policy.json` contient les seuils configurables : revue à 0,5, blocage à 0,9, `calibrated: false`. Ce sont des seuils UX provisoires, pas des seuils validés. Le mode debug les utilise en signalant leur caractère expérimental. **La vraie publication release reste fermée tant que la politique n’est pas calibrée.** Le hash du modèle et le contrat de prétraitement sont liés à la politique et vérifiés au chargement.

Aucun corpus utilisateur n’était disponible pendant l’intégration. Il reste à constituer des paires représentatives : selfies, plage, sport, faible lumière, cadrages difficiles, avec des labels vérifiés. Il faut distinguer les paires de calibration et celles d’évaluation finale, sans réutiliser une même image. Préparer les JPEG par la chaîne de production, notamment `PhotoSanitizer`.

Deux CSV, colonnes `rear,front,label` : chemins relatifs au CSV, label 0 pour une paire SFW, 1 si au moins une vue est NSFW. L’outil rejette les images partagées entre les deux jeux, exige les deux classes et refuse les JPEG avec EXIF ou un bord >1280.

```sh
python scripts/nsfw/calibrate.py \
  --calibration /chemin/calibration.csv \
  --evaluation /chemin/evaluation.csv \
  --output /chemin/rapport.json
```

Il propose des seuils candidats selon des objectifs configurables de rappel/FPR, puis rapporte les TP/FP/FN/TN sur le jeu indépendant, pour padding et center-crop. Les objectifs par défaut sont exploratoires. L’outil **ne modifie pas la politique de l’app et ne marque jamais une calibration comme validée**. Évaluer aussi les sous-groupes et la taille/incertitude du corpus avant de renseigner les seuils, l’identifiant de politique et `calibrated: true`. Réévaluer après toute quantification ; aucune quantification n’est activée ici.

## Reproduire l’export et les vérifications

Python 3.10, environnement isolé, versions dans `scripts/nsfw/requirements.txt` (PyTorch 2.6.0, timm 1.0.15, ORT desktop 1.21.0). Android utilise ORT 1.30.0 et sa parité est testée séparément.

```sh
python3 -m venv /tmp/moment-nsfw-venv
/tmp/moment-nsfw-venv/bin/pip install -r scripts/nsfw/requirements.txt
/tmp/moment-nsfw-venv/bin/python scripts/nsfw/export_model.py --validation-images /chemin/images
/tmp/moment-nsfw-venv/bin/python -m unittest discover -s scripts/nsfw -p 'test_*.py'
```

L’export charge strictement les poids safetensors de la révision fixée, sans code distant, désactive l’attention fusionnée timm et utilise une entrée fixe. `onnx.checker` vérifie le graphe. Les comparaisons PyTorch/ORT couvrent les images fournies et leurs variantes portrait/paysage. La première image sert aussi à régénérer les fixtures des tests Android. Si le hash change, mettre à jour la politique après validation, sans conserver une ancienne calibration.

Export initial : deux images publiques (beignets Hugging Face et chien PyTorch), six comparaisons, écart maximal **2,384185791015625e-7**. Ces images valident la mécanique numérique, **pas la détection NSFW**. Métadonnées, versions, hash et mesures de parité sont dans `assets/moderation/model.json`. Licence Apache-2.0 et attribution accompagnent le modèle.

Pour les tests sur appareil, compiler les APK puis les installer avec `adb install -r -t` et exécuter directement l’instrumentation. Cette méthode garde l’app et ses rapports installés après les tests ; certaines configurations de `connectedDebugAndroidTest` désinstallent les APK à la fin.

```sh
cd app
./gradlew :app:testDevnetDebugUnitTest :app:assembleDevnetDebug :app:assembleDevnetDebugAndroidTest :app:compileMainnetReleaseKotlin
adb install -r -t app/build/outputs/apk/devnet/debug/app-devnet-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/devnet/debug/app-devnet-debug-androidTest.apk
adb shell am instrument -w -e class com.clockin.hackathon.moderation.LocalModeratorTest,com.clockin.hackathon.ui.DeveloperModeTest,com.clockin.hackathon.ui.ModerationReviewTest com.clockin.hackathon.dev.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.clockin.hackathon cat files/moderation-benchmark.json
```

Le test d’interface exige un téléphone déverrouillé. Capture du rendu : [review.png](screenshots/moderation/review.png).

## Limite côté serveur

Le contrôle local reste contournable par un client modifié. Le [keyserver Rust](../keyserver/README.md), ajouté le 22 septembre 2026, analyse les images correspondant au manifeste avant de co-signer, avec le même ONNX et un prétraitement Rust vérifié contre les fixtures Android. Il refuse la zone de revue sans acquittement client. Le déploiement et le branchement Android restent à effectuer ; les seuils restent à calibrer.

Sources :
- https://huggingface.co/Marqo/nsfw-image-detection-384/blob/0c26ec22111b83f106d72a55f611ec35962bcb65/config.json
- https://huggingface.co/Marqo/nsfw-image-detection-384/blob/0c26ec22111b83f106d72a55f611ec35962bcb65/README.md
- https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html
- https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
- https://huggingface.co/datasets/huggingface/documentation-images/resolve/main/beignets-task-guide.png
- https://raw.githubusercontent.com/pytorch/hub/master/images/dog.jpg
