plugins {
    id("com.android.application")
}

android {
    namespace = "it.smartplugmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.smartplugmonitor"
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
