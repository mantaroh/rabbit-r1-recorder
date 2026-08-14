plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.r1.audioprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.r1.audioprobe"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint { abortOnError = false }
}

dependencies {
    // Still nothing third-party: the audio path measures the platform, so
    // nothing should sit between the probe and AudioRecord/ConnectivityManager.
    // :core is our own, and holds the two things that talk to this particular
    // hardware — the motorised arm and a headless Camera2 capture.
    implementation(project(":core"))
}
