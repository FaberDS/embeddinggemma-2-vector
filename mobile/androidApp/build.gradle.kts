plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.pocketask"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pocketask"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    sourceSets["main"].assets.srcDir("../third-party")
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation(project(":shared"))
    implementation("app.cash.sqldelight:android-driver:2.1.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
