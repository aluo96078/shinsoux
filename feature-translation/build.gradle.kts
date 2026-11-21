import dev.shinsou.build.HostPlatform

// R5-1 (docs/architecture/0006 §4.2): translation (reader translation, offline engine, providers)
// and manga (on-device manga translation). Owns the iOS cinterops translationCore, mangaOnnx and
// mangaOpenCv (src/nativeInterop) with the libraries behind them, and the Chinese script table
// (a Compose resource). Depends on the data, domain and core modules.
plugins {
    id("shinsou.kmp-library")
    id("shinsou.native-interop")
    id("shinsou.testing")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources {
    packageOfResClass = "dev.aluo.shinsoux.translation.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val jvmCommonMain by getting {
            dependencies {
                // ADR-0005: Android and desktop share one implementation over the same Java APIs
                // (ai.onnxruntime, org.opencv); each platform below supplies the binaries.
                compileOnly(libs.onnxruntime.jvm)
                compileOnly(libs.opencv.bytedeco)
            }
        }
        iosMain.dependencies {
            // The manga benchmark (IosMangaBenchmark) keeps its page store in memory.
            implementation(libs.sqldelight.native.driver)
        }
        androidMain.dependencies {
            implementation(libs.onnxruntime.android)
            implementation(libs.opencv.android)
        }
        val desktopMain by getting {
            dependencies {
                val bytedecoPlatform = HostPlatform.bytedeco
                implementation(libs.jna)
                implementation(libs.onnxruntime.jvm)
                implementation("org.bytedeco:opencv:${libs.versions.opencv.bytedeco.get()}") { exclude(group = "org.bytedeco", module = "numpy") }
                implementation("org.bytedeco:opencv:${libs.versions.opencv.bytedeco.get()}:$bytedecoPlatform")
                implementation("org.bytedeco:openblas:${libs.versions.openblas.bytedeco.get()}:$bytedecoPlatform")
                implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$bytedecoPlatform")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
