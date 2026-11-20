# Tester sur émulateur, installer sur un téléphone, éviter les pièges

## 1. Compiler et installer (Windows)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"     # sans JAVA_HOME, Gradle ne démarre pas
$env:ANDROID_SERIAL = "emulator-5554"                              # INDISPENSABLE si un téléphone est aussi branché
Set-Location <dépôt>
.\gradlew.bat :androidApp:installDebug --console=plain > "$env:TEMP\build.txt" 2>&1
"exit=$LASTEXITCODE"
Select-String -Path "$env:TEMP\build.txt" -Pattern "^e: |Unresolved|Location:|FAILED|BUILD|Installed"
```

- `installDebug` installe sur **tous** les appareils branchés, téléphone compris : `ANDROID_SERIAL` l'évite.
- **Ne jamais** enchaîner `gradlew … | Select-Object -First N` : la sortie coupée fait tomber le shell (code 255) alors que la compilation
  s'est bien terminée. Rediriger vers un fichier, puis chercher dedans.
- Les erreurs de compilation Kotlin tiennent dans `^e: ` et `Unresolved` ; un message générique « Kotlin compiler error » s'explique en
  lisant le fichier de sortie complet (la ligne utile est quelques lignes plus bas).
- Une compilation incrémentale prend 15–40 s ; la première, plus.

## 2. Boucle de vérification visuelle (écran 1080 × 2400)

```bash
ADB="/c/Users/<moi>/AppData/Local/Android/Sdk/platform-tools/adb.exe"; E="$ADB -s emulator-5554"
$E shell am force-stop <paquet>
$E shell monkey -p <paquet> -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 9                                           # fin du démarrage (1,5 s de splash + fondu) + chargement des données
$E shell input tap 237 2230                       # un onglet de la barre du bas
$E exec-out screencap -p > "$SCR/capture.png"     # puis lire l'image (outil Read)
$E shell input swipe 540 2000 540 700 300         # défilement
```

- Les coordonnées viennent **des captures** (1080 × 2400), pas de `uiautomator dump`, peu fiable avec Compose.
- Pour juger d'un défilement ou d'une animation, prendre 3–4 captures à intervalles courts et en faire un montage (Pillow) ; une capture
  prend ~0,5 s, on ne fige pas une animation de 240 ms, on voit son avancement.
- Tester **aussi** le thème clair (réglages de l'appli) et la langue anglaise si elle existe.

## 3. Pièges d'environnement (Windows)

| Piège | Remède |
|---|---|
| PowerShell 5.1 : `adb exec-out screencap -p > fichier` **corrompt** le PNG (redirection en UTF-16) | faire les captures depuis **Git Bash** (`> fichier.png`) |
| Git Bash transforme `/sdcard/…` en `E:/Programmes/Git/sdcard/…` | `MSYS_NO_PATHCONV=1 adb …`, ou lancer ce `adb` depuis PowerShell |
| `adb push` depuis Git Bash avec un chemin local `/c/…` + `MSYS_NO_PATHCONV=1` : le chemin local n'est plus converti | pousser depuis PowerShell avec un chemin Windows |
| `adb` sans `-s` quand deux appareils sont branchés : « more than one device » | toujours `adb -s <série>` |
| Python : `f"{base}\u{i}.png"` → erreur d'échappement `\u` dans un chemin Windows | chemins avec `/`, concaténation de chaînes |
| Le texte d'un outil contenant des paires de substituts (`\uD83D…`) devient de vrais emoji dans le script | construire les antislashs avec `chr(92)` |
| Un `heredoc` Bash contenant des guillemets simples + un appel `powershell -Command '…'` : « unexpected EOF » | écrire le script dans un **fichier** (outil d'écriture) et l'exécuter |
| `ls` PowerShell de la sortie d'`adb shell` : codes de sortie parasites | ignorer si le contenu est bon |

## 4. Données de l'utilisateur : ne rien abîmer

Les fichiers de l'appli sont lisibles par `run-as` (APK de debug) :

```bash
$E exec-out run-as <paquet> cat files/dives.json > "$SCR/bk_dives.json"       # sauvegarde binaire sûre
$E exec-out run-as <paquet> md5sum files/dives.json                            # empreinte avant
# … tests …
# restauration (PowerShell) :
adb -s emulator-5554 push bk_dives.json /data/local/tmp/bk.json
adb -s emulator-5554 shell "run-as <paquet> sh -c 'cat /data/local/tmp/bk.json > files/dives.json; rm -rf files/photos'"
adb -s emulator-5554 shell "run-as <paquet> md5sum files/dives.json"           # doit être identique
```

- Pour voir un **état vide**, remplacer les fichiers par `[]`, capturer, puis restaurer et vérifier les empreintes.
- Un test qui touche aux préférences (réglages cochés…) : relire `settings.json` à la fin ; un réglage resté coché par accident est
  facile à rater.
- Fichiers de test (photos) : les générer (Pillow), les pousser dans `/sdcard/Pictures`, déclencher
  `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/x.jpg`, puis les **supprimer** à la fin.
- Tester les cas limites d'un format : image **EXIF orientation 6**, portrait et paysage, 4000 × 3000.

## 5. Installer sur le téléphone de l'utilisateur

**Seulement une fois le développement validé** (et jamais sans que l'utilisateur l'ait demandé).

```powershell
$apk = "<dépôt>\androidApp\build\outputs\apk\debug\androidApp-debug.apk"
adb -s <série> install -r $apk                                    # mise à jour : les données du téléphone sont conservées
adb -s <série> push $apk "/sdcard/Download/<Appli>-<version>-debug.apk"     # copie dans Téléchargements, si demandé
adb -s <série> shell "dumpsys package <paquet> | grep -E 'versionName|lastUpdateTime'"
```

- Avant : `dumpsys package` pour savoir si l'appli y est déjà et de quand elle date.
- Échec de signature (« INSTALL_FAILED_UPDATE_INCOMPATIBLE ») : **ne jamais désinstaller sans demander** (cela efface ses données).
  Certains téléphones (Xiaomi) demandent d'activer « Installer via USB ».
- L'APK de debug embarque la clé d'API de `local.properties` : ne pas le publier.

## 6. Pièges de code rencontrés (à ne pas refaire)

- **Google Maps** plante avec une clé valide : manque `uses-library org.apache.http.legacy`. Une clé **invalide** n'empêche pas d'afficher les tuiles
  déjà en cache de l'appareil : tester le repli avec une zone jamais vue.
- **Regroupement de repères** glouton → résultat qui dépend de l'ordre et qui change avec le zoom : utiliser les **composantes connexes**
  (union-find) et un zoom arrondi vers le bas par quart.
- **Barre d'onglets** à 7 entrées : étiquettes rognées → 10 sp + marge de 4 dp.
- **Transitions** qui gardent l'ancien écran : double `pop()`. Animer l'entrée seulement.
- `LazyListScope.stickyHeader` : membre (pas d'import), `@OptIn(ExperimentalFoundationApi)`.
- `InlineTextContent` / `appendInlineContent` : paquet `androidx.compose.foundation.text`.
- `PagerState` : après un changement d'ordre de la liste, la page ne suit pas toute seule → `LaunchedEffect(liste, idSuivi) { pager.scrollToPage(index) }`.
- **Cache d'images** : le borner en octets décodés, pas en nombre d'images.
- **Plusieurs `@Composable` qui lisent `LocalXxx`** dans des `Dialog` : les locals sont bien hérités, mais pas l'état `remember` de l'appelant :
  passer l'état en paramètre.
- Un champ ajouté au modèle doit être recopié **partout où l'objet est reconstruit** (formulaire qui construit `Dive(...)` de zéro : sans
  `photos = existing.photos`, les photos disparaissaient à la modification) ; chercher les constructeurs, pas seulement les `.copy`.
- Les envois à un tiers (fichier de partage) **vident** les champs qui n'ont de sens que sur ce téléphone (identifiants, photos).
