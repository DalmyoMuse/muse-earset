import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing: the project's own key, from keystore.properties next to settings.gradle.kts
// (never commit or upload that file). Without it the build falls back to the debug key.
val ks = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "app.museearset.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.museearset.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        if (!ks.isEmpty) create("muse") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(if (ks.isEmpty) "debug" else "muse")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ks.isEmpty) "debug" else "muse")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // None: framework + org.json only, on purpose (a small public project stays auditable).
}
