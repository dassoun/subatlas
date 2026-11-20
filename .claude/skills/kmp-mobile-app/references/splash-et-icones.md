# Écran de démarrage (splash) et icônes

## Le problème

Depuis Android 12, **le système affiche son propre écran de démarrage** : un fond uni et une icône, découpée dans un **disque**.
On ne peut pas y mettre un logo complet avec son nom. On ne peut pas non plus le supprimer. La solution qui marche : deux écrans
qui se relaient **sans que rien ne bouge** — le système montre le symbole seul, l'appli prend le relais avec le même fond et le
même symbole **au même endroit**, et fait apparaître le nom en fondu.

## 1. Écran de démarrage système

1. Dépendance : `androidx.core:core-splashscreen` dans `androidApp`.
2. `MainActivity.onCreate` : `installSplashScreen()` **avant** `super.onCreate(...)`.
3. Thème de démarrage (voir `gradle-et-versions.md`) : parent `Theme.SplashScreen`, avec
   `windowSplashScreenBackground` (la couleur du logo), `windowSplashScreenAnimatedIcon` (un dessin vectoriel) et
   `postSplashScreenTheme` (le thème normal de l'appli). Le manifeste applique ce thème **à l'activité** ; l'application garde
   le thème normal.
4. Le thème normal met la **même couleur** en `android:windowBackground` : sans cela, un éclair blanc apparaît entre le démarrage
   et le premier dessin de Compose.
5. L'icône (`drawable/ic_splash_mark.xml`) est un **VectorDrawable de 288 dp** (`viewportWidth/Height = 288`). Android la découpe
   dans un cercle : tout ce qui compte doit tenir dans un disque de **144 dp de diamètre au plus** (les deux tiers de 192 dp, soit la
   zone sûre). Un logo carré ou large est coupé. On utilise donc **le symbole seul** (anneau, pictogramme), jamais le texte.
   Pas de dégradé fantaisiste : `aapt:attr` + `<gradient>` fonctionne dans un vector, c'est ce que génère le script.

## 2. Écran de démarrage Compose

Un composable `SplashScreen()` posé **au-dessus** de l'appli, visible 1,5 s :

```kotlin
private const val SPLASH_DURATION_MS = 1500L

@Composable
fun App(...) {
    var splashVisible by rememberSaveable { mutableStateOf(true) }     // survit à la rotation : pas de second splash
    LaunchedEffect(Unit) { delay(SPLASH_DURATION_MS); splashVisible = false }
    Box(Modifier.fillMaxSize()) {
        AppContent(...)
        AnimatedVisibility(visible = splashVisible, enter = EnterTransition.None, exit = fadeOut(tween(400))) {
            SplashScreen()
        }
    }
}

@Composable
fun SplashScreen() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val wordmarkAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(450))
    Box(
        Modifier.fillMaxSize().background(SplashBackground)       // la MÊME couleur que le thème système
            .pointerInput(Unit) {},                                // absorbe les appuis : ils ne traversent pas jusqu'à l'appli
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(LogoSize).offset(y = LogoOffsetY)) {
            Image(painterResource(Res.drawable.logo_symbol), "Nom de l'appli", Modifier.fillMaxSize())
            Image(painterResource(Res.drawable.logo_wordmark), null, Modifier.fillMaxSize().alpha(wordmarkAlpha))
        }
    }
}
```

`EnterTransition.None` : l'écran est déjà là au premier dessin (il continue le système), seule la sortie s'anime.

### La géométrie, ce qui fait que le symbole « ne bouge pas »

Le logo source est dessiné dans un cadre de 1024 unités ; l'anneau a un rayon `ring_r` et un centre `(ring_cx, ring_cy)`.

- Dans `ic_splash_mark.xml`, on met l'anneau à un diamètre cible (150 dp dans SubAtlas) :
  `scale = diamètre_cible / (2 × ring_r)`, puis on **centre l'anneau** dans le cadre de 288 dp :
  `translate = 144 − centre × scale`.
- Dans Compose, le logo complet est affiché à la **même échelle** : `LogoSize = 1024 × scale` (374,6 dp pour 0,366), et décalé de
  `LogoOffsetY = (512 − ring_cy) × scale` pour que le **centre de l'anneau** tombe au centre de l'écran, comme l'icône système.
- Le script imprime ces deux valeurs (« taille du cadre du logo », « décalage vertical »). **Ne les calcule pas à la main.**

Vérification : lancer l'appli, faire des captures à ~300 ms d'intervalle et superposer deux images (le symbole ne doit pas bouger d'un
pixel entre l'écran système et l'écran Compose).

## 3. Le logo : SVG → dessins vectoriels

Garder le logo source dans `design/*.svg` et le convertir par script (`tools/svg_to_vector.py`, Python, bibliothèque standard) :

- `shared/src/commonMain/composeResources/drawable/logo_symbol.xml` et `logo_wordmark.xml` (1024 × 1024, fond transparent) :
  le symbole et le nom **séparés**, pour animer l'apparition du nom ;
- `androidApp/src/main/res/drawable/ic_splash_mark.xml` : le symbole seul, cadré pour le système.

Le script lit les chemins du SVG (arcs, courbes, cercles) et les réécrit en `<path android:pathData>` avec les dégradés en
`aapt:attr`. Un SVG simple (quelques chemins et cercles) se convertit sans perte ; un SVG exporté d'un outil complexe doit d'abord
être simplifié (aplatir les transformations, convertir le texte en chemins). Les ressources Compose se référencent par
`Res.drawable.nom` (paquet défini par `compose.resources { packageOfResClass = … }`).

## 4. Icône de l'appli (adaptative, thématique)

Une seule image source (`tools/icon_source.webp`, carrée, fond uni) produit toutes les icônes avec `tools/make_icons.py`
(Python + Pillow) :

| Sortie | Rôle |
|---|---|
| `mipmap-*/ic_launcher.png`, `ic_launcher_round.png` | icônes classiques (48 dp) |
| `mipmap-*/ic_launcher_foreground.png` | premier plan adaptatif (108 dp) |
| `mipmap-*/ic_launcher_monochrome.png` | icône thématique d'Android 13+ |
| `mipmap-anydpi-v26/ic_launcher(.|_round).xml` | définition adaptative : `background` (couleur) + `foreground` + `monochrome` |
| `values/ic_launcher_background.xml` | couleur de fond |
| `tools/out/play_store_icon_512.png` | icône de la fiche Google Play |

Règles : le logo occupe au plus **66/108 (61 %)** du cadre adaptatif (zone toujours visible, quelle que soit la forme choisie par le
lanceur) et ~86 % de l'icône classique ; la couleur de fond est **échantillonnée dans le coin** de l'image source. Les icônes d'une
**société** (splash et logo éditeur) sont séparées de celle de l'**appli** : deux scripts, deux sources.

## Pièges

- Symbole trop grand dans l'icône système → coupé par le disque. Toujours garder la marge.
- Couleurs de fond légèrement différentes entre le thème système, `windowBackground` et le splash Compose → un « saut » visible. Une
  seule constante de couleur, recopiée à trois endroits (XML ×2, Kotlin ×1) et commentée comme telle.
- `installSplashScreen()` après `super.onCreate` → le thème de démarrage reste affiché.
- Capture d'écran trop tôt : le démarrage dure ~1,9 s (1,5 s + 0,4 s de fondu) ; **attendre ≥ 9 s** avant de toucher l'écran dans
  un script, sinon les appuis tombent sur le splash.
