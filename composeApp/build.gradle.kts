import dev.shinsou.build.HostPlatform

// R5-2 (docs/architecture/0006): the build logic is in build-logic's convention plugins:
//   shinsou.kmp               targets, source-set hierarchy, the iOS cinterops of src/nativeInterop
//   shinsou.ios-framework     the iOS framework ShinsouKit that iosApp/ links
//   shinsou.codegen           generated sources (here the Android icon)
//   shinsou.native-libraries  sandbox-core/translation-core fetches, desktop helpers, iOS libraries
//   shinsou.desktop-app       the Compose desktop application, installers and macOS signing
//   shinsou.android-app       the Android application, its signing and unit-test host resources
//   shinsou.quality           detekt (detektProduct) and Kover
//   shinsou.testing           test task inputs and system properties
// This script keeps the plugin list and the dependencies of each source set. The library modules
// (R5-1, docs/architecture/0006 §4.2) use shinsou.kmp-library; settings.gradle.kts lists them.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    id("shinsou.quality")
    id("shinsou.testing")
    id("shinsou.native-libraries")
    id("shinsou.codegen")
    id("shinsou.kmp")
    id("shinsou.ios-framework")
    id("shinsou.desktop-app")
    id("shinsou.android-app")
}

shinsouCodegen {
    androidAppIcon("ui/src/commonMain/composeResources/drawable/shinsou_icon.png")
}

// R5-1: the Compose resources (the app icon) are :ui's; this module has none.
compose.resources {
    generateResClass = never
}

// The Swift app registers Apple's translation bridge (IosSystemTranslationKt, AppleTranslationBridge).
shinsouIosFramework {
    export(":feature-translation")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-network"))
            implementation(project(":domain"))
            implementation(project(":data-persistence-api"))
            implementation(project(":data-content"))
            implementation(project(":data-repository"))
            implementation(project(":feature-sync"))
            implementation(project(":feature-library"))
            implementation(project(":feature-reader"))
            implementation(project(":feature-translation"))
            implementation(project(":feature-plugin"))
            implementation(project(":ui-core"))
            implementation(project(":ui-reader"))
            implementation(project(":ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.json.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.libsodium.bindings)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        val jvmCommonMain by getting {
            dependencies {
                implementation(libs.coil.network.ktor)
                implementation(libs.ktor.client.okhttp)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.biometric)
                implementation(libs.androidx.webkit)
                implementation(libs.androidx.work.runtime)
                implementation(libs.android.avif)
                // MainActivity installs a shared Coil network fetcher so Android image
                // requests use the same no-proxy Ktor client as source requests.
                implementation(libs.coil.network.ktor)
                implementation(libs.sqldelight.android.driver)
            }
        }

        // Device tests (ADR-0002 S2: the isolated-process sandbox on real hardware).
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }

        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }

        // The manga translation's inference and image libraries (ADR-0005) come with :feature-translation.
        val desktopMain by getting {
            dependencies {
                val javafx = libs.versions.javafx.get()
                val javafxClassifier = HostPlatform.javafx
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.jna)
                implementation(libs.sqldelight.sqlite.driver)
                implementation("org.openjfx:javafx-base:$javafx:$javafxClassifier")
                implementation("org.openjfx:javafx-graphics:$javafx:$javafxClassifier")
                implementation("org.openjfx:javafx-controls:$javafx:$javafxClassifier")
                implementation("org.openjfx:javafx-media:$javafx:$javafxClassifier")
                implementation("org.openjfx:javafx-web:$javafx:$javafxClassifier")
                implementation("org.openjfx:javafx-swing:$javafx:$javafxClassifier")
            }
        }

        val desktopTest by getting {
            dependencies {
                // Compose UI tests of reader panels (the translation panel).
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

// One coverage report over the app and the modules it is built from (R2-5, R5-1).
dependencies {
    kover(project(":core"))
    kover(project(":core-network"))
    kover(project(":domain"))
    kover(project(":data-persistence-api"))
    kover(project(":data-content"))
    kover(project(":data-repository"))
    kover(project(":feature-sync"))
    kover(project(":feature-library"))
    kover(project(":feature-reader"))
    kover(project(":feature-translation"))
    kover(project(":feature-plugin"))
    kover(project(":ui-core"))
    kover(project(":ui-reader"))
    kover(project(":ui-translation"))
    kover(project(":ui-sync"))
    kover(project(":ui"))
}

// The architecture tests in desktopTest (the test-only API opt-in rule) read every module's sources and the
// build scripts: they are inputs, so a change anywhere (an iOS-only source too) runs them again.
tasks.named<Test>("desktopTest") {
    inputs.files(
        fileTree(rootDir) {
            include("*/src/**/*.kt", "*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPropertyName("architectureScanSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
