# Architecture du code commun et des abstractions de plateforme

Paquet unique `<paquet>` dans `shared/src/{commonMain,androidMain,iosMain}/kotlin/`. Les fichiers sont courts et nommés par rôle
(`App.kt`, `AppState.kt`, `Models.kt`, `Strings.kt`, `Theme.kt`, `Settings.kt`, `Platform.kt`, `Pictograms.kt`, `Dashboard.kt`…).

## 1. Abstractions de plateforme (interfaces en commun)

```kotlin
interface Storage {                       // un fichier texte par nom
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun readBytes(name: String): ByteArray? = null                 // binaires (photos) : défaut « indisponible »
    fun writeBytes(name: String, bytes: ByteArray): Boolean = false
    fun delete(name: String) {}
}

interface PlatformActions {               // tout ce que Compose ne sait pas faire
    fun openMap(latitude: Double, longitude: Double, label: String)
    fun shareText(text: String)
    fun shareFile(name: String, mimeType: String, bytes: ByteArray): Boolean = false
    fun openLink(url: String)
    fun isOnline(): Boolean = true
    suspend fun httpGetBytes(url: String, userAgent: String): ByteArray? = null
    suspend fun saveTextFile(suggestedName: String, text: String): Boolean
    suspend fun openFileBytes(): ByteArray?
    val canPickPhotos: Boolean get() = false
    suspend fun pickPhotos(): List<PreparedPhoto> = emptyList()
    // …
}
```

Règles :
- **Les valeurs par défaut disent « indisponible »** : une plateforme (iOS) qui n'a pas la fonction n'a rien à écrire, et l'UI
  **cache** le bouton avec `if (actions.canPickPhotos)`. Pas de bouton qui ne marche pas.
- `expect/actual` seulement quand une interface injectée ne convient pas (par ex. un composable : `expect fun BackHandler(...)`, ou
  `expect fun rememberGoogleMapAvailable()`).
- `App(storage, location, actions, contacts)` est le seul point d'entrée : Android l'appelle depuis `MainActivity.setContent`, iOS depuis
  `ComposeUIViewController { App(...) }` (`fun MainViewController()` appelée par `ContentView.swift`).

### Lancer un sélecteur système depuis une fonction `suspend`

`ActivityResultContracts` doivent être enregistrés dans l'**Activity**. On les enveloppe dans des lambdas `suspend` passées à
`AndroidPlatformActions` :

```kotlin
private var pendingOpen: CompletableDeferred<Uri?>? = null
private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingOpen?.complete(it) }
// …
pickOpenUri = { CompletableDeferred<Uri?>().also { pendingOpen = it; openLauncher.launch(arrayOf("*/*")) }.await() }
```

Même schéma pour : enregistrer un fichier (`CreateDocument`), choisir des photos (`PickMultipleVisualMedia`), demander une permission
(`RequestPermission`), le sélecteur de contacts, la confirmation de suppression du système (`StartIntentSenderForResult`).

## 2. Données : JSON dans des fichiers

```kotlin
private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }
val dives = mutableStateListOf<Dive>()
fun saveDive(d: Dive) { /* remplace ou ajoute, puis */ storage.write("dives.json", json.encodeToString(ListSerializer(Dive.serializer()), dives.toList())) }
```

- Un champ **ajouté plus tard** a toujours une valeur par défaut : les anciens fichiers restent lisibles. Un champ obsolète est conservé
  (« lu mais plus écrit ») le temps de migrer.
- `Settings` dans `settings.json` ; `encodeDefaults` désactivé → un réglage à sa valeur par défaut n'est pas écrit.
- Sauvegarde/restauration : un `BackupCodec` (format versionné + date) ; **les binaires (photos) n'en font pas partie** : le dire.
- Identifiants : `newId()` = max(dernier + 1, horloge en ms).

## 3. Navigation

```kotlin
sealed interface Screen { data object Home : Screen; data class DiveDetail(val id: Long) : Screen; … }
val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
fun push(s: Screen) { stack.add(s) }; fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
fun goTab(s: Screen) { stack.clear(); stack.add(s) }
BackHandler(enabled = stack.size > 1) { pop() }
```

- Positions de défilement des listes **hissées** au-dessus (`rememberLazyListState()` dans `AppContent`) : on retrouve la liste comme
  on l'a laissée.
- Barre d'onglets Material 3 : **7 onglets maximum**, étiquettes en 10 sp et marge latérale de 4 dp, sinon elles sont rognées.
- Glissé d'une fiche à l'autre : `Modifier.swipeNeighbours(onRight, onLeft)` ; réserver le milieu des bords à l'appli
  (`systemGestureExclusionRects`) pour que le geste « retour » du système ne le vole pas.
- Transition d'écran : animation **d'entrée seulement** (fondu + glissement de 36 dp ; 240 ms) ; l'ancien écran disparaît aussitôt.
  Garder l'ancien écran le temps de l'animation oblige à le recomposer : un `if (item == null) pop()` s'exécuterait deux fois.
- Fiche supprimée pendant qu'elle est affichée : `if (dive == null) pop()` — mais pas d'effet de bord ailleurs que dans cette garde.

## 4. Thème, couleurs, pictogrammes

- `SubAtlasTheme(mode, strings, …)` expose des `CompositionLocal` (`LocalStrings`, réglages de graphiques…). Palette nommée (`Ocean`,
  `Lagoon`, `Abyss`), clair/sombre/système, thème **sombre par défaut** pour un logo sombre.
