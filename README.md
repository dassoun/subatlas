# SubAtlas

Boîte à outils pour plongeurs — Kotlin Multiplatform + Compose Multiplatform.

## Fonctions (v0.1)
- **Sites de plongée** : ajout via position GPS (ou saisie manuelle), profondeur max, accès (bord / bateau), notes, ouverture dans l'app de cartes.
- **Accueil** (premier onglet, écran de démarrage) : tableau de bord du carnet, calculé à partir des plongées et des sites
  (`Dashboard.kt`, fonction `computeDashboard`) : nombre de plongées et temps total sous l'eau (en jours, heures et minutes au-delà de 24 h ; durées connues seulement, la
  note « durée connue pour N plongées sur M » le dit quand il en manque), sites et pays visités, records (profondeur max,
  plongée la plus longue, eau la plus froide : une pastille touchable ouvre la plongée), site favori, histogramme des plongées
  par année, pays avec leur nombre de plongées, dernière plongée avec sa courbe, et un bouton qui ouvre la carte des sites
  où l'on a plongé. Sans plongée, un message d'accueil propose d'en ajouter une.
- **Liste des sites** : recherche, filtre par pays (les graphies « France », « france » et « FR » sont regroupées) et bouton
  🗺️ qui affiche les sites de la liste sur une carte intégrée (voir ci-dessous).
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

