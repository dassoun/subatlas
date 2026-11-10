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

## Import depuis Shearwater Cloud
L'écran « Plongées » propose « Importer depuis Shearwater » : on choisit un export Shearwater Cloud (fichier `.db`, SQLite).
SubAtlas le lit lui-même (lecteur SQLite et décompression gzip en Kotlin pur dans `SqliteReader.kt` et `Inflate.kt`, donc
identiques sur Android et iOS) :
- `dive_details` : date et heure, site, pays, profondeur max et moyenne, durée, température, lestage, combinaison, notes,
  bouteilles (pressions de début et de fin converties de PSI en bar, volume, mélange) ;
- `log_data` : le profil, au format binaire Shearwater « PNF » (enregistrements de 32 octets) : profondeur, température et
  pression de la bouteille 1 toutes les 5 s environ.

Une plongée du carnet à la même date et à moins d'une heure (ou déjà importée de la même source) est complétée sans rien
écraser de ce qui a été saisi ; sinon une nouvelle plongée est créée, avec son site. Un aperçu demande confirmation.
La courbe s'affiche dans la fiche de la plongée (un appui ou un glissement donne les valeurs au point touché).

Limites connues : la sélection du fichier n'est pas encore implémentée sur iOS (comme l'export des données) ; seul le
capteur de la bouteille 1 alimente la courbe de pression ; les exports en unités impériales n'ont pas été testés.

## Courbe de plongée : plein écran et vitesse de remontée
- Le bouton en haut à droite du profil l'affiche en plein écran, en paysage. Sur Android l'écran est verrouillé en paysage le
  temps de l'affichage (`PlatformActions.setLandscape`) ; sur iPhone, où ce verrouillage n'est pas encore écrit, le contenu est
  pivoté d'un quart de tour et on tourne le téléphone soi-même. L'activité Android déclare `configChanges` pour que la
  rotation ne la recrée pas (sinon l'appli retomberait sur l'écran d'accueil).
- La vitesse de remontée est calculée à partir du profil, lissée sur environ 30 s (`DiveProfile.ascentRates`). Les tronçons de
  courbe passent en jaune au-delà de 10 m/min, en orange au-delà de 15 et en rouge au-delà de 20 par défaut ; seuils et couleurs se règlent dans les réglages (`AscentScale`, dans `Settings.kt` ; ce sont des repères de l'appli et non ceux d'un fabricant). Un bilan sous la courbe donne la vitesse max et le
  temps passé au-dessus de 10 m/min ; la lecture au toucher indique la vitesse du point touché.

## Recevoir un export Shearwater depuis une autre appli (Android)
SubAtlas apparaît dans le menu « Partager » (et « Ouvrir avec ») pour les fichiers de type `application/*`, dont l'export
`.db` de Shearwater : depuis l'appli Shearwater (Profil > Options > Exporter), « Partager > Sub Atlas » ouvre directement
l'aperçu d'import, sans passer par un e-mail. Voir `MainActivity.handleIntent` et `IncomingFile`.
Si SubAtlas n'apparaît pas (type de fichier inhabituel), enregistrer le fichier dans « Téléchargements » puis utiliser
« Importer depuis Shearwater » dans l'écran Plongées.

## Fichiers Shearwater contenant beaucoup de plongées
Un export complet (des centaines de plongées) mélange deux formats de journal (`log_data.format`) :
- `sw-pnf` : plongées enregistrées par l'ordinateur (profil binaire PNF) ;
- `uddf` : plongées saisies dans un autre logiciel puis importées dans Shearwater Cloud. Un vrai profil UDDF
  (plus de douze points) est rééchantillonné à intervalle régulier ; une simple saisie manuelle (quatre points, un
  trapèze) n'a pas de courbe, car elle serait fausse. La durée vient alors de `diveduration`.

Autres points : les capteurs de pression éteints (0 PSI) ne créent pas de bouteille, une taille de bouteille sans unité
(« 12 ») est lue en litres, un lestage de 0 est ignoré, et la lecture du fichier se fait hors du fil principal avec un
message d'attente (quelques secondes pour 200 plongées sur l'émulateur).
