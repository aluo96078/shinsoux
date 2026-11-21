import dev.shinsou.build.HostPlatform

// docs/architecture/0007 B1: the network package split out of :core, so a module that only needs
// the foundation does not compile against Ktor or Coil. Depends on :core and nothing above it.
plugins {
    id("shinsou.kmp-library")
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            api(libs.ktor.client.core)
            api(libs.coil)
            implementation(libs.coil.network.ktor)
            implementation(libs.ktor.client.encoding)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.android.avif)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
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
