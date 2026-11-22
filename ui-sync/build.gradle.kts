// C1 (docs/architecture/0007): the sync screens: the sync settings and account, sharing with other
// users, and the reviews pane. Depends on :ui-core and the layers below, never on another UI module;
// :ui-settings (More) and :ui-browse (the publication pane) open it.
plugins {
    id("shinsou.kmp-library")
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
            implementation(project(":feature-sync"))
            implementation(project(":ui-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
    }
}
