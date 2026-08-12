plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.r1.camerawrapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.r1.camerawrapper"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    lint {
        // This wrapper has no launcher icon or string resources on purpose.
        abortOnError = false
    }
}

dependencies {
    // Motor + Camera2 plumbing, shared with :hermes.
    implementation(project(":core"))
    // No AndroidX dependency is required by this wrapper.
}
