plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.r1.hermes"
    compileSdk = 35

    defaultConfig {
        // A separate applicationId so this installs alongside :app rather than
        // replacing it, and shows up as its own launcher entry.
        applicationId = "com.r1.hermes"
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
        abortOnError = false
    }
}

dependencies {
    // Motor + Camera2 plumbing, shared with :app.
    implementation(project(":core"))

    // Android ships no WebSocket client, and the gateway is WS-only. OkHttp
    // pulls in okio + kotlin-stdlib and nothing from AndroidX, so the
    // dependency-free UI stack and android.useAndroidX=false both stand.
    // JSON stays on the platform's org.json — no serialization library.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
