import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("1.0.0")
val releaseDisplayVersion = providers.gradleProperty("releaseDisplayVersion").orElse(releaseVersion)
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
    .map { rawValue ->
        rawValue.toIntOrNull()?.takeIf { it > 0 }
            ?: error("releaseVersionCode must be a positive integer, got: $rawValue")
    }
    .orElse(1)

val androidReleaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val androidReleaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val androidReleaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val androidReleaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val androidReleaseSigningValues = listOf(
    androidReleaseStoreFile,
    androidReleaseStorePassword,
    androidReleaseKeyAlias,
    androidReleaseKeyPassword,
)
val androidReleaseSigningConfigured = androidReleaseSigningValues.all { !it.isNullOrBlank() }

val isMacOsBuildHost = System.getProperty("os.name").lowercase().contains("mac")
val macOsBuildArchitecture = System.getProperty("os.arch").lowercase().let { architecture ->
    if (architecture.contains("aarch64") || architecture.contains("arm64")) "arm64" else "x86_64"
}
val macOsWebChallengeResourcesRoot = layout.buildDirectory.dir("generated/desktopAppResources")
val macOsWebChallengeHelper = macOsWebChallengeResourcesRoot.map {
    it.dir("macos").file("shinsou-web-challenge")
}
val compileMacOsWebChallengeHelper = if (isMacOsBuildHost) {
    // Keep task actions free of build-script closures so Gradle can serialize the configuration
    // cache. swiftc creates an executable output; the packaged app still copies it to a private
    // directory and restores owner-execute permission before launch.
    val prepareOutputDirectory = tasks.register<Exec>("prepareMacOsWebChallengeHelperOutput") {
        commandLine(
            "/bin/mkdir",
            "-p",
            macOsWebChallengeHelper.get().asFile.parentFile.absolutePath,
        )
    }
    tasks.register<Exec>("compileMacOsWebChallengeHelper") {
        group = "build"
        description = "Builds the native macOS WKWebView challenge helper"
        dependsOn(prepareOutputDirectory)

        val sourcePath = layout.projectDirectory
            .file("src/desktopMain/swift/ShinsouWebChallenge/main.swift")
            .asFile.absolutePath
        val outputPath = macOsWebChallengeHelper.get().asFile.absolutePath
        inputs.file(sourcePath)
        outputs.file(macOsWebChallengeHelper)

        commandLine(
            "xcrun",
            "--sdk",
            "macosx",
            "swiftc",
            "-swift-version",
            "5",
            "-Osize",
            "-target",
            "$macOsBuildArchitecture-apple-macosx12.0",
            "-framework",
            "AppKit",
            "-framework",
            "WebKit",
            sourcePath,
            "-o",
            outputPath,
        )
    }
} else {
    null
}

// JavaFX WebView embeds a WebKit-grade EPUB surface in Desktop distributions.  Native OpenJFX
// artifacts use the build host classifier; release CI already builds DMG and MSI on their native
// hosts, so each installer receives only its own browser runtime.
val javafxPlatformClassifier = run {
    val osName = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    when {
        osName.contains("mac") && (architecture.contains("aarch64") || architecture.contains("arm64")) ->
            "mac-aarch64"
        osName.contains("mac") -> "mac"
        osName.contains("win") -> "win"
        osName.contains("linux") && (architecture.contains("aarch64") || architecture.contains("arm64")) ->
            "linux-aarch64"
        osName.contains("linux") -> "linux"
        else -> error("Unsupported JavaFX EPUB renderer host: $osName/$architecture")
    }
}

check(androidReleaseSigningValues.none { !it.isNullOrBlank() } || androidReleaseSigningConfigured) {
    "Android release signing is only enabled when ANDROID_KEYSTORE_PATH, " +
        "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD are all set."
}

// Android local unit tests execute Android bytecode on the host JVM. The
// libsodium and JNA Android AARs only carry device JNI libraries, while their
// loaders correctly observe the host OS during those tests and request the
// matching JVM native resources. Resolve the host artifacts in an isolated
// configuration and expose only their native resources to Android unit tests;
// no JVM classes or libraries are added to the application classpath.
val androidHostNativeTestResources by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    add(
        androidHostNativeTestResources.name,
        "com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings-jvm:${libs.versions.libsodium.get()}",
    )
    // Must match the JNA version required by the configured libsodium Android
    // AAR; JNA rejects a native dispatch library from a different release.
    add(
        androidHostNativeTestResources.name,
        "net.java.dev.jna:jna:${libs.versions.jna.get()}@jar",
    )
}

