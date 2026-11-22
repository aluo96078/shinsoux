import dev.shinsou.build.HostPlatform

// R5-1 (docs/architecture/0006 §4.2): reader (EPUB and image renderers, the EPUB browser view),
// local (imported files) and tts. Depends on the data, domain and core modules.
plugins {
    id("shinsou.kmp-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-network"))
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(project(":data-repository"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.coil)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                // The EPUB browser view embeds JavaFX's WebView (see the app's desktopMain).
                val javafx = libs.versions.javafx.get()
                listOf("base", "graphics", "controls", "web", "swing").forEach { module ->
                    implementation("org.openjfx:javafx-$module:$javafx:${HostPlatform.javafx}")
                }
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
