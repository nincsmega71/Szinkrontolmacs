plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hu.tamas.szinkrontolmacs"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.tamas.szinkrontolmacs"
        minSdk = 29          // Android 10: belső hang rögzítése (AudioPlaybackCapture)
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk {
            abiFilters += listOf("arm64-v8a")   // S24 Ultra – kisebb APK
        }
    }

    signingConfigs {
        create("sajat") {
            // Állandó kulcs, hogy a frissítések telepíthetők legyenek a régi fölé
            storeFile = file("../keystore/szinkrontolmacs.jks")
            storePassword = "szinkron123"
            keyAlias = "szinkron"
            keyPassword = "szinkron123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sajat")
        }
        debug {
            signingConfig = signingConfigs.getByName("sajat")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    // sherpa-onnx (Whisper beszédfelismerés + VAD), a GitHub Actions tölti le a libs mappába
    implementation(files("libs/sherpa-onnx-2.0.0.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
