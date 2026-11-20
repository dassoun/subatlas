---
name: kmp-mobile-app
description: Créer une application mobile Android + iOS en Kotlin Multiplatform et Compose Multiplatform, du squelette Gradle jusqu'à l'APK installé sur un téléphone. Couvre la stack et ses versions, l'architecture (code commun, abstractions de plateforme, navigation, thème, textes FR/EN, pictogrammes dessinés), l'écran de démarrage (splash système + splash Compose), les icônes, les données en JSON, les fichiers et photos, les cartes, et la vérification sur émulateur avec adb. À utiliser quand on demande de créer, d'initialiser ou de structurer une appli mobile, ou de refaire « comme SubAtlas ».
---

# Créer une appli mobile Kotlin Multiplatform (Android d'abord, iOS ensuite)

Ce skill est tiré de la construction de **SubAtlas** (carnet de plongée : plongées, sites, carte, courbes, photos, partage) :
une appli Android complète, testée sur émulateur et sur téléphone, dont l'interface est écrite une seule fois en Compose
Multiplatform. Reprends ces choix tels quels sauf demande contraire ; chaque écart doit avoir une raison.

## 0. Ce qu'il faut savoir avant d'écrire du code

Pose ces questions (une seule fois, groupées) si la réponse n'est pas déjà dans la demande :

