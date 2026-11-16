plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.nise.lezyne"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.nise.lezyne"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-test"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
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
