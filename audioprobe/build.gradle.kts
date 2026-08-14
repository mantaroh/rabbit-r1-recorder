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

    /**
     * The chat client is compiled from :hermes's own sources rather than
     * copied here.
     *
     * Copying would fork it: two versions of a 900-line Activity that have to
     * be fixed twice and will eventually differ in some way nobody notices.
     * Sharing the directory keeps one implementation while both apps exist,
     * which is the point of keeping :hermes around during the move — if the
     * merged app breaks, the standalone one still works, and it is the same
     * code either way.
     *
     * Safe to do because :hermes carries no resources and references no R
     * class; it is plain Kotlin against the platform and OkHttp.
     */
    sourceSets["main"].java.srcDirs("src/main/java", "../hermes/src/main/java")
}

dependencies {
    // Nothing third-party on the audio path: it measures the platform, so
    // nothing should sit between the probe and AudioRecord/ConnectivityManager.
    // :core is our own, and holds the two things that talk to this particular
    // hardware — the motorised arm and a headless Camera2 capture.
    implementation(project(":core"))

    // The chat client's gateway is WebSocket-only and Android ships no client.
    // Same dependency :hermes already carries, for the same reason: OkHttp
    // pulls in okio and kotlin-stdlib and nothing from AndroidX, so
    // android.useAndroidX=false still stands.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
