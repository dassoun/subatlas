plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "com.sensitivemobility.subatlas.shared"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
        // Tests unitaires du code commun, executes sur l'ordinateur (JVM) : ./gradlew :shared:testAndroidHostTest
        withHostTest { }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
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
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // Carte Google (vue satellite) ; la clé de l'API est fournie par l'application (voir androidApp).
            implementation(libs.maps.compose)
        }
    }
}

compose.resources {
    packageOfResClass = "com.sensitivemobility.subatlas.shared.resources"
    publicResClass = false
}
