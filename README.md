# SubAtlas

Boîte à outils pour plongeurs — Kotlin Multiplatform + Compose Multiplatform.

## Fonctions (v0.1)
- **Sites de plongée** : ajout via position GPS (ou saisie manuelle), profondeur max, accès (bord / bateau), notes, ouverture dans l'app de cartes.
- **Check-lists** : deux listes par défaut (équipement, briefing), création/renommage/suppression, ajout d'éléments, cocher / tout décocher.
- Données stockées localement en JSON (`sites.json`, `checklists.json`).

## Structure
- `shared/` — code commun (UI, modèles, état) + implémentations Android (`androidMain`) et iOS (`iosMain`)
- `androidApp/` — application Android (ouvrir le dossier dans Android Studio, lancer `androidApp`)
- `iosApp/` — sources Swift ; à intégrer dans un projet Xcode (voir ci-dessous)

## Android
```
./gradlew :androidApp:assembleDebug
```

## iOS (nécessite un Mac + Xcode)
1. Xcode → New Project → iOS App (SwiftUI), nom `iosApp`, dans ce dossier `iosApp/`; remplacer ses fichiers Swift par ceux fournis.
2. Ajouter une *Run Script Phase* avant la compilation : `cd "$SRCROOT/.." && ./gradlew :shared:embedAndSignAppleFrameworkForXcode`
3. Build Settings → *Framework Search Paths* : `$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`; *Other Linker Flags* : `-framework Shared`; désactiver *User Script Sandboxing*.
4. Info.plist :
   - `NSLocationWhenInUseUsageDescription` (« Enregistrer la position d'un site de plongée ») ;
   - `NSContactsUsageDescription` (« Proposer vos contacts comme binôme et leur partager une plongée ») ;
   - `CFBundleURLTypes` avec le schéma `subatlas` (liens de plongée partagée `subatlas://dive?d=…`).

## Partage d'une plongée avec un binôme
Le message de partage contient un lien `https://dassoun.github.io/subatlas/dive.html#…` (constante `SHARE_PAGE_URL` dans
`Sharing.kt`). La plongée voyage dans la partie après `#`, qui n'est jamais envoyée à un serveur. La page (dossier `docs/`)
affiche un résumé, ouvre SubAtlas si l'appli est installée (Android : lien `intent://` avec repli vers le store ; iOS :
schéma `subatlas://`) et propose sinon de la télécharger. Si le lien ne s'ouvre pas, l'écran « Plongées » propose aussi
« Coller une plongée reçue ».

**Mise en ligne de la page** (une fois) : pousser le dossier `docs/`, puis sur GitHub : *Settings → Pages → Build and
deployment → Deploy from a branch → `main` / `/docs`*. La page est alors servie sur
`https://dassoun.github.io/subatlas/dive.html` (un dépôt privé exige un compte GitHub payant pour Pages).

**Avant la publication de l'appli** : renseigner `PLAY_STORE` / `APP_STORE` en haut du script de `docs/dive.html` une
fois les fiches des stores créées. Pour ouvrir l'appli directement depuis un lien https (sans passer par le bouton de la
page), il faudra un nom de domaine à soi (Android App Links et iOS Universal Links exigent un fichier de vérification à la
racine du domaine, ce que `github.io` ne permet pas pour un dépôt de projet).