## Pictogrammes
Plus aucun emoji de décor dans l'appli : tout est dessiné en traits dans `Pictograms.kt`, comme les pictogrammes des types de
site (`SiteIcons.kt`). Les tracés sont du SVG sur une grille de 24 x 24 lus par `PathParser` de Compose, trait de 1,8, bouts
arrondis, couleur du thème (celle du bouton, de l'onglet ou du texte qui entoure le pictogramme). Pas de fichier image : ils
suivent le thème clair ou sombre, restent nets à toute taille et sont identiques sur tous les téléphones.
- L'enum `Picto` en liste les 27 : les sept onglets (accueil, masque, épingle, ancre, liste, bouée, engrenage), carte,
  satellite, globe, loupe, personne, avertissement, validation, croix, plus, moins, chevrons, flèche de profondeur,
  chronomètre, thermomètre, étoile, œil (visibilité), manomètre (SAC), double flèche (profondeur moyenne) et image (partage).
- `Pictogram(icon, size, tint, filled)` dessine un pictogramme seul ; `filled` ajoute un léger remplissage sur sa forme
  principale (onglet actif, pastilles de chiffres de l'accueil). `PictoText(texte, icon)` en met un devant (ou derrière,
  avec `iconAtEnd`) un texte, à la taille de la police et dans la même couleur (boutons, liste de sites).
- Pour en changer un ou en ajouter un : modifier ou ajouter ses tracés dans `Picto.shapes()` (le tracé marqué `accent` est la
  forme remplie).
- Restent des emoji, volontairement : les drapeaux de pays, qui sont des données (le pays saisi). Restent aussi des symboles
  de texte : « ✓ LISTE OK » et « ✗ LISTE KO » (états d'une check-list), les puces « ● » de la légende du graphique et les
  flèches « → ↑ ↓ » insérées dans des phrases.
- Sept onglets tiennent juste dans la barre du bas : libellés en 10 sp et marge latérale de 4 dp, sans quoi « Check-lists » et
  « Paramètres » sont coupés et la pastille des onglets d'extrémité touche le bord de l'écran.

## Carte des sites
Le bouton 🗺️ de la liste des sites ouvre une carte (`SitesMap.kt`) avec un repère par site de la liste affichée (pays et
recherche respectés ; les sites sans position GPS n'y figurent pas). Quand certains de ces sites n'ont aucune plongée, une
fenêtre demande s'il faut tous les afficher ou seulement ceux qui ont au moins une plongée (repère bleu : avec plongée, gris :
sans, rouge : sélectionné ; il porte le pictogramme du type du site). Un appui sur un repère affiche une fiche résumée, un second appui ouvre le site.

Le fond de carte vient des tuiles OpenStreetMap (`https://tile.openstreetmap.org`), dessinées par l'appli (projection de
Mercator, déplacement et pincement au doigt, double appui pour zoomer) : pas de bibliothèque de carte, le même code sert
Android et iOS. Il faut du réseau pour le fond détaillé ; sans réseau, un fond de carte embarqué (terres émergées du monde,
sans routes ni noms) s'affiche sous les repères, avec un message. Respect de la
politique d'usage d'OpenStreetMap : `User-Agent` identifié, deux téléchargements à la fois, tuiles gardées en mémoire (120 au
plus) et dans le cache HTTP du système (`HttpResponseCache` sur Android, `NSURLCache` sur iOS), mention © contributeurs
OpenStreetMap sur la carte. Les tuiles passent par `PlatformActions.httpGetBytes`.

**Fond embarqué (hors ligne).** Ni OpenStreetMap ni Google n'autorisent d'enregistrer leurs tuiles pour le hors-ligne, d'où
un fond à part : les terres émergées de Natural Earth 1:50m (domaine public, « Made with Natural Earth »), converties par
`tools/make_land.py` en `shared/src/commonMain/composeResources/files/land.bin` (174 Ko, 1 422 anneaux, 59 247 points,
précision 1/1000 de degré). Pour le régénérer : télécharger `ne_50m_land.zip` (naturalearthdata.com, lien de téléchargement
hébergé sur naciscdn.org) puis `python -I tools/make_land.py ne_50m_land.zip --res 1000 --tol 0 --out <fichier>` ; sans
`--out`, le script affiche les tailles obtenues avec d'autres réglages. Lecture et dessin dans `WorldLand.kt` : le tracé est
construit pour un niveau de zoom entier puis seulement déplacé et mis à l'échelle à chaque image. Limite : les côtes sont
généralisées d'environ un kilomètre, donc le fond n'est dessiné que jusqu'au zoom 9 (`LAND_MAX_ZOOM`) ; au-delà, fond neutre.

## Vue satellite (Google Maps, Android)
L'écran carte s'ouvre **en vue satellite Google avec noms quand Google est utilisable (clé fournie, services Google
présents) et qu'il y a du réseau** (`PlatformActions.isOnline`, permission `ACCESS_NETWORK_STATE`) ; sinon il s'ouvre sur la
carte OpenStreetMap et son fond embarqué. Un bouton 🛰️ / 🗺️ bascule à la main entre les deux. Si la carte Google ne s'affiche
pas au bout de 15 s (`GOOGLE_LOAD_TIMEOUT_MS` : clé désactivée ou refusée, quota, réseau absent ; détecté par
`onMapLoaded`), l'écran revient seul à OpenStreetMap avec un message. Attention : Google garde sur l'appareil les tuiles déjà
vues, une clé refusée peut donc sembler fonctionner sur les zones déjà consultées. Sans clé, l'appli fonctionne comme avant
et le bouton est absent.

Code : `GoogleMap.kt` (déclaration commune et regroupement), `GoogleMap.android.kt` (SDK Maps pour Android via
`maps-compose`). Les repères, la sélection et la fiche du site sont les mêmes que sur OpenStreetMap ; la position de la
carte suit d'une vue à l'autre. Pas de « Map ID » : la carte reste dans la ligne de facturation gratuite du SDK mobile. Sur
la carte Google, **les sites proches sont regroupés** (`groupPins`) : deux sites à moins de 44 dp l'un de l'autre à l'écran
sont dans le même groupe, dessiné en disque portant leur nombre. La proximité se transmet de proche en proche (composantes
connexes) : le résultat ne dépend pas de l'ordre des sites, deux groupes ne se chevauchent jamais et, en zoomant, les groupes
ne font que se scinder. Un appui sur un groupe zoome pour le défaire. Le site sélectionné reste toujours seul. **La carte OpenStreetMap regroupe de la même façon** : même fonction (`groupPins`, rayon de 44 dp, recalcul par quart de
niveau de zoom), même disque numéroté (blanc cerclé, bleu s'il y a au moins un site avec plongée, gris sinon). Un appui sur un
groupe y déplace la vue en 350 ms pour cadrer ses sites, au moins un niveau de zoom plus près (`clusterCamera` : sans cela un
groupe de sites très proches se reformerait tel quel), ou zoome de trois niveaux si tous sont au même endroit ; un geste de
l'utilisateur interrompt ce déplacement. Les sites ne sont donc regroupés que sur la carte, pas dans la liste. Sur iPhone la
vue satellite n'existe pas encore (`GoogleMap.ios.kt` répond « indisponible » et la carte OpenStreetMap reste la seule).

**Clé d'API.** Le SDK exige une clé et un projet Google Cloud avec facturation activée. La clé se met dans
`local.properties` (jamais versionné) : `MAPS_API_KEY=…` ; `androidApp/build.gradle.kts` la place dans le manifeste
(`com.google.android.geo.API_KEY`). Sans clé, l'appli fonctionne comme avant et le bouton est absent. La clé étant lisible
dans l'APK, il faut la restreindre dans la console Google Cloud : applications Android (`com.sensitivemobility.subatlas` +
empreinte SHA-1 de la clé de signature : celle de debug, puis celle de release ou de Play App Signing) et API « Maps SDK for
Android » seule.

## Import depuis Shearwater Cloud
Réglages > Données > « Importer depuis d'autres applications » propose « Importer depuis Shearwater » : on choisit un export Shearwater Cloud (fichier `.db`, SQLite).
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

- **Fichier reçu** : un export de sites (`subatlas-sites-<date>.json`) partagé avec l'appli (« Partager > SubAtlas ») ou ouvert avec elle (« Ouvrir avec ») est reconnu à son contenu (`"format": "subatlas-sites"`). Rien n'est fait sans confirmation : une boîte de dialogue annonce le nombre de sites (dont ceux qui sont localisés), puis « Importer » les intègre comme le bouton « Importer des sites » (récapitulatif, puis onglet Sites). Tout fichier reçu (plongée, sites, Shearwater, Dive Logs Data, UDDF) demande ainsi une confirmation avant de modifier le carnet.

## Import Dive Logs Data (divelogs.de)
Le bouton « Importer Dive Logs Data » (Réglages > Données) lit un export `.DLD` : une archive ZIP (lue par `ZipReader`, en Kotlin
pur, comme le gzip de Shearwater) qui contient un fichier XML par plongée (`DivelogsImport.kt`). Chaque plongée est convertie en
`ShearwaterDive` (marquée `fromDivelogs`) : l'**aperçu** (nouvelles / à fusionner, sites au nom proche rattachables un à un), la
**fusion** et l'application sont ceux de l'import Shearwater, et un fichier envoyé à l'appli (« Partager » / « Ouvrir avec ») est
reconnu à son en-tête ZIP.
- **Lu** : date, heure (fin déduite de la durée), durée, profondeurs max et moyenne, site et ville (le ville va dans les notes du
  site, comme dans le carnet), pays (un à trois mots reconnus par la liste des pays), position GPS, températures, bouteilles
  (principale et `ADDITIONALTANKS` : volume, pressions, mélange), lestage, binôme, visibilité (« > 10m | 30ft » → 10 m), notes,
  ordinateur (le « Dummy Computer » de Mares est ignoré) et la courbe des échantillons (intervalle, profondeur, température
  reportée d'un échantillon à l'autre). Un 0 du fichier veut dire « non renseigné ».
- **Fusion** : comme pour Shearwater, une plongée du carnet du même jour dont l'heure de début est à moins d'une heure est
  complétée sans rien écraser. Différences : elle peut être une plongée **déjà importée de Shearwater** (la source Divelogs
  s'ajoute à ses sources), et sa **courbe et son ordinateur sont gardés** (la courbe Shearwater a LND, paliers et CNS, pas celle de
  Divelogs). Réimporter le même fichier ne refait rien (`divelogs:<identifiant>`).
- Essayé sur un export de 189 plongées : 188 fusionnées et 1 nouvelle (la plongée du 30 octobre 2025 n'a pas d'équivalent à moins
  d'une heure dans le carnet).
- **Position des sites** : le fichier donne la position GPS du site de chaque plongée ; un site du carnet encore sans position la reçoit
  (et son pays s'il n'en a pas) quand une de ses plongées est fusionnée ; un site déjà localisé n'est pas modifié. Un site créé par
  l'import reçoit aussi cette position.
- Limites : la météo, le bateau et la température de l'air du fichier ne sont pas gardés (pas de champ dans le carnet).

## Export et import UDDF
UDDF (*Universal Dive Data Format*, uddf.org) est le format XML d'échange des carnets de plongée : Subsurface, MacDive, divelogs.de,
Shearwater Cloud et la plupart des autres le lisent et l'écrivent. Unités du standard (SI) : profondeurs en m, durées en s,
températures en **kelvins**, pressions en **pascals**, volumes de bouteille en **m³**, mélanges en fractions de 0 à 1.
- **Exporter** : Réglages > Données > « Exporter au format UDDF » écrit `subatlas-<date>.uddf` (`UddfExport.kt`) : sites (nom,
  pays, position, notes), mélanges de gaz, binômes, ordinateurs, et pour chaque plongée la date et l'heure, le site, les bouteilles
  (volume, pressions, mélange), le lestage, la courbe (profondeur, température, pression de la bouteille 1), les profondeurs max et
  moyenne, la durée, la température minimale, la visibilité et les notes. Les plongées sont groupées par jour (`repetitiongroup`).
  Non écrits, faute d'équivalent dans le standard : photos, type de plongée, combinaison, palanquée, LND, paliers, PPO2 et CNS.
  Les points de courbe tiennent sur une ligne (un carnet de 199 plongées fait environ 10 Mo).
- **Importer** : Réglages > Données > « Importer un fichier UDDF », ou un fichier UDDF envoyé à l'appli (« Partager > SubAtlas » /
  « Ouvrir avec ») : il est reconnu à son contenu (déclaration XML puis balise `<uddf`), pas à son extension. Le lecteur XML
  (`Xml.kt`) est écrit en Kotlin pur, donc identique sur Android et iOS ; `UddfImport.kt` convertit chaque plongée en
  `ShearwaterDive` (marquée `fromUddf`) : **aperçu**, rattachement des sites au nom proche et **fusion** sont ceux de l'import
  Dive Logs Data (rien d'écrasé, courbe et ordinateur déjà importés conservés, position GPS du site complétée) et une
  confirmation est demandée avant d'agir.
- **Tolérance** : les logiciels s'écartent souvent du standard, donc une valeur qui n'a pas la bonne grandeur est reconnue
  (température de moins de 200 : déjà en °C ; pression de moins de 1000 : déjà en bar ; volume d'un ou plus : déjà en litres ;
  fraction de gaz supérieure à 1 : déjà en %). Un 0 veut dire « non renseigné ». Les dates `<datetime>` ISO et l'ancien codage
  `<date><year>…` sont lus, le pays vient de `<address><country>` ou de `<location>` (« France Toulon », usage de divelogs.de).
  Un journal de moins de douze points (plongée saisie à la main) ne donne pas de courbe.
- **Position des sites** : même pour une plongée déjà importée (rien d'autre n'est refait), le site du carnet encore sans position
  reçoit celle du fichier, à condition que son nom soit proche de celui du fichier (pour ne pas donner la position d'un autre
  lieu à un site rattaché à la main). Sur l'émulateur, le fichier divelogs a fait passer les sites localisés de 4 à 48 sur 62.
- **Doublons** : un fichier UDDF de divelogs.de nomme ses plongées `dive_<numéro>` ; SubAtlas en reprend la clé
  `divelogs:<numéro>` : une plongée déjà importée du fichier `.DLD` n'est pas refaite. Les autres sources donnent `uddf:<identifiant>`.
- **Vérifié** (émulateur) : un export de divelogs.de de 91 plongées (89 retrouvées comme déjà importées du `.DLD`) ; sur un carnet
  vide, 91 plongées, 45 sites et 43 courbes créés ; aller-retour d'un carnet de 199 plongées (courbes, bouteilles, températures,
  notes et sites identiques). Non essayé : fichiers de Subsurface ou MacDive, et iPhone (non compilé).

## Téléchargement Bluetooth d'un Shearwater (Android)
Réglages > Données > « Importer d'un Shearwater en Bluetooth » (`ShearwaterBleDialog.kt`) : recherche des appareils Bluetooth LE, connexion,
sommaire des plongées de l'ordinateur (date, durée, profondeur max). Celles qui sont **déjà dans le carnet** sont marquées (clé
`shearwater-ble:<série>:<heure>`, ou plongée déjà importée de Shearwater Cloud le même jour, à moins d'une heure, à 0,3 m de
profondeur max près) ; les **nouvelles sont cochées**. « Télécharger N plongées » les lit (environ 40 s chacune, écran gardé allumé,
progression et bouton Arrêter), puis elles passent par l'**aperçu d'import habituel** (nouvelles / à fusionner, sites rattachables,
confirmation) : une plongée du carnet à la même date et à moins d'une heure est complétée sans rien écraser (la courbe déjà importée est
gardée), sinon une nouvelle plongée est créée, sans site (l'ordinateur ne connaît ni le site, ni les notes, ni le binôme).
Les relevés bruts sont enregistrés dans `files/ble/` (`manifest.bin`, `dive-<empreinte>.bin`) et un journal visible, partageable, décrit
chaque échange.
- `ShearwaterBleImport.kt` : une plongée téléchargée devient un `ShearwaterDive` (marquée `fromDevice`) : date et heure du sommaire (horloge de
  l'ordinateur), durée, profondeur max, moyenne et température calculées sur les échantillons immergés, mélange respiré (octets 8 et 9 de
  chaque échantillon) avec pression de début et de fin si l'émetteur était allumé, courbe complète par le décodeur « PNF » de l'import
  Shearwater Cloud, ordinateur « Peregrine TX (977D8B42) ».
- `Ble.kt` : interfaces `BleTransport` / `BleConnection` (commun), `AndroidBle.kt` : recherche, connexion GATT, service « série » de
  Shearwater (`fe25c237-0ece-443c-b0aa-e02033e7029d`, sinon le premier service qui notifie et accepte des écritures), notifications,
  écritures de 20 octets ; permissions Bluetooth demandées à l'exécution (`BLUETOOTH_SCAN`/`CONNECT`).
- `ShearwaterBle.kt` (Kotlin pur) : le protocole de libdivecomputer : paquets SLIP, trames de 2 octets, lectures d'identifiants (`0x22`),
  téléchargement (`0x35`/`0x36`/`0x37`), décompression (plages de zéros sur 9 bits, XOR par 32 octets), sommaire à `0xE0000000`.
- **Vérifié sur un Peregrine TX (firmware V104 Classic, Android)** : connexion, identité, sommaire de 48 plongées (heure de début et de fin,
  durée, profondeur max, numéro de journal, adresses) et téléchargement complet d'une plongée (27 776 octets décompressés, 731
  échantillons de 5 s) : profondeurs identiques, échantillon par échantillon, à celles importées depuis Shearwater Cloud.
