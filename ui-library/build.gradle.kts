// 0008 §7-5 (docs/architecture/0007 §7): the Library section: the library grid and its categories,
// a title's detail page and tracking sheet, updates, history and the upcoming schedule, continue
// reading. A section UI module: it may open the feature UI modules (:ui-reader for the progress
// labels) and :ui-core, never another section or the shell, which wires it.
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
            implementation(project(":data-repository"))
            implementation(project(":feature-sync"))
            implementation(project(":feature-library"))
            implementation(project(":feature-reader"))
            implementation(project(":feature-plugin"))
            implementation(project(":ui-core"))
            implementation(project(":ui-reader"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}
