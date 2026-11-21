// R5-1 (docs/architecture/0006 §4.2): plugin (extension repositories, trust, the plugin network,
// the sandboxed runtimes and their hosts). Owns sandbox-core on every platform but the desktop
// helpers the app packages: the iOS cinterops networkReceive and sandboxWasm (src/nativeInterop) with
// the WASM sandbox host behind them; on Android libshinsou_sandbox.so (src/androidMain/cpp), the AIDL
// interface and the manifest entries of the isolated-process sandbox, all merged into the app's APK.
// Also the Public Suffix List (psl/). Depends on the data, domain and core modules.
import dev.shinsou.build.HostPlatform

plugins {
    id("shinsou.kmp-library")
    id("shinsou.native-interop")
    id("shinsou.codegen")
    id("shinsou.testing")
    alias(libs.plugins.kotlinSerialization)
}

shinsouCodegen {
    publicSuffixData()
}

android {
    // ADR-0002 §4.3: ISandboxProcess, the isolated sandbox process's binder interface.
    buildFeatures.aidl = true
    // The sandbox's device tests (androidInstrumentedTest) run in this library's test APK, which
    // carries its native library, its isolated-process service and the zygote preload.
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // ADR-0002 §4.3: under the app's own compatibility policy (its target SDK), not the minimum's.
    testOptions.targetSdk = libs.versions.android.targetSdk.get().toInt()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-network"))
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(project(":data-repository"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val jvmCommonMain by getting {
            dependencies {
                // The plugin network's pinned-DNS OkHttp client (PlatformPluginNetwork.jvm.kt).
                implementation(libs.ktor.client.okhttp)
            }
        }
        // R4-5 (docs/architecture/0006): the in-process Rhino engine only serves tests and parity
        // checks; packages always run sandboxed, so the product build cannot reach it.
        val jvmCommonTest by getting {
            dependencies {
                implementation(libs.rhino)
                implementation(libs.jsoup)
            }
        }
        // ADR-0002 S2: the isolated-process sandbox on a device or emulator.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val desktopMain by getting {
            dependencies {
                // The browser-session transport drives JavaFX's WebView.
                val javafx = libs.versions.javafx.get()
                listOf("base", "graphics", "controls", "web", "swing").forEach { module ->
                    implementation("org.openjfx:javafx-$module:$javafx:${HostPlatform.javafx}")
                }
            }
        }
        val desktopTest by getting {
            dependencies {
                // The host's Skia runtime (what compose.desktop.currentOs brings the app).
                implementation("org.jetbrains.compose.desktop:desktop-jvm-${HostPlatform.composeDesktop}:${libs.versions.compose.get()}")
                implementation(libs.ktor.client.encoding)
                implementation(libs.libsodium.bindings)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

// The live WKWebView image test (SHINSOU_LIVE_BROWSER_IMAGE=1, macOS) needs the helper the app
// builds; Gradle runs this module's tests in feature-plugin/, so its path is passed explicitly.
val liveBrowserImage = providers.environmentVariable("SHINSOU_LIVE_BROWSER_IMAGE").orNull == "1"
if (liveBrowserImage && System.getProperty("os.name").lowercase().contains("mac")) {
    tasks.named<Test>("desktopTest") {
        dependsOn(":composeApp:compileMacOsWebChallengeHelper")
        systemProperty(
            "shinsou.webChallengeHelper",
            rootProject.layout.projectDirectory
                .file("composeApp/build/generated/desktopAppResources/macos/shinsou-web-challenge")
                .asFile.absolutePath,
        )
    }
}
