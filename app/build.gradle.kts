plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Quote environment-provided values safely for generated Java BuildConfig constants.
// Trim values and remove one accidental pair of surrounding quotes copied from JSON.
fun String.toBuildConfigLiteral(): String {
    val normalized = trim().removeSurrounding("\"")
    val escaped = normalized
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    return "\"$escaped\""
}

android {
    namespace = "com.ngocsi.music"
    compileSdk = 36

    val youtubeApiKey = System.getenv("YOUTUBE_API_KEY").orEmpty()
    val driveApiKey = System.getenv("DRIVE_API_KEY").orEmpty()
    // Firebase client settings are build-time configuration; no Gemini API key is embedded.
    val firebaseApiKey = System.getenv("FIREBASE_API_KEY").orEmpty()
    val firebaseProjectId = System.getenv("FIREBASE_PROJECT_ID").orEmpty()
    val firebaseSenderId = System.getenv("FIREBASE_SENDER_ID").orEmpty()
    val firebaseProdAppId = System.getenv("FIREBASE_APP_ID").orEmpty()
    val firebaseDebugAppId = System.getenv("FIREBASE_DEBUG_APP_ID").orEmpty()
    val ciKeystorePath = System.getenv("CI_KEYSTORE_PATH").orEmpty()
    val ciStorePassword = System.getenv("CI_KEYSTORE_PASSWORD").orEmpty()
    val ciKeyAlias = System.getenv("CI_KEY_ALIAS").orEmpty()
    val ciKeyPassword = System.getenv("CI_KEY_PASSWORD").orEmpty()

    signingConfigs {
        if (ciKeystorePath.isNotBlank() && ciStorePassword.isNotBlank() && ciKeyAlias.isNotBlank() && ciKeyPassword.isNotBlank()) {
            create("ciStable") {
                storeFile = file(ciKeystorePath)
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.ngocsi.music"
        minSdk = 26
        targetSdk = 36
        // Preview keeps the local Vietnamese ASR fallback for ARM64 phones; owner voice verification is disabled.
        // Stable production ABI settings are untouched on main.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        versionCode = 74
        versionName = "5.54"
        buildConfigField("String", "YOUTUBE_API_KEY", youtubeApiKey.toBuildConfigLiteral())
        buildConfigField("String", "DRIVE_API_KEY", driveApiKey.toBuildConfigLiteral())
        buildConfigField("String", "FIREBASE_API_KEY", firebaseApiKey.toBuildConfigLiteral())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseProjectId.toBuildConfigLiteral())
        buildConfigField("String", "FIREBASE_SENDER_ID", firebaseSenderId.toBuildConfigLiteral())
        buildConfigField("String", "FIREBASE_APP_ID", firebaseProdAppId.toBuildConfigLiteral())
    }

    buildTypes {
        getByName("debug") {
            // Never let an AI preview overwrite the installed production app.
            applicationIdSuffix = ".aipreview"
            buildConfigField("String", "FIREBASE_APP_ID", firebaseDebugAppId.toBuildConfigLiteral())
            if (ciKeystorePath.isNotBlank() && ciStorePassword.isNotBlank() && ciKeyAlias.isNotBlank() && ciKeyPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("ciStable")
            }
        }
        getByName("release") {
            applicationIdSuffix = ".aipreview"
            // Every variant on this branch is the preview package, never the production package.
            buildConfigField("String", "FIREBASE_APP_ID", firebaseDebugAppId.toBuildConfigLiteral())
            if (ciKeystorePath.isNotBlank() && ciStorePassword.isNotBlank() && ciKeyAlias.isNotBlank() && ciKeyPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("ciStable")
            }
            // Keep release behavior predictable until minification rules are verified.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Downloaded by the Preview-only GitHub Actions workflow before lint/build.
    // It supplies the local sherpa-onnx runtime for the Vietnamese offline ASR fallback.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Firebase AI Logic client; missing Firebase settings deliberately leave preview offline.
    implementation(platform("com.google.firebase:firebase-bom:35.0.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    // Debug-only App Check provider for sideloaded preview builds; token must be registered in Firebase Console.
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.4")
    implementation("androidx.media3:media3-session:1.9.4")
    implementation("androidx.media3:media3-ui:1.9.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google OAuth: required for Drive files/folders shared privately with the signed-in account.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
}
