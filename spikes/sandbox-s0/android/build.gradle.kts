// Standalone S0 spike: versions mirror ../../../gradle/libs.versions.toml (agp 8.11.1, kotlin 2.4.0)
// but the app's build is untouched. minSdk is 29 per ADR-0002 D2.
plugins {
    id("com.android.library") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.4.0"
    id("com.android.application") version "8.11.1" apply false
}

android {
    namespace = "dev.shinsou.sandbox"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_PLATFORM=android-29" } }
    }
    buildFeatures { aidl = true }
    externalNativeBuild { cmake { path = file("CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