- **Ce que l'essai a appris** : (1) il faut demander une **MTU** plus grande juste après la connexion (`requestMtu(517)`, l'appareil
  accorde 80) : l'appareil envoie des notifications plus longues que les 20 octets de la MTU par défaut, que le téléphone tronque, et
  les réponses de plus d'une trame n'arrivent alors jamais ; (2) les adresses du sommaire se comptent depuis la base lue en `0x8021`
  (`0x80000000` pour le format natif) ; (3) l'en-tête de trame `[nombre, rang]` ne sert pas à reconstituer le paquet, la fin se
  reconnaît à l'octet `0xC0` ; (4) le débit est faible (environ 700 octets décompressés par seconde : une plongée d'une heure demande
  une quarantaine de secondes), donc on ne lit que les plongées nouvelles.
- **Sommaire** : des pages de 48 entrées de 32 octets, de la plus récente à la plus ancienne ; chaque nouvelle demande à la même adresse
  donne la page suivante et la dernière est incomplète (`ShearwaterBle.manifest`, comme libdivecomputer). **Vérifié sur un Perdix AI** :
  255 plongées lues, n° 1 à 255, de janvier 2017 à novembre 2026, sans doublon. Une entrée : `A5C4` (présente), heure de début (+4) et de
  fin (+8) en secondes depuis 1970 à l'heure de l'ordinateur, durée en secondes (+12), profondeur max en dm (+16), numéro de journal (+18), adresses de début (+20) et de fin (+24).
