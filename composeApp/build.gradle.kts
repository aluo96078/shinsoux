import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// Keep generated Compose resource classes in a valid Kotlin package even
// though the product/project name intentionally contains a space.
compose.resources {
    packageOfResClass = "dev.aluo.shinsoux.generated.resources"
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    iosArm64()
    iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ShinsouKit"
            isStatic = true
            binaryOption("bundleId", "dev.aluo.shinsoux.shared")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
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

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.coil.network.ktor)
                implementation(libs.jsoup)
                implementation(libs.rhino)
            }
        }

        androidMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.biometric)
                implementation(libs.androidx.work.runtime)
                // MainActivity installs a shared Coil network fetcher so Android image
                // requests use the same no-proxy Ktor client as source requests.
                implementation(libs.coil.network.ktor)
                implementation(libs.ktor.client.okhttp)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.jna)
            }
        }

        val desktopTest by getting {
            resources.srcDir(file("../../shinsou_plugin"))
        }
    }
}

android {
    namespace = "dev.aluo.shinsoux"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.aluo.shinsoux"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures.compose = true

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

compose.desktop {
    application {
        mainClass = "dev.shinsou.kmp.desktop.MainKt"

        // JNA's Native class is accessed by JNI and reflection.  Keep its
        // methods intact in the release distribution so the macOS Keychain
        // adapter can initialize before the Compose window is shown.
        buildTypes {
            release {
                proguard {
                    configurationFiles.from(project.file("proguard-rules.pro"))
                }
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Shinsou X"
            packageVersion = "1.0.0"
            description = "Shinsou X manga reader"
            vendor = "Shinsou X"
            macOS {
                bundleID = "dev.aluo.shinsoux"
                packageName = "Shinsou X"
                dockName = "Shinsou X"
                setDockNameSameAsPackageName = true
                iconFile.set(project.file("src/desktopMain/resources/shinsou.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleDisplayName</key>
                        <string>Shinsou X</string>
                    """.trimIndent()
                }
            }
            windows {
                // Keep this UUID stable across releases so MSI/EXE upgrades replace
                // an existing Shinsou X installation instead of installing side-by-side.
                upgradeUuid = "1E4B4C87-A9D7-4C41-9270-401E627717A9"
                perUserInstall = true
                // A per-user jpackage install otherwise defaults to %LOCALAPPDATA%\Shinsou X,
                // which is also the application data root. Keep binaries under Programs so
                // upgrades and uninstallers can never remove the user's library and secrets.
                installationPath = "Programs\\Shinsou X"
                dirChooser = true
                shortcut = true
                menu = true
                menuGroup = "Shinsou X"
                iconFile.set(project.file("src/desktopMain/resources/shinsou.ico"))
            }
        }
    }
}