val prepareAndroidUnitTestLibsodiumResources by tasks.registering(Sync::class) {
    from(
        androidHostNativeTestResources.incoming.files.elements.map { artifacts ->
            artifacts.map { archive -> zipTree(archive.asFile) }
        },
    ) {
        include("libdynamic-*")
        include("com/sun/jna/**/libjnidispatch.*")
        include("com/sun/jna/**/jnidispatch.*")
    }
    into(layout.buildDirectory.dir("generated/androidUnitTest/libsodiumResources"))
}

tasks.configureEach {
    if (name.startsWith("process") && name.endsWith("UnitTestJavaRes")) {
        dependsOn(prepareAndroidUnitTestLibsodiumResources)
    }
    if (compileMacOsWebChallengeHelper != null &&
        name in setOf("desktopTest", "prepareAppResources")
    ) {
        dependsOn(compileMacOsWebChallengeHelper)
    }
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
        binaries.all {
            linkerOpts("-lsqlite3")
        }
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
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.websockets)
            implementation(libs.sqldelight.runtime)
            implementation(libs.libsodium.bindings)
            implementation(libs.qrose)
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
                implementation(libs.sqldelight.android.driver)
                implementation(libs.google.code.scanner)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
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
                implementation(libs.sqldelight.sqlite.driver)
                implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:$javafxPlatformClassifier")
                implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:$javafxPlatformClassifier")
                implementation("org.openjfx:javafx-controls:${libs.versions.javafx.get()}:$javafxPlatformClassifier")
                implementation("org.openjfx:javafx-media:${libs.versions.javafx.get()}:$javafxPlatformClassifier")
                implementation("org.openjfx:javafx-web:${libs.versions.javafx.get()}:$javafxPlatformClassifier")
                implementation("org.openjfx:javafx-swing:${libs.versions.javafx.get()}:$javafxPlatformClassifier")
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
        versionCode = releaseVersionCode.get()
        versionName = releaseDisplayVersion.get()
    }

    signingConfigs {
        if (androidReleaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(androidReleaseStoreFile))
                storePassword = requireNotNull(androidReleaseStorePassword)
                keyAlias = requireNotNull(androidReleaseKeyAlias)
                keyPassword = requireNotNull(androidReleaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (androidReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures.compose = true

    sourceSets.getByName("test").resources.srcDir(
        layout.buildDirectory.dir("generated/androidUnitTest/libsodiumResources"),
    )

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

compose.desktop {
    application {
        mainClass = "dev.shinsou.kmp.desktop.MainKt"

        if (compileMacOsWebChallengeHelper != null) {
            dependsOn("compileMacOsWebChallengeHelper")
        }

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
            if (isMacOsBuildHost) {
                // Compose places OS-specific children of this directory in
                // `compose.application.resources.dir`. The Kotlin host copies the signed helper
                // to a private executable temporary directory before launch because jpackage
                // intentionally installs application resources without executable permissions.
                appResourcesRootDir.set(macOsWebChallengeResourcesRoot)
            }
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            // SQLDelight and JavaFX WebKit use entry points that jlink cannot infer
            // from the application bytecode. Keep their JDK modules explicitly;
            // without the WebKit modules the challenge dialog fails before issuing
            // a network request and appears to load forever.
            modules(
                "java.sql",
                "java.net.http",
                "jdk.jsobject",
                "jdk.unsupported.desktop",
                "jdk.xml.dom",
            )
            packageName = "Shinsou X"
            packageVersion = releaseVersion.get()
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
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>dev.aluo.shinsoux.sync</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>shinsou</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
            windows {
                // Keep this UUID stable across releases so MSI/EXE upgrades replace
                // an existing Shinsou X installation instead of installing side-by-side.
                upgradeUuid = "1E4B4C87-A9D7-4C41-9270-401E627717A9"
                // Use a machine-scoped MSI so jpackage's per-user RemoveFolderEx cleanup cannot
                // run in a user's profile and remove application data outside the install tree.
                // The app data root remains per-user and is protected by the user's DPAPI key.
                perUserInstall = false
                installationPath = "Shinsou X"
                // Never let the jpackage UI replace the fixed install root with a user-selected
                // existing directory: RemoveFolderEx recursively removes that root on uninstall.
                dirChooser = false
                shortcut = true
                menu = true
                menuGroup = "Shinsou X"
                iconFile.set(project.file("src/desktopMain/resources/shinsou.ico"))
            }
        }
    }
}
