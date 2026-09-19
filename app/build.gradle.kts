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
        val keystoreFile = System.getenv("SIGNING_KEY_FILE")
        val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("KEY_ALIAS")
        val keyPassword = System.getenv("KEY_PASSWORD")

        if (keystoreFile != null && keystorePassword != null &&
            keyAlias != null && keyPassword != null) {

            storeFile = file(keystoreFile)
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
        }
    }
}

buildTypes {
    debug {
        isMinifyEnabled = false
    }

    release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("release")
    }
}

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
