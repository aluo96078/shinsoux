// C1 (docs/architecture/0007): the translation settings screens: services and keys, offline models,
// the manga translation's resources and accelerators. Depends on :ui-core and the layers below,
// never on another UI module; :ui (More, the panes) opens it.
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
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":feature-translation"))
            implementation(project(":ui-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
