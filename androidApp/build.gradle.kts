import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Clé de l'API Google Maps : MAPS_API_KEY dans local.properties (fichier jamais versionné). Sans clé, la vue satellite
// est simplement absente de l'appli. Lu par `providers` pour que Gradle (cache de configuration) en suive les changements.
val mapsApiKey: Provider<String> = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("MAPS_API_KEY", "").trim() }
    .orElse("")

androidComponents {
    onVariants { variant -> variant.manifestPlaceholders.put("MAPS_API_KEY", mapsApiKey) }
}

android {
    namespace = "com.sensitivemobility.subatlas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sensitivemobility.subatlas"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
}
