plugins {
    id("com.android.application")
}

// Release signing is supplied by CI (see .github/workflows/build-apk.yml).
// Without SHIFTLIT_KEYSTORE the release variant builds unsigned, which is fine
// for local experiments but must never be published.
val releaseKeystore = System.getenv("SHIFTLIT_KEYSTORE")

android {
    namespace = "app.shiftlit.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.shiftlit.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = 10903
        versionName = "1.9.3"
    }

    signingConfigs {
        // Public, checked-in debug key. Without it every CI runner generates
        // its own ~/.android/debug.keystore, so a new debug APK can't update
        // the previous one. This key is intentionally not secret.
        create("debugStable") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SHIFTLIT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHIFTLIT_KEY_ALIAS")
                keyPassword = System.getenv("SHIFTLIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debugStable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // FileProvider for handing the downloaded update APK to the installer.
    implementation("androidx.core:core:1.13.1")

    // Test-only. The parser is plain Kotlin (see Bounds in models.kt) so its
    // decisions can be replayed offline against captured node dumps.
    testImplementation("junit:junit:4.13.2")
}