- **Vérifié sur un Peregrine TX (V104 Classic)** : la plongée convertie donne les mêmes valeurs que Shearwater Cloud (heures, 3620 s, max
  13,9 m, moyenne 9,7 m, 26,8 °C, air, 212 à 49 bar, 726 points identiques) ; la fusion avec la plongée du carnet n'a rien écrasé.
  La création d'une plongée nouvelle n'a été essayée que sur l'émulateur.
- **Une bouteille par gaz** : la bouteille principale est le premier gaz respiré (avec ses pressions si l'émetteur était allumé) ; chaque
  autre mélange respiré (au moins 10 s, pour écarter un parasite) devient une bouteille sans pression, avec sa durée d'utilisation et sa
  profondeur moyenne ; chaque gaz **activé** dans l'ordinateur mais non respiré a aussi la sienne, sans usage. L'ordinateur garde dix gaz
  en mémoire (cinq en circuit ouvert, cinq diluants), désactivés compris : le masque des octets 17-18 de l'enregistrement d'ouverture
  `0x14` dit lesquels sont activés (O2 à partir de l'octet 20 de `0x10`, hélium aux octets 30-31 de `0x10` puis 1 à 8 de `0x11`). Vu sur
  un Perdix en recycleur (V102 Classic) : masque `0042`, soit le secours à 30 % et le diluant à l'air, au lieu de sept bouteilles. Rejoué sur les 103
  journaux du Peregrine TX : mêmes bouteilles que l'ancienne règle sauf sur 2 plongées, où un 60 % désactivé dans l'ordinateur n'a plus
  la sienne (les notes disent que le bloc était emporté : à ajouter à la main). Un pony ou un bloc du même gaz, sans émetteur ni configuration, est invisible pour l'ordinateur : il
  reste à saisir. La fusion avec une plongée du carnet associe les bouteilles **par gaz** (et non par rang) : une plongée saisie à la main
  avec ses bouteilles dans un autre ordre garde chaque volume ; la bouteille manquante est ajoutée. Une plongée déjà fusionnée de deux
  sources n'est pas modifiée. Le décodeur a été essayé sur les 103 plongées de l'export Shearwater Cloud (dont 5 à plusieurs gaz) :
  profondeurs max et moyenne (moyenne des échantillons non nuls, comme Shearwater), température et longueur de courbe identiques ; le
  24 avril 2026 Cloud attribue le 60 % à la bouteille équipée de l'émetteur, le décodeur Bluetooth l'air (la pression 2840 à 678 psi est
  celle d'une bouteille d'air). Le 30 avril 2026 à 10:00 n'a qu'un gaz configuré (l'air) : le pony y est dans les notes seulement.
- **Coupure** : la barre de progression et l'erreur sont affichées en haut de la fenêtre. Un silence de l'ordinateur de plus de 10 s ou une
  déconnexion signalée par le téléphone (appareil éloigné, éteint, Bluetooth coupé) donne un message en clair (« appareil hors de portée
  ou éteint », « l'ordinateur ne répond plus ») ; les plongées déjà lues restent importables (« Importer les N lues »). **Pas de reprise
  automatique** : le Peregrine cesse de transmettre dès que la liaison est coupée (il affiche « log error : time out »), « Recommencer »
  repart d'une connexion neuve. Essayé en vrai : Bluetooth du téléphone coupé pendant le téléchargement, et Peregrine éloigné.
- iPhone : pas de couche Bluetooth (non compilé).

## Unités métriques ou impériales
Réglages > Unités : **métrique** (m, °C, bar, L, kg, par défaut) ou **impérial** (ft, °F, psi, cuft, lb). Seul l'affichage et la saisie
changent : les données restent enregistrées en métrique (`Units.kt`, lu par tous les écrans par `LocalUnits`).
- **Affiché** : profondeurs (max, moyenne, par bouteille, de site, visibilité), température, pression des bouteilles, volume de
  bouteille, gaz consommé, SAC, lestage, vitesse de remontée (seuils des réglages, infobulle et bilan du graphique), distance aux sites,
  tableau de bord, carte de partage, import. Les graduations du graphique tombent sur des valeurs rondes de l'unité (5 m ou 10 ft ; 50 bar
  ou 500 psi).
- **Saisie** : les formulaires de plongée et de site s'ouvrent et se lisent en unités affichées, puis sont convertis en métrique à
  l'enregistrement. Un champ non modifié rend la valeur d'origine : ouvrir puis enregistrer une plongée ne déforme rien (vérifié).
- **Volume de bouteille en cuft** : le litre est un volume d'eau, le pied cube un volume de gaz à la pression de service ; la conversion
  suit la convention de 3000 psi (11,1 L = 80 cuft). Le gaz consommé et le SAC (gaz libre : 1 L = 0,0353 cuft) n'ont pas cette ambiguïté.
- **Non converti** : le texte de la page Sécurité (remontée rapide à 15 m/min), l'épaisseur de combinaison (mm), la pression partielle
  d'oxygène (bar).
- **Import** : les exports Shearwater en unités impériales sont convertis à la lecture (déjà géré) ; non essayé sur un vrai fichier.

## Association d'un ordinateur de plongée (Android)
Réglages > « Importer depuis un ordinateur de plongée » (`ComputerSettings.kt`, parcours voulu dans `docs/import-ordinateur-plongee.md`).
Un seul ordinateur Shearwater associé à la fois ; la section, les boutons et les messages portent **le modèle lu dans l'appareil**
(« Perdix AI », « Peregrine TX » ; modèle inconnu : son nom Bluetooth) :
- **Associer un ordinateur de plongée** : la recherche Bluetooth démarre à l'ouverture de la fenêtre (l'ordinateur doit avoir son Bluetooth
  activé, Shearwater Cloud fermé) ; on le touche dans la liste (seuls les ordinateurs Shearwater sont montrés, avec un lien « Afficher
  tous les appareils Bluetooth »). L'appli s'y connecte pour lire son numéro de série, son modèle et son firmware (preuve
  que c'est un ordinateur Shearwater) puis **enregistre l'association** dans les réglages : `Settings.computers` (`DiveComputer` : adresse
  Bluetooth, nom, modèle, série, firmware, famille de protocole). La section affiche alors « Peregrine TX (977D8B42) · V104 Classic ».
  La recherche des nouvelles plongées **s'enchaîne sur la même connexion** : l'ordinateur n'en accepte qu'une par activation de son
  Bluetooth (sur un Perdix, la fermer puis en rouvrir une donnait « ordinateur introuvable »). Quand aucune connexion n'aboutit, le
  message invite à quitter puis réactiver le Bluetooth de l'ordinateur.
- **Dissocier** (avec confirmation) : efface l'enregistrement ; les plongées déjà importées sont conservées.
- Décision : la détection et l'import automatiques ne se feront **que lorsque SubAtlas est ouverte** (pas de service en arrière-plan).
- **Détection (appli ouverte seulement)** : tant que SubAtlas est au premier plan et qu'un ordinateur est associé, l'appli guette son adresse
  Bluetooth (`BleTransport.waitForDevice` : recherche filtrée sur l'adresse, mode équilibré, sans permission à redemander). Dès qu'il
  apparaît, `ComputerImport` (`ComputerImport.kt`) se connecte, lit le sommaire et ne garde que les **nouvelles plongées** (celles qui ne
  sont pas déjà dans le carnet). Il ne se redéclenche pas tant que l'ordinateur reste à portée : il faut qu'il disparaisse (Bluetooth
  coupé sur l'ordinateur) plus de 15 s, ou qu'on revienne dans l'appli plus de 5 min après le dernier import. La détection se met en
  pause pendant l'association et l'import manuel.
- **Avec confirmation (par défaut)** : une fenêtre liste les nouvelles plongées (cochées ; « Les nouvelles » et « Aucune »
  cochent ou décochent tout), « Importer (N) » ou « Plus tard ». Aucune
  nouvelle plongée : message de 3 s. La connexion **reste ouverte** pendant la confirmation : après l'envoi de la liste, le Peregrine
  (« Liste envoyée ») attend les demandes de téléchargement sur cette connexion (une première version la fermait puis la rouvrait : le
  téléchargement échouait). En cas d'échec, la fenêtre propose « Partager le journal » des échanges.
- **Case « Importer automatiquement les nouvelles plongées »** (par ordinateur, `DiveComputer.autoImport`) : sans confirmation ; une jauge
  et un message indiquent le traitement (« Connexion au Perdix AI… », « Lecture de la liste… », « Plongée 2 sur 3 · 40 % », « Ajout des
  plongées au carnet… ») puis le résultat (« 3 plongées ajoutées, 1 fusionnée »). Une coupure en route ajoute tout de même les plongées déjà
  lues et affiche la cause ; les autres seront proposées la fois suivante.
- **Importer les nouvelles plongées** : bouton de la section, même flux sans attendre la détection. Les plongées sont ajoutées par
  l'import habituel (fusion avec une plongée du carnet à la même heure, sinon plongée nouvelle, sans site à renseigner ensuite).
- **Vérifié sur un Perdix AI (V102 Classic, 25 novembre 2026)** : détection, import avec confirmation de 10 plongées (date, heure, durée
  et profondeur max identiques au sommaire de l'ordinateur) et lecture de la liste complète. La plus ancienne plongée (2017, version de
  journal 13, **unités impériales**) est au même format et convertie en mètres et °C. Limite connue : la liste des plongées proposées
  affiche sa profondeur en pieds comme des mètres (le sommaire ne passe pas par la conversion).
- **Vérifié** : sur ton Peregrine TX, l'association (recherche, identité, enregistrement ; le Peregrine annonce son service Bluetooth
  Shearwater et son nom) ; sur l'émulateur, la section, la case, la fenêtre (connexion, erreur « ordinateur introuvable »), Dissocier.
  **Non vérifié** : la détection et l'import complet depuis cet écran avec un vrai Peregrine.

## Photos de plongée
- **Ajout** : « Ajouter des photos » (sous les notes de la fiche, ou la tuile « + » de la rangée de vignettes quand il y en a
  déjà) ouvre la galerie du système en sélection multiple (12 photos au plus d'un coup). C'est le sélecteur de photos
  d'Android (`PickMultipleVisualMedia`) : l'appli n'a **aucune permission** à demander et ne voit que les photos choisies.
- **Réduction** (`PhotoPreparation.android.kt`) : une photo d'appareil (4000 pixels, 3 à 8 Mo) est décodée directement réduite,
  son orientation (EXIF) appliquée aux pixels, puis réencodée en JPEG de 1600 pixels de côté au plus (qualité 85, 50 à 400 Ko) avec
  une vignette de 400 pixels (5 à 30 Ko). Le réencodage retire aussi les métadonnées, **le lieu GPS de la prise de vue compris**.
  Les photos sont traitées une à une : l'image d'origine n'est jamais gardée en plusieurs exemplaires en mémoire.
- **Stockage** : les octets ne vont pas dans `dives.json` (qui reste du texte, 8 Mo avec les courbes) mais dans des fichiers
  privés de l'appli, `photos/<id>.jpg` et `photos/<id>_t.jpg` (`Storage.readBytes / writeBytes / delete`, `AndroidStorage`) ;
  la plongée garde une liste `photos` de références (`DivePhoto` : identifiant et dimensions). Un fichier absent s'affiche
  comme un fond neutre.
- **Fiche de la plongée** : la première photo est la **couverture**, en bandeau 16 par 9 tout en haut de la fiche ; en dessous,
  une rangée de vignettes. Un appui ouvre la **visionneuse** plein écran (glissé d'une photo à l'autre, rang « 2 / 3 ») avec :
  une étoile pour **définir la photo comme couverture** (elle passe en première), le **partage** de la photo (feuille de
  partage du système, `shareFile`) et deux façons de s'en défaire, chacune avec confirmation :
  - **Retirer de la plongée** (disque barré) : la photo est déliée de la plongée et la copie réduite de l'appli est effacée ;
    la photo d'origine, dans la galerie du téléphone, n'est pas touchée.
  - **Supprimer du téléphone** (corbeille) : en plus, la photo d'origine est effacée de la galerie. L'appli garde l'adresse de
    l'original (`DivePhoto.source`, accès conservé avec `takePersistableUriPermission`) et demande la suppression au système
    (`MediaStore.createDeleteRequest`, Android 11 et plus) : **Android affiche sa propre confirmation** (« Autoriser SubAtlas à
    supprimer cette photo ? »). Si l'utilisateur refuse, ou si l'original n'est plus accessible, rien n'est supprimé et la
    photo reste dans la plongée (un message l'explique). Testé sur l'émulateur, y compris après redémarrage de l'appli.
- **Accueil** : le temps sous l'eau (et la plongée la plus longue) s'écrivent en toutes lettres, « 5 jours 10 heures 13 minutes »,
  au singulier ou au pluriel, sans unité nulle (`Strings.spelledDuration`).
- **Ailleurs** : une pastille « photo + nombre » sur la carte de la plongée dans la liste ; la fiche d'un site montre les
  photos de toutes ses plongées (visionneuse en lecture seule, on les gère depuis la plongée) ; la fusion de deux plongées
  réunit leurs photos ; supprimer une plongée (ou toutes) supprime ses fichiers.
- **Mémoire** : les images décodées sont gardées dans un cache de 64 Mo au plus (`PhotoCache`, la moins récemment utilisée part
  la première) ; vignettes et images sont décodées hors du fil principal.
- **Ce que les photos ne suivent pas** : elles ne font pas partie de la sauvegarde (`Exporter les données` : un fichier JSON, qui
  ne garderait que des références) ni du fichier de plongée envoyé à un binôme (`photos` y est vidé) ; changer de téléphone ou
  réinstaller l'appli les perd. Un import fusionné ou remplaçant des plongées laisse sur le disque les fichiers des photos
  qu'il ne référence plus (pas de nettoyage automatique).
- **iPhone** : `PlatformActions.canPickPhotos` y est faux, le bouton n'apparaît donc pas. Reste à écrire sur Mac : le sélecteur
  (`PHPickerViewController`), la réduction des photos (`preparePhoto`) et le stockage de fichiers de `IosStorage`
  (`readBytes / writeBytes / delete`, aujourd'hui sans effet). L'affichage (couverture, rangée, visionneuse) est déjà commun.
- Limites de cette première version : une photo ne se zoome pas au pincement dans la visionneuse, on ne peut pas réordonner
  les photos autrement qu'en choisissant la couverture, et on ne peut pas prendre une photo avec l'appareil depuis l'appli
  (on choisit dans la galerie).

## Finitions
- **Transitions entre écrans** (`ScreenTransition.kt`) : le nouvel écran apparaît en fondu en 240 ms, en glissant de 36 dp depuis la
  droite quand on ouvre une fiche et depuis la gauche quand on revient ; un changement d'onglet ou le passage à la plongée
  voisine (glissé) est un fondu seul. L'ancien écran disparaît aussitôt, volontairement : le garder à l'écran le temps de
  l'animation obligerait à le recomposer, et une fiche supprimée se « refermerait » une seconde fois (retour de trop dans la
  pile). Pas d'animation pour le tout premier écran (l'écran de démarrage fait son fondu), ni pour la carte (une vue native,
  celle de Google, ne s'anime pas bien sous une transparence).
- **Courbe qui se dessine** : à l'ouverture de la fiche, le profil se révèle de gauche à droite en 0,9 s (`reveal` dans
  `ProfileChart`, un rognage de plus en plus large du tracé : aplats, courbes et palier apparaissent ensemble). Pas en plein
  écran, où l'on vient lire la courbe. Elle rejoue quand on passe à une autre plongée.
- **Repères de carte par type de site** : la tête du repère porte le pictogramme du type du site (mer, lac, rivière, carrière,
  fosse, grotte, piscine, autre), en blanc sur la couleur du repère ; un site sans type garde le point blanc. Même rendu sur la
  carte OpenStreetMap, la carte Google (`GooglePin.type`) et le bandeau de la fiche de plongée. Pour cela les repères sont un
  peu plus grands (rayon 11 dp, 14 dp pour le site sélectionné, `pinRadiusDp`) ; le dessin du pictogramme est partagé avec
  les listes (`drawSiteType` dans `SiteIcons.kt`).
- **États vides illustrés** (`EmptyState.kt`) : listes des plongées, des sites et des centres vides, et recherche de sites sans
  résultat. Une petite scène sous-marine (disque d'eau, vague, bulles) portant le pictogramme de la liste, un titre et
  l'explication. Le texte est coupé à son premier saut de ligne : la première ligne fait le titre, le reste l'explication.
- **Plongées groupées par mois** : triée par date (dans les deux sens), la liste des plongées porte un titre par mois
  (« Novembre 2026 · 3 plongées », `Strings.monthYear`) qui reste collé en haut tant que les plongées du mois défilent
  (`stickyHeader`, fonction expérimentale de Compose : `OptIn` sur `DiveList`). Les autres tris (site, pays, profondeur,
  durée, SAC) n'ont pas de titres.

## Image de plongée à partager
Le bouton « Partager en image » de la fiche d'une plongée (sous « Partager la plongée », pour une plongée qui a un site) ouvre
l'aperçu d'une carte portrait 4 par 5 (`ShareCard.kt`) : logo et numéro de la plongée, drapeau et pays, nom du site, date et
horaires, la courbe, puis trois chiffres clés à pictogrammes (profondeur max, durée, température de l'eau ; à défaut de l'un
d'eux, la profondeur moyenne ou la visibilité). « Partager l'image » l'envoie à la feuille de partage du système (WhatsApp,
messagerie, réseaux sociaux, enregistrement dans la galerie).
- **Une image, pas un écran** : la carte a les mêmes couleurs dans les deux thèmes (dégradé océan vers abysse, texte blanc). La
  courbe y est blanche sur une eau turquoise qui s'éteint avec la profondeur ; la surface du palier est en rouge (quand la
  plongée en a un, sans dépendre de la pastille « Déco » de la fiche) et les remontées trop rapides gardent leurs couleurs de
  gravité. Aucun repère n'est posé sur la courbe (la profondeur max est dans les chiffres clés). Sans courbe, la profondeur max
  s'affiche en grand à la place.
- **Capture** : la carte affichée dans l'aperçu est enregistrée telle quelle dans une couche graphique Compose
  (`rememberGraphicsLayer`), sans coins arrondis, puis encodée en PNG (`toPngBytes`, déclaration commune dans
  `ImageEncoding.kt`, `Bitmap.compress` sur Android, Skia sur iPhone). L'image fait la largeur de l'aperçu à l'échelle de
  l'écran : environ 910 x 1140 pixels sur le téléphone d'essai.
- **Envoi** : `PlatformActions.shareFile` (fichier temporaire du cache exposé par le `FileProvider`, comme le fichier de plongée).
  Sur iPhone, `shareFile` écrit dans le dossier temporaire et ouvre la feuille de partage ; ce code n'a pas été compilé (pas de
  Mac), et il rend aussi l'envoi du fichier de plongée possible sur iPhone, où il retombait sur « enregistrer le fichier »
  (non implémenté).
- Les textes de la carte suivent la langue de l'appli. Pas de carte du site dans l'image : elle dépendrait du réseau au moment
  de la capture.

- **Couleurs de remontée** : la courbe de l'image reste d'une seule couleur (blanche). Réglages > Avancé > « Couleurs de remontée sur l'image à partager » les colore selon les seuils de vitesse de remontée ; désactivé par défaut (`Settings.shareAscentColors`).

## Courbe de plongée : plein écran et vitesse de remontée
- Le bouton en haut à droite du profil l'affiche en plein écran, en paysage. Sur Android l'écran est verrouillé en paysage le
  temps de l'affichage (`PlatformActions.setLandscape`) ; sur iPhone, où ce verrouillage n'est pas encore écrit, le contenu est
  pivoté d'un quart de tour et on tourne le téléphone soi-même. L'activité Android déclare `configChanges` pour que la
  rotation ne la recrée pas (sinon l'appli retomberait sur l'écran d'accueil).
- La vitesse de remontée est calculée à partir du profil, lissée sur environ 30 s (`DiveProfile.ascentRates`). Les tronçons de
  courbe passent en jaune au-delà de 10 m/min, en orange au-delà de 15 et en rouge au-delà de 20 par défaut. Ces seuils
  forment une liste que l'utilisateur règle dans les réglages : valeur de chaque seuil, couleur (8 au choix), ajout (jusqu'à
  8) et retrait de seuils, jusqu'à n'en garder aucun (`AscentScale.levels`, dans `Settings.kt` ; ce sont des repères de
  l'appli et non ceux d'un fabricant). La courbe prend la couleur du plus haut seuil dépassé. Un `settings.json` écrit
  avant la liste (trois niveaux fixes) est repris tel quel à la lecture (`AscentScale.migrated`). Un bilan sous la courbe
  donne la vitesse max et le temps passé au-dessus du seuil le plus bas (rien n'est cité s'il n'y a aucun seuil) ; la lecture
  au toucher indique la vitesse du point touché.

## Fiche de plongée plus visuelle
- **Courbe en grand** : 260 dp de haut au lieu de 210. La colonne d'eau entre la surface et la courbe est remplie d'un
  dégradé vertical qui va du bleu clair du lagon (surface) à l'océan puis à l'abysse (le bas du tracé) ; en thème sombre,
  l'abysse se confondrait avec le fond : le dégradé part d'un turquoise lumineux et s'arrête à l'océan plein, et le rouge du
  palier y est plus opaque (sinon, sur cette eau plus claire, il vire au gris). Le dégradé suit toute la hauteur du tracé, pas celle de la plongée : à
  une même profondeur, la teinte est la même d'une plongée à l'autre (`waterGradient` dans `ProfileChart.kt`). Ordre de
  dessin : les aplats (l'eau, puis la surface du palier), ensuite les courbes (LND, CNS, PPO2, pression), puis la profondeur
  et le bord du palier ; aucune courbe n'est voilée par le dégradé.
- **Palier en surface** : quand la série « Déco » est affichée (pastille rouge sous le titre du profil), la zone interdite,
  de la surface jusqu'à la profondeur du premier palier, est remplie en rouge translucide, avec le trait rouge en bordure. Elle
  se creuse quand le plafond descend et disparaît quand il n'y a plus de palier. Même rendu en plein écran, et en bandeau dans
  les mini-courbes de la liste et de l'accueil (`ProfileSparkline`), qui suivent la même pastille. Cette série reste
  **masquée par défaut**, comme avant : on l'active d'un appui sur la pastille.
- **Chiffres clés à pictogrammes** : profondeur max (flèche vers le bas), moyenne (double flèche), durée (chronomètre), SAC
  (manomètre), température de l'eau (thermomètre) et visibilité (œil), en tuiles de deux par ligne. La température et la
  visibilité ne sont plus répétées dans la carte « Conditions et matériel ».
- **Carte du site en bandeau** (`SiteBanner.kt`) : sous le nom du site, quand il a une position, une carte OpenStreetMap de 150 dp
  au zoom 14 avec un repère au centre ; un appui ouvre la fiche du site. Elle réutilise les tuiles de l'écran carte (cache
  partagé, deux téléchargements à la fois, mention © contributeurs OpenStreetMap). Sans réseau et sans tuile déjà vue, elle se
  rabat sur le fond de terres embarqué au zoom 6. Sur Android, quand la vue Google est utilisable (clé, services Google) et qu'il y a du réseau, le bandeau est une **vue satellite Google en mode lite** (GoogleSiteMap : une image fixe sans geste, avec le repère du type du site) ; si elle ne s'affiche pas en 10 s, ou sans réseau, ou sur iPhone, il reste le plan OpenStreetMap décrit ci-dessus (avec son fond de terres embarqué hors ligne). Les appuis sont ceux du bandeau (ouvrir le site).
- `sw-pnf` : plongées enregistrées par l'ordinateur (profil binaire PNF) ;
- `uddf` : plongées saisies dans un autre logiciel puis importées dans Shearwater Cloud. Un vrai profil UDDF
  (plus de douze points) est rééchantillonné à intervalle régulier ; une simple saisie manuelle (quatre points, un
  trapèze) n'a pas de courbe, car elle serait fausse. La durée vient alors de `diveduration`.

Autres points : les capteurs de pression éteints (0 PSI) ne créent pas de bouteille, une taille de bouteille sans unité
(« 12 ») est lue en litres, un lestage de 0 est ignoré, et la lecture du fichier se fait hors du fil principal avec un
message d'attente (quelques secondes pour 200 plongées sur l'émulateur).
