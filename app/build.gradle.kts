plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.nise.lezyne"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.nise.lezyne"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
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
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.9")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
