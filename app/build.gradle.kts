import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    // id("io.objectbox") version "5.0.1"  // RAG vector database - disabled, using file-based storage
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"  // For JSON handling
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 19
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Nexa SDK token — read from local.properties (gitignored), never hardcoded
        val localProperties = Properties().also { props ->
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
        }
        buildConfigField("String", "NEXA_TOKEN", "\"${localProperties["NEXA_TOKEN"] ?: ""}\"")
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64") // arm64-v8a = real devices, x86_64 = emulator
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11  // Updated for Nexa SDK
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"  // Updated for Nexa SDK
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true  // Required for Nexa SDK native libs
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so"
            )
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // Arti (Tor in Rust) Android bridge - use published AAR with native libs
    implementation("info.guardianproject:arti-mobile-ex:1.2.3")

    // Google Play Services Location
    implementation(libs.gms.location)

    // ExifInterface for photo metadata
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Security preferences
    implementation(libs.androidx.security.crypto)

    // ============================================
    // AI/ML DEPENDENCIES (SafeGuardian)
    // ============================================

    // Nexa AI SDK (LLM inference)
    implementation("ai.nexa:core:0.0.22")

    // Sherpa-ONNX ASR (Speech Recognition - replaces VOSK)
    // Maven artifact with Android-specific packaging (includes JNI libraries)
    implementation("com.bihe0832.android:lib-sherpa-onnx:6.25.21")

    // ObjectBox Vector Database (RAG)
    implementation("io.objectbox:objectbox-kotlin:5.0.1")
    implementation("io.objectbox:objectbox-android:5.0.1")

    // Kotlin Serialization (JSON handling)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OkDownload (Model downloads - from nexa-sdk-examples)
    // Note: Add AAR files to libs/ directory
    // implementation(files("libs/okdownload-core.aar"))
    // implementation(files("libs/okdownload-sqlite.aar"))
    // implementation(files("libs/okdownload-okhttp.aar"))
    // implementation(files("libs/okdownload-ktx.aar"))

    // TTS is built-in Android (no dependency needed)

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
