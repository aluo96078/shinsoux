// 0008 §7-5 (docs/architecture/0007 §7): the Browse section: the source list and catalogues, the
// global search, the extension and repository pages, migration, the extension publication page
// (which embeds the reader, the reviews and sharing), the plugin permission and package security
// dialogs, and the source web challenge. A section UI module: it may open the feature UI modules
// (:ui-reader, :ui-sync, :ui-translation) and :ui-core, never another section or the shell; :ui
// hands it the narrow interfaces of :feature-plugin and :feature-sync it uses.
plugins {
    id("shinsou.kmp-library")
    id("shinsou.testing")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources {
    generateResClass = never
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":domain"))
            implementation(project(":feature-sync"))
            implementation(project(":feature-reader"))
            implementation(project(":feature-translation"))
            implementation(project(":feature-plugin"))
            implementation(project(":ui-core"))
            implementation(project(":ui-reader"))
            implementation(project(":ui-translation"))
            implementation(project(":ui-sync"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            // The publication state tests commit typed content through the in-memory store.
            implementation(project(":data-content"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // The challenge dialog's WebView runs in a dedicated profile (AndroidX WebKit).
            implementation(libs.androidx.webkit)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
