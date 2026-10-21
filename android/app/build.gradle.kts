import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. The keystore and its passwords live outside the repository: point
// SCRY_KEYSTORE_PROPERTIES at a properties file (storeFile, storePassword, keyPassword), or put
// one at android/keystore.properties (git-ignored). The key alias is chosen with
// -PscryKey=upload (default, for the Play bundle) or -PscryKey=github (for the APKs on GitHub).
// Without a properties file the release build is simply left unsigned.
val signingProps = Properties().apply {
    val path = System.getenv("SCRY_KEYSTORE_PROPERTIES")
    val file = if (path != null) File(path) else rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.scry"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.scry"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = (project.findProperty("scryKey") as String?) ?: "upload"
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // One APK per CPU type for the GitHub release (the Play bundle splits by itself). The two types
    // must match the targets passed to gomobile bind.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Go tunnel core, built from ../../core with gomobile bind.
    implementation(files("../libs/core.aar"))

    // Background jobs for downloads now, and backup later.
    implementation("androidx.work:work-runtime:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
