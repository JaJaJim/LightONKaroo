plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

fun gitShortSha(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}

// Monotonic version code derived from the commit count. Requires full git
// history — CI must check out with fetch-depth: 0 (a shallow clone returns 1).
fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().toInt()
} catch (e: Exception) {
    1
}

android {
    namespace = "io.github.JaJaJim.lightonkaroo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.JaJaJim.lightonkaroo"
        minSdk = 23
        targetSdk = 34
        versionCode = 14
        versionName = "0.1.2-R2"
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nordic.ble.client)

    // ANT Android SDK - place ant-lib.aar in app/libs/
    // Download from: https://github.com/ant-wireless/ANT-Android-SDKs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
