plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val devKeystorePath = System.getenv("OMNICORE_DEV_KEYSTORE")

android {
    namespace = "com.omnicore.emulator"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.omnicore.emulator"
        minSdk = 26
        targetSdk = 36
        versionCode = 48
        versionName = "0.11.6-ps1-input2-r2"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // DEV-ONLY signing. CI injects the public development keystore so
        // sideloaded device-test builds can update in-place across runners.
        // Production / Play builds must use a private production key instead.
        if (!devKeystorePath.isNullOrBlank()) {
            create("development") {
                storeFile = file(devKeystorePath)
                storePassword = System.getenv("OMNICORE_DEV_STORE_PASSWORD")
                keyAlias = System.getenv("OMNICORE_DEV_KEY_ALIAS")
                keyPassword = System.getenv("OMNICORE_DEV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!devKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("development")
            }
        }
        getByName("release") {
            // Device-test builds use release runtime characteristics so UI
            // performance measurements are not distorted by a debuggable APK.
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!devKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("development")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)
}
