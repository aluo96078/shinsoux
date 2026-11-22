// 0008 §7-5 (docs/architecture/0007 §7): the More section: the settings, downloads, statistics,
// backup and content portability (v2 backups, the ShuYue migration), the update check and about.
// A section UI module: it may open the feature UI modules (:ui-sync for the sync settings,
// :ui-reader for the volume-key setting) and :ui-core, never another section or the shell, which
// wires it (and hands it the app icon: the Compose resources are :ui's).
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
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(project(":feature-sync"))
            implementation(project(":feature-library"))
            implementation(project(":ui-core"))
            implementation(project(":ui-reader"))
            implementation(project(":ui-sync"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            // The portability controller tests restore into an in-memory repository.
            implementation(project(":data-repository"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
