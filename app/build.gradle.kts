plugins {
    id("com.android.application")
}

android {
    namespace = "it.smartplugmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.smartplugmonitor"
        minSdk = 25          // Android 7.1.1
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Letti da variabili d'ambiente (impostate dalla GitHub
            // Action tramite i secrets del repository) — nessuna
            // password scritta qui o salvata nel repository.
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Firma con la release key solo se le variabili
            // d'ambiente sono presenti (build locale/CI configurata);
            // altrimenti Gradle userebbe la firma di debug di
            // fallback, quindi lo segnaliamo esplicitamente invece di
            // fallire in modo poco chiaro.
            if (System.getenv("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
