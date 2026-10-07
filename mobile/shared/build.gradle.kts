import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}

kotlin {
    android {
        namespace = "dev.pocketask.shared"
        compileSdk = 37
        minSdk = 31
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "PocketAsk"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("dev.chrisbanes.haze:haze:2.0.1")
            implementation("dev.chrisbanes.haze:haze-glass:2.0.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("com.squareup.okio:okio:3.16.4")
            implementation("io.ktor:ktor-client-core:3.5.2")
            implementation("app.cash.sqldelight:runtime:2.1.0")
            implementation("io.coil-kt.coil3:coil-compose:3.6.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.compose.ui:ui-test:1.12.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.1.0")
            implementation("io.ktor:ktor-client-okhttp:3.5.2")
        }
        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:2.1.0")
            implementation("io.ktor:ktor-client-darwin:3.5.2")
        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
            implementation("io.ktor:ktor-client-okhttp:3.5.2")
        }
        jvmTest.dependencies {
            implementation("io.ktor:ktor-client-mock:3.5.2")
            implementation(compose.desktop.currentOs)
        }
    }
}

sqldelight { databases { create("AppDatabase") { packageName.set("dev.pocketask.db") } } }
