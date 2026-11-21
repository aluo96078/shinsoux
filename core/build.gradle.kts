import dev.shinsou.build.HostPlatform

// R5-1 (docs/architecture/0006 §4.2): concurrent, crypto, files, logging and the generated
// buildinfo. Depends on no other module of the app.
plugins {
    id("shinsou.kmp-library")
    id("shinsou.codegen")
}

shinsouCodegen {
    buildInfo()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.libsodium.bindings)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
            }
        }
        val desktopTest by getting {
            dependencies {
                // The host's Skia runtime (what compose.desktop.currentOs brings the app).
                implementation("org.jetbrains.compose.desktop:desktop-jvm-${HostPlatform.composeDesktop}:${libs.versions.compose.get()}")
            }
        }
    }
}
