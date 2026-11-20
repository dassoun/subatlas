# Fichiers Gradle, manifeste et thèmes (à adapter : `{{paquet}}`, `{{Appli}}`)

Remplace `{{paquet}}` par l'identifiant (`com.société.appli`) et `{{Appli}}` par le nom. Vérifie les dernières versions stables
avant de figer le catalogue ; celles-ci ont été éprouvées ensemble (novembre 2026).

## `gradle/libs.versions.toml`

```toml
[versions]
agp = "9.5.1"
kotlin = "2.4.21"
compose-multiplatform = "1.12.2"
coroutines = "1.11.0"
serialization = "1.11.0"
activity-compose = "1.13.0"
compose-material3 = "1.9.0"
core-splashscreen = "1.2.0"
datetime = "0.8.0"
# maps-compose = "9.0.1"   # seulement si l'appli a une carte Google

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity-compose" }
androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "core-splashscreen" }
compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "compose-multiplatform" }
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "compose-multiplatform" }
compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "compose-multiplatform" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "compose-material3" }

[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidKotlinMultiplatformLibrary = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

## `settings.gradle.kts`

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "{{Appli}}"
include(":shared")
include(":androidApp")
```

## `build.gradle.kts` (racine)

```kotlin
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}
```

## `gradle.properties`

```
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
android.useAndroidX=true
kotlin.native.ignoreDisabledTargets=true   # permet de compiler sous Windows sans Mac : les cibles iOS sont ignorées
```

## `shared/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "{{paquet}}.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }      // nécessaire pour les ressources Compose
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework { baseName = "Shared"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies { implementation(libs.androidx.activity.compose) }
    }
}

compose.resources {
    packageOfResClass = "{{paquet}}.shared.resources"   // les dessins s'importent de ce paquet : Res.drawable.logo_symbol
    publicResClass = false
}
```

## `androidApp/build.gradle.kts`

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Clé d'API (si besoin) : MAPS_API_KEY dans local.properties, jamais versionné. Lue par `providers` pour que le cache de
// configuration de Gradle suive ses changements ; sans clé, la fonction est simplement absente de l'appli.
val mapsApiKey: Provider<String> = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("MAPS_API_KEY", "").trim() }
    .orElse("")
androidComponents { onVariants { variant -> variant.manifestPlaceholders.put("MAPS_API_KEY", mapsApiKey) } }

android {
    namespace = "{{paquet}}"
    compileSdk = 37
    defaultConfig {
        applicationId = "{{paquet}}"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
}
```

## `androidApp/src/main/AndroidManifest.xml` (squelette)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Seulement les permissions dont l'appli a besoin -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="{{Appli}}"
        android:supportsRtl="true"
        android:theme="@style/Theme.{{Appli}}">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
            android:theme="@style/Theme.{{Appli}}.Starting">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Partage de fichiers (image, export) : le fichier du cache est exposé le temps de l'envoi -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

`configChanges` évite que la rotation recrée l'activité (sinon l'appli retombe sur l'accueil) ; `launchMode="singleTop"` pour les liens
reçus ; `res/xml/file_paths.xml` : `<paths><cache-path name="shared" path="shared/" /></paths>`.

Pour une **carte Google** : `<meta-data android:name="com.google.android.geo.API_KEY" android:value="${MAPS_API_KEY}" />` et
`<uses-library android:name="org.apache.http.legacy" android:required="false" />` (sans lui la carte plante avec
`NoClassDefFoundError org.apache.http.ProtocolVersion`). Pas de « Map ID » : la carte reste dans le palier gratuit. Restreindre la clé
(paquet + empreinte SHA-1, API « Maps SDK for Android » seule) dans la console Google Cloud.

## `androidApp/src/main/res/values/themes.xml`

```xml
<resources>
    <!-- Fond avant le premier dessin de l'appli : la couleur du logo (thème sombre par défaut). -->
    <style name="Theme.{{Appli}}" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">#08111C</item>
    </style>

    <!-- Écran de démarrage système : fond, symbole seul, puis retour au thème normal. -->
    <style name="Theme.{{Appli}}.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#08111C</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_mark</item>
        <item name="postSplashScreenTheme">@style/Theme.{{Appli}}</item>
    </style>
</resources>
```

## `MainActivity` minimale

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()               // DOIT précéder super.onCreate : bascule du thème de démarrage au thème normal
        super.onCreate(savedInstanceState)
        val storage = AndroidStorage(applicationContext)
        val actions = AndroidPlatformActions(this /* + lanceurs de sélecteurs, voir architecture.md */)
        setContent { App(storage, actions /* , location, contacts… */) }
    }
}
```

Build : `./gradlew :androidApp:assembleDebug` → `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Sous Windows, `JAVA_HOME` doit pointer sur le JDK d'Android Studio (`C:\Program Files\Android\Android Studio\jbr`).