- **Pictogrammes** : une `enum class Picto` + `Picto.shapes()` qui donne des tracés SVG (grille 24 × 24, trait 1,8, bouts arrondis),
  lus par `androidx.compose.ui.graphics.vector.PathParser`, mis en cache. `Pictogram(icon, size, tint, filled)` et
  `PictoText(texte, icon)` (pictogramme dans le texte via `InlineTextContent` : `androidx.compose.foundation.text`). Aucun fichier
  image : ils suivent le thème et restent nets à toute taille.
- Dégradés : si le fond peut être clair ou sombre, **deux jeux de couleurs** (en thème sombre, l'« abysse » se confond avec le fond).
  Les aplats d'abord, les courbes par-dessus.
- Listes : états vides illustrés (`EmptyState(icon, message)` : le message est coupé à son premier saut de ligne, titre + explication) ;
  titres de mois collants (`stickyHeader` est un **membre** de `LazyListScope`, sans import, à annoter
  `@OptIn(ExperimentalFoundationApi::class)`).

## 5. Textes FR / EN

```kotlin
interface Strings { val ok: String; fun diveCount(n: Int): String; fun monthYear(date: LocalDate): String; … }
object StringsFr : Strings { override fun diveCount(n: Int) = "$n plongée${if (n > 1) "s" else ""}" … }
object StringsEn : Strings { … }
val s = LocalStrings.current          // dans tous les écrans
```

Pluriels et accords **dans la fonction** (`if (n > 1)` en français, `!= 1` en anglais). Les durées s'écrivent en toutes lettres dans
l'interface principale (« 5 jours 10 heures 13 minutes »). Tutoiement/vouvoiement : choisir une fois et s'y tenir.

## 6. Fichiers, photos, partage

- **Choisir des photos** : `PickMultipleVisualMedia` (le sélecteur du système : **aucune permission**). Réduire tout de suite :
  décoder directement réduit (`inSampleSize`), appliquer l'orientation EXIF, recompresser en JPEG 1600 px (qualité 85) + vignette 400 px
  (80). Les métadonnées, **GPS compris**, disparaissent. Traiter les photos **une à une** (mémoire).
- **Stocker** : `files/photos/<id>.jpg` et `<id>_t.jpg` (privés) ; le modèle garde `DivePhoto(id, width, height, source)`.
  Cache mémoire des images décodées **borné en octets** (largeur × hauteur × 4), pas en nombre.
- **Supprimer l'original dans la galerie** : garder l'URI du sélecteur + `takePersistableUriPermission`, puis
  `MediaStore.createDeleteRequest(resolver, listOf(mediaUri))` (Android 11+) : **le système demande confirmation**. L'identifiant du
  sélecteur (`content://media/picker/0/…/media/<id>`) est l'identifiant MediaStore. Refus ou impossible → ne rien supprimer.
  Distinguer deux actions pour l'utilisateur : « retirer de la fiche » (l'original reste) et « supprimer du téléphone ».
- **Partager un fichier** : écrire dans `cacheDir/shared/`, `FileProvider.getUriForFile`, `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` +
  `ClipData`, `Intent.createChooser`.
- **Image de partage d'un écran** : `rememberGraphicsLayer()` ; sur le composable à capturer :
  `Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }` ; puis `layer.toImageBitmap()`
  (suspend), encodé en PNG par un `expect fun ImageBitmap.toPngBytes()` (`Bitmap.compress` sur Android, Skia sur iOS). Les coins arrondis
  de l'aperçu se mettent **hors** du composable capturé (l'image enregistrée garde des coins carrés).

## 7. Cartes

- **Plan OpenStreetMap dessiné en commun** (aucune bibliothèque) : projection de Mercator en coordonnées normalisées 0–1, tuiles de
  256 dp, cache de tuiles décodées borné, 2 téléchargements à la fois, `User-Agent` identifié, mention © contributeurs OpenStreetMap.
  **Fond embarqué hors ligne** : les terres de Natural Earth (domaine public) converties en binaire compact par un script.
  Ni OpenStreetMap ni Google n'autorisent d'enregistrer leurs tuiles : ne pas essayer de les mettre en cache pour le hors-ligne.
- **Vue satellite Google** (Android, option) : maps-compose, `MapType.HYBRID`, clé dans `local.properties` → `manifestPlaceholders`,
  repli automatique sur le plan si la carte ne se charge pas en 15 s (`onMapLoaded`). Les **mêmes** repères et le **même** regroupement
  (composantes connexes, rayon 44 dp, recalcul par quart de niveau de zoom) sur les deux cartes. `MarkerComposable(vararg keys: Any, …)` :
  les clés ne peuvent pas être nulles.

## 8. iOS sans Mac

Écrire le code iOS **minimal**, en s'appuyant sur les valeurs par défaut des interfaces. Tout ce qui est écrit à l'aveugle (Kotlin/Native :
`UIActivityViewController`, `NSData`, `PHPicker`…) peut casser la première compilation : le **lister dans le README** comme « non
compilé », ne pas l'écrire quand la fonction peut simplement être indisponible sur iOS. Le projet Xcode se crée sur Mac (voir le README
du dépôt : script de phase « Run Script » `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, *Framework Search Paths*, `-framework Shared`,
Info.plist pour la position/les contacts/le schéma d'URL).
