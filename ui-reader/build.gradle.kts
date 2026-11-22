// C1 (docs/architecture/0007): the reader screens: the page reader (ReaderScreen and its pagers and
// controls), the unified content reader (EPUB, image sequences, novels), the extension novel reader
// shell, the reader translation panels and manga prefetch, and what the shell hands them
// (ContentFeatures, the volume-key router, the platform reader capabilities). Depends on :ui-core and
// the layers below, never on another UI module; :ui (the shell), :ui-browse (the
// publication page), :ui-library and :ui-settings open it.
plugins {
    id("shinsou.kmp-library")
    id("shinsou.testing")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

compose.resources {
    generateResClass = never
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-network"))
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(project(":feature-reader"))
            implementation(project(":feature-translation"))
            implementation(project(":feature-plugin"))
            implementation(project(":ui-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Compose UI tests of reader panels (the translation panels).
                implementation(compose.desktop.uiTestJUnit4)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
