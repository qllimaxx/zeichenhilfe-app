plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.benni.zeichenhilfe"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.benni.zeichenhilfe"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.6"
    }

    // KOMPROMISS FÜR DIE PROBE: fester Debug-Schlüssel im (öffentlichen) Repository, damit sich
    // jede neue APK über die alte installieren lässt. Für später: Schlüssel als GitHub-Secret.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.ar:core:1.46.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
