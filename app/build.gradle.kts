plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build output directory can be overridden with H6N_BUILD_DIR.
System.getenv("H6N_BUILD_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let {
    layout.buildDirectory.set(file(it))
}

android {
    namespace = "com.h6n.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.h6n.launcher"
        // The target head unit runs Android 8.1 and the launcher is installed
        // as the HOME app, so targetSdk is pinned to the platform version.
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "6.0.108"
    }

    signingConfigs {
        create("release") {
            // Keystore is never committed. Provide it through environment
            // variables: H6N_KEYSTORE / H6N_KEYSTORE_PASSWORD /
            // H6N_KEY_ALIAS / H6N_KEY_PASSWORD.
            val store = System.getenv("H6N_KEYSTORE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("H6N_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("H6N_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("H6N_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 keeps every class name (Manifest components, accessibility
            // config, crash stacks) and only renames members.
            // shrinkResources stays off on purpose: lane icons are resolved at
            // runtime through getIdentifier("lane_pdf_$icon").
            isMinifyEnabled = true
            isShrinkResources = false
            val store = System.getenv("H6N_KEYSTORE")
            if (!store.isNullOrBlank() && System.getenv("H6N_NO_SIGN") != "1") {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // Every screen is built in code: no XML layouts, no ViewBinding.
        viewBinding = false
    }
    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
