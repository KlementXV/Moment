# Simplification du cercle — 16 septembre 2026

- Série compacte dans le header, grand anneau retiré de l’écran.
- Carte centrée, un seul déclencheur de capture sur le cercle verrouillé.
- Cadenas dans l’onglet Le cercle ; raccourci caméra conservé depuis Moi.
- Conditions SKR dans le profil ; suppression du texte redondant sous la carte.
- Date et échéances locales, compte à rebours dans les trois dernières heures.

## Vérification

`assembleDebug`, `testDebugUnitTest` et `lintDebug` réussis : 76 tests JVM,
aucune erreur Lint, quatre avertissements sur du code ou de la configuration
préexistants. Les six tests ajoutés vérifient notamment les changements de date,
les heures d’été/hiver, un fuseau à décalage fractionnaire et le passage de minuit UTC.

Captures sur l’émulateur Pixel 7 Pro API 36 :

- [Cercle fermé](01-circle.png)
- [Police à 150 %](02-large-font.png)
- [Profil et raccourci caméra](03-profile.png)

Ouverture de la caméra vérifiée depuis le cercle et depuis Moi ; fermer la
capture depuis Moi ramène au profil. Police de l’émulateur rétablie à 100 %.

APK installé avec succès sur le Seeker via `adb install -r`, sans effacement des
données (mise à jour confirmée à 23:51, heure de Paris). Le contrôle visuel sur
le téléphone physique reste à effectuer : son écran est verrouillé.
