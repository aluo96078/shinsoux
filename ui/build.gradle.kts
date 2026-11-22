// R5-1 (docs/architecture/0006 §4.2): ui (the Compose screens, the challenge dialog) and navigation.
// C1 (0007): the shell (ShinsouApp, the app services, navigation, app state, layouts and panes).
// 0008 §7-5: the screens are in the section UI modules (:ui-browse, :ui-library, :ui-settings) and
// the feature UI modules (:ui-reader, :ui-translation, :ui-sync); the shell composes and wires them,
// and the design system and strings are :ui-core's. Owns the Compose resources (the app icon, Res in dev.aluo.shinsoux.generated.resources);
// the app module reaches the icon through shinsouIconPainter(). Depends on the feature modules and
// the layers below; only the app module (composition, entry points, packaging) is above it.
plugins {
    id("shinsou.kmp-library")
    id("shinsou.testing")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Keep generated Compose resource classes in a valid Kotlin package even
// though the product/project name intentionally contains a space.
compose.resources {
    packageOfResClass = "dev.aluo.shinsoux.generated.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-network"))
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(project(":data-repository"))
            implementation(project(":feature-sync"))
            implementation(project(":feature-library"))
            implementation(project(":feature-reader"))
            implementation(project(":feature-translation"))
            implementation(project(":feature-plugin"))
            implementation(project(":ui-core"))
            implementation(project(":ui-reader"))
            implementation(project(":ui-translation"))
            implementation(project(":ui-sync"))
            implementation(project(":ui-browse"))
            implementation(project(":ui-library"))
            implementation(project(":ui-settings"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Compose UI tests of reader panels (the translation panel).
                implementation(compose.desktop.uiTestJUnit4)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
