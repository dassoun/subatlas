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
Le message de partage contient un lien `subatlas://dive?d=…` (données de la plongée encodées) et l'adresse de
téléchargement de l'appli (`APP_DOWNLOAD_URL` dans `Sharing.kt`, à remplacer par les adresses des stores une fois
l'appli publiée). Les liens à schéma personnalisé ne sont pas toujours cliquables dans les messageries : l'écran
« Plongées » propose donc aussi « Coller une plongée reçue ». Pour des liens https cliquables partout (avec renvoi
automatique vers le store si l'appli est absente), il faudra un nom de domaine (Android App Links / iOS Universal Links).
