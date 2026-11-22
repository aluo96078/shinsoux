// C1 (docs/architecture/0007): the design system the UI modules share: the theme, the shared
// components (cover images and the plugin image loader, the image viewer, the settings rows, the
// loading indicator), the UI strings (StringKey, ShinsouStrings and the labels of lower-layer
// enums), the review Markdown and small Compose utilities. It knows no screen: every UI module
// (:ui-reader, :ui-translation, :ui-sync, :ui) and the app depend on it, and it only on the layers
// below. No Compose resources (they are :ui's); no generated Res class.
// 0008: no feature module either (DependencyDirectionTest): a plugin or translation ABI change
// recompiles only the UI modules that use it. The plugin image loader takes a PluginImageSource
// the shell supplies; the feature labels live with the screens that show them.
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
            implementation(project(":domain"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // LoadingIndicatorTest reads a stored snapshot the way the repository decodes it.
            implementation(project(":data-repository"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// I18nKeyTablesTest reads the sources of every UI module and the app (the keys they use): they are
// inputs, so a key used or dropped anywhere runs it again.
tasks.named<Test>("desktopTest") {
    inputs.files(
        fileTree(rootDir) {
            include("ui*/src/**/*.kt", "composeApp/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPropertyName("i18nKeyUseSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