1. **Nom** de l'appli, **identifiant de paquet** (`com.société.appli`), langues (FR seul ? FR + EN ?).
2. **Plateformes** : Android seul pour commencer est le plus sûr. iOS est **écrit mais jamais compilé sur Windows** : il faut un Mac.
3. **Capacités natives** : position GPS, contacts, carte, photos, partage de fichiers, liens profonds, réseau ?
   Chacune a un coût (permissions, clés d'API, code par plateforme) : voir `references/architecture.md`.
4. **Identité visuelle** : logo (SVG de préférence), couleur de fond du démarrage, thème clair/sombre/système.
5. **Données** : tout en local (JSON) ou un serveur ? Le modèle d'SubAtlas est 100 % local, sans compte.

## 1. Stack retenue (versions éprouvées en novembre 2026)

| Brique | Choix |
|---|---|
| Langage / UI | Kotlin 2.4.21, Compose Multiplatform 1.12.2, Material 3 1.9.0 |
| Build | Gradle (wrapper), AGP 9.5.1, catalogue de versions `gradle/libs.versions.toml`, cache de configuration activé |
| Modules | `:shared` (plugin `com.android.kotlin.multiplatform.library` + `kotlinMultiplatform`) et `:androidApp` (application) |
| Cibles | Android `minSdk 26`, `compileSdk/targetSdk 37` ; iOS `iosArm64` + `iosSimulatorArm64`, framework statique `Shared` |
| Bibliothèques | coroutines 1.11, kotlinx-serialization-json 1.11, kotlinx-datetime 0.8, activity-compose 1.13, core-splashscreen 1.2 |
| Ressources | `compose.components.resources` (dessins vectoriels et fichiers dans `commonMain/composeResources`) |
| Carte (option) | maps-compose 9.0.1 (Android) ; plan OpenStreetMap dessiné soi-même en commun |

Les versions changent : **vérifie les dernières stables avant de les figer**, puis garde le catalogue comme source unique.
Fichiers Gradle complets prêts à adapter : `references/gradle-et-versions.md`.

## 2. Structure du dépôt

```
appli/
├─ settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
├─ shared/                      tout le code commun + androidMain + iosMain
│  └─ src/{commonMain,androidMain,iosMain}/kotlin/<paquet>/
│     └─ commonMain/composeResources/{drawable,files}/
├─ androidApp/                  MainActivity, manifeste, thèmes, icônes, FileProvider
├─ iosApp/                      sources Swift à intégrer dans un projet Xcode (créé sur Mac)
├─ design/                      logo source (SVG)
├─ tools/                       scripts Python : icônes, conversion du logo, données embarquées
├─ docs/                        pages web éventuelles (lien de partage)
└─ README.md                    mis à jour à CHAQUE fonction ajoutée
```

`.gitignore` : `local.properties` (chemin du SDK, **clés d'API**), `*.jks`, `*.keystore`, `keystore.properties`, `build/`, `.gradle/`,
`.kotlin/`, `dist/`, `*.apk`. Ne jamais versionner une clé ni un mot de passe.

## 3. Ordre de travail

1. **Squelette** : Gradle, deux modules, une `MainActivity` qui appelle `App(...)`, l'icône par défaut. Compiler et installer **tout de suite**.
2. **Démarrage** : écran de démarrage système + écran Compose (voir §4), thème sombre par défaut sans clignotement.
3. **Charpente** : thème, navigation par pile, barre d'onglets, textes FR/EN, pictogrammes.
4. **Abstractions de plateforme** : `Storage`, `PlatformActions`, `LocationProvider`… (interfaces en commun, implémentations par plateforme).
5. **Données** : modèles `@Serializable`, `AppState` (listes observables) qui lit/écrit des fichiers JSON.
6. **Fonctions**, une par une, chacune **vérifiée sur l'émulateur** (capture d'écran) avant la suivante.
7. **README** à jour, puis installation sur le téléphone **une fois validé** et copie de l'APK dans Téléchargements.

## 4. Écran de démarrage (splash) — le détail est dans `references/splash-et-icones.md`

Deux écrans qui se relaient sans saut visible :
- **Système** (Android 12+) : `androidx.core:core-splashscreen`, `installSplashScreen()` avant `super.onCreate`, thème
  `Theme.SplashScreen` avec fond, **symbole seul** (Android le découpe dans un disque) et `postSplashScreenTheme`.
- **Compose** : même couleur de fond, symbole **exactement au même endroit**, le nom (wordmark) apparaît en fondu, 1,5 s, puis
  fondu de sortie ; il absorbe les appuis ; l'état « déjà montré » survit à la rotation (`rememberSaveable`).
- Le thème de l'appli a la même couleur en `windowBackground` : pas d'éclair blanc entre les deux.
- Le logo SVG est converti en dessins vectoriels par un script (`tools/svg_to_vector.py`), pas redessiné à la main.

## 5. Règles de conception à garder

- **Une seule UI** en commun ; le natif ne sert qu'à ce que Compose ne sait pas faire. Pas de `expect/actual` quand une
  **interface injectée** suffit (testable, et iOS peut avoir une implémentation par défaut « indisponible »).
- Un **état** (`AppState`) avec `mutableStateListOf` ; chaque écriture réécrit le JSON. JSON tolérant :
  `ignoreUnknownKeys`, `coerceInputValues`, champs nouveaux avec valeur par défaut (les anciens fichiers restent lisibles).
- **Navigation** : pile de `sealed interface Screen` ; l'écran affiché est le dernier. Les transitions ne gardent **pas** l'ancien
  écran à l'écran (il serait recomposé : un élément supprimé se « refermerait » deux fois).
- **Textes** : interface `Strings` + `StringsFr` / `StringsEn`, `LocalStrings.current`. Aucun texte en dur dans les écrans.
- **Pas d'emoji de décor** : pictogrammes tracés en SVG sur grille 24 × 24 (trait 1,8, bouts arrondis), couleur du thème.
  Les drapeaux de pays restent des emoji (ce sont des données).
- **Hors ligne d'abord** : ce qui dépend du réseau (carte, recherche) a un repli embarqué et le dit à l'utilisateur.
- **Permissions au plus juste** : sélecteur de photos du système (aucune permission), position à la demande, contacts à la demande.
- **Commentaires** : en français, ils disent *pourquoi*, pas *quoi*.
- **Le README suit le code** : chaque fonction ajoutée y entre avec ses limites connues.

## 6. Vérifier sur émulateur — le détail est dans `references/test-et-deploiement.md`

Boucle : compiler → `installDebug` **sur l'émulateur seulement** (`ANDROID_SERIAL`) → lancer → attendre la fin du démarrage
(≥ 9 s) → toucher → capture → lire la capture. Sauvegarder les données de l'appli (md5) avant tout test qui les modifie et les
restaurer ensuite. Le téléphone de l'utilisateur ne reçoit l'appli qu'**une fois le développement validé**.

## 7. Ce qu'il faut dire honnêtement à la fin d'un travail

- Ce qui a été **vérifié** (et sur quoi : émulateur, téléphone) et ce qui ne l'a **pas** été.
- **iOS n'est pas compilé** tant qu'il n'y a pas de Mac : le dire à chaque fois que du code iOS est touché.
- Ce qui n'est **pas commité**, les limites connues (données non sauvegardées, fonctions absentes sur iOS…).

## Références

- `references/gradle-et-versions.md` — tous les fichiers Gradle, le catalogue de versions, le manifeste et les thèmes à copier.
- `references/splash-et-icones.md` — splash système + Compose, géométrie du logo, icônes adaptatives, scripts.
- `references/architecture.md` — interfaces de plateforme, lancement des sélecteurs depuis l'Activity, navigation, thème, textes, pictogrammes, JSON, fichiers/photos, cartes, capture d'une vue en image.
- `references/test-et-deploiement.md` — recettes adb, Windows (PowerShell / Git Bash), installation sur téléphone, pièges rencontrés.
