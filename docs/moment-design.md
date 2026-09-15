# Moment — direction UX/UI

Ce document décrit le lot graphique 0.1. La capture réelle et la persistance ajoutées en 0.2 sont décrites dans le [README](../README.md).

Nom validé le 15 septembre 2026 : **Moment**.

L’inspiration Apple porte sur la clarté, la hiérarchie visuelle, les proportions et le mouvement. L’implémentation reste native Android / Compose.

## Identité

- Fond graphite `#080A0C`, surfaces `#15181C`, texte ivoire `#F4F3EF`.
- Accents Solana adoucis : menthe `#8AEAC5`, lavande `#C2B5F5`.
- Logotype Moment, symbole circulaire et point évoquant un instant capturé.
- Icône adaptative, variante monochrome et lancement sur fond sombre.
- Police système sans-serif, titres semi-gras, textes secondaires lisibles et capitales limitées.

## Interactions

- En-tête stable et barre de navigation flottante ; chaque page défile dans sa propre zone.
- Navigation : fondu et déplacement vertical de quelques pixels, 320 ms au maximum.
- Boutons : légère compression à 97,5 %, retour avec un ressort amorti, retour tactile natif.
- Onglets : teinte et surface interpolées sur 220 ms, sémantique Android de sélection accessible.
- Aucun mouvement décoratif permanent. Animations Compose pilotées par l’échelle d’animation du système.
- Cartes et contenu défilables ; carte de feed verrouillé à hauteur minimale pour laisser le texte agrandi respirer.
- Une action principale par étape. La capture reste une simulation clairement indiquée.

## Portée technique

Le nom visible Android et l’identité affichée par MWA deviennent Moment. L’identifiant d’application `com.clockin.hackathon`, les packages et le programme Anchor sont conservés pour permettre les mises à jour de l’app installée. L’URI de connexion existante reste provisoire : aucun domaine Moment n’a été inventé.

Cette itération ne branche ni caméra réelle, ni transfert de tokens, ni serveur. Les règles économiques restent celles de la démo précédente.

## Validation

- APK compilé ; 12 tests unitaires existants réussis ; aucune alerte Android Lint.
- Parcours complet rejoué sur Pixel 7 Pro / API 36 : entrée, mise, capture fictive, publication, feed, sortie et annulation.
- Navigation vérifiée avec texte à 130 % et animations système désactivées ; réglages restaurés après l’essai.
- Captures dans `docs/screenshots/moment/`. Les retours tactiles et la fluidité sur Seeker physique restent à valider ; aucun objectif de fréquence d’image n’a été mesuré sur appareil.
