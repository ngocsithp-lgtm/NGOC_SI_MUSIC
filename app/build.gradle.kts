plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ngocsi.music"
    compileSdk = 36

    val youtubeApiKey = System.getenv("YOUTUBE_API_KEY").orEmpty()
    val driveApiKey = System.getenv("DRIVE_API_KEY").orEmpty()
    // Firebase client options are injected from GitHub Actions secrets, not committed files.
    // The Gemini API key is server-only and must never be added to Android BuildConfig.
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
        versionCode = 58
        versionName = "5.38"
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")
        buildConfigField("String", "DRIVE_API_KEY", "\"$driveApiKey\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"$firebaseSenderId\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseProdAppId\"")
    }

    buildTypes {
        getByName("debug") {
            // Keep preview builds installable alongside the production NGỌC SĨ MUSIC app.
            applicationIdSuffix = ".aipreview"
            // Debug uses a separately registered Firebase Android app when configured.
            buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseDebugAppId\"")
            if (ciKeystorePath.isNotBlank() && ciStorePassword.isNotBlank() && ciKeyAlias.isNotBlank() && ciKeyPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("ciStable")
            }
        }
        getByName("release") {
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
    implementation(platform("com.google.firebase:firebase-bom:35.0.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
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
