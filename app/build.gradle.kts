plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.opencodemobile"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.opencodemobile"
        minSdk = 26
        // 28: permite ejecutar binarios (PRoot) igual que Termux. Solo instalación manual (APK).
        targetSdk = 28
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.8.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
