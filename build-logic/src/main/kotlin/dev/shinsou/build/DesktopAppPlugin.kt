package dev.shinsou.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.JvmApplication
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * The Compose desktop application and its installers (DMG, MSI, EXE), plus the signing of local
 * Apple Development builds that Compose cannot do itself (Signing.kt holds the identity).
 */
public class DesktopAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.shinsouBuild().macOsSigning.checkValid()
        target.pluginManager.withPlugin("org.jetbrains.compose") {
            val compose = target.extensions.getByType(ComposeExtension::class.java)
            compose.extensions.getByType(DesktopExtension::class.java).application {
                target.configureApplication(this)
            }
            target.registerLocalDevelopmentSigners()
        }
    }
}

private fun Project.configureApplication(application: JvmApplication) = application.apply {
    val settings = shinsouBuild()
    val macOsSigning = settings.macOsSigning
    mainClass = "dev.shinsou.kmp.desktop.MainKt"

    if (settings.isMacOsBuildHost) {
        dependsOn("compileMacOsWebChallengeHelper")
    }

    // JNA's Native class is accessed by JNI and reflection. Keep its methods intact in the release
    // distribution so the macOS Keychain adapter can initialize before the Compose window is shown.
    buildTypes {
        release {
            proguard {
                configurationFiles.from(file("proguard-rules.pro"))
            }
        }
    }

    nativeDistributions {
        if (settings.isMacOsBuildHost || settings.isWindowsBuildHost) {
            // Compose places OS-specific children of this directory in
            // `compose.application.resources.dir`. The Kotlin host copies the helpers to a private
            // executable temporary directory before launch because jpackage intentionally installs
            // application resources without executable permissions.
            appResourcesRootDir.set(settings.desktopAppResourcesRoot)
        }
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
        // SQLDelight, JavaFX WebKit, and Windows Java Access Bridge use entry points that jlink
        // cannot infer from the application bytecode. Keep their JDK modules explicitly. Without
        // jdk.accessibility, Windows users with Access Bridge enabled only see jpackage's "Failed
        // to launch JVM" dialog before the Compose window can open. On Windows, libsodium's
        // resource-loader extracts its DLL through Paths.get(jar: URI), which needs the zip
        // filesystem provider from jdk.zipfs; without it, verifying a repository signature fails
        // with `Provider "jar" not installed`. JavaFX's Marlin rasterizer (WebView pages with
        // arbitrary paths, SVG, borders) uses sun.misc.Unsafe from jdk.unsupported; without it
        // rendering throws NoClassDefFoundError on every packaged platform.
        modules(
            "java.sql",
            // Manga translation sizes its work by the machine's memory (com.sun.management).
            "java.management",
            "jdk.management",
            "java.net.http",
            "jdk.accessibility",
            "jdk.jsobject",
            "jdk.unsupported",
            "jdk.unsupported.desktop",
            "jdk.xml.dom",
            "jdk.zipfs",
        )
        packageName = "Shinsou X"
        packageVersion = settings.releaseVersion.get()
        description = "Shinsou X manga reader"
        vendor = "Shinsou X"
        macOS {
            bundleID = "dev.aluo.shinsoux"
            packageName = "Shinsou X"
            dockName = "Shinsou X"
            setDockNameSameAsPackageName = true
            iconFile.set(file("src/desktopMain/resources/shinsou.icns"))
            val composeIdentity = macOsSigning.composeIdentity
            if (composeIdentity != null) {
                // A stable signing identity gives Keychain a stable designated requirement. Ad-hoc
                // signatures are tied to each build's cdhash, so "Always Allow" is forgotten after
                // every update even when the bundle ID is unchanged. Compose handles Developer ID
                // certificates here. Its signer hard-codes that prefix, so local Apple Development
                // builds are signed by registerLocalDevelopmentSigners instead.
                signing {
                    sign.set(true)
                    identity.set(composeIdentity)
                    macOsSigning.keychain?.let(keychain::set)
                }
            }
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
            // Windows Installer accepts only MAJOR.MINOR.BUILD. Use the monotonic,
            // prerelease-aware value derived from the release tag instead of dropping the beta
            // suffix and accidentally reusing the previous ProductCode/ProductVersion.
            msiPackageVersion = settings.windowsPackageVersion.get()
            exePackageVersion = settings.windowsPackageVersion.get()
            // Keep this UUID stable across releases so MSI/EXE upgrades replace an existing
            // Shinsou X installation instead of installing side-by-side.
            upgradeUuid = "1E4B4C87-A9D7-4C41-9270-401E627717A9"
            // Use a machine-scoped MSI so jpackage's per-user RemoveFolderEx cleanup cannot run in
            // a user's profile and remove application data outside the install tree. The app data
            // root remains per-user and is protected by the user's DPAPI key.
            perUserInstall = false
            installationPath = "Shinsou X"
            // Never let the jpackage UI replace the fixed install root with a user-selected
            // existing directory: RemoveFolderEx recursively removes that root on uninstall.
            dirChooser = false
            shortcut = true
            menu = true
            menuGroup = "Shinsou X"
            iconFile.set(file("src/desktopMain/resources/shinsou.ico"))
        }
    }
}

private fun Project.registerLocalDevelopmentSigners() {
    val settings = shinsouBuild()
    val identity = settings.macOsSigning.localDevelopmentIdentity
    if (!settings.isMacOsBuildHost || identity == null) return
    val keychain = settings.macOsSigning.keychain.orEmpty()
    val defaultEntitlements = layout.buildDirectory
        .file("compose/default-resources/${catalogVersion("compose")}/default-entitlements.plist")
        .get().asFile.absolutePath

    // The create task also finalizes with this signer, while DMG/run tasks depend on it. This
    // ordering keeps the app signed when create* is called directly and guarantees that jpackage
    // receives the signed app image when it creates a DMG.
    fun appSigner(taskName: String, createTaskName: String, appDirectory: String) =
        tasks.register(taskName, Exec::class.java) {
            group = "compose desktop"
            description = "Signs the macOS app image with the configured Apple Development identity"
            mustRunAfter(createTaskName)
            outputs.upToDateWhen { false }
            commandLine(
                "/bin/bash",
                rootProject.layout.projectDirectory.file("scripts/sign_macos_app.sh").asFile.absolutePath,
                layout.buildDirectory.dir(appDirectory).get().asFile.absolutePath,
                identity,
                defaultEntitlements,
                keychain,
            )
        }

    // jpackage replaces the certificate signature on an app image with an ad-hoc signature while
    // wrapping it in a DMG. Run once more against the app inside the final image.
    fun dmgSigner(taskName: String, packageTaskName: String, dmgDirectory: String) =
        tasks.register(taskName, Exec::class.java) {
            group = "compose desktop"
            description = "Re-signs the packaged DMG app with the configured Apple Development identity"
            mustRunAfter(packageTaskName)
            outputs.upToDateWhen { false }
            commandLine(
                "/bin/bash",
                rootProject.layout.projectDirectory.file("scripts/sign_macos_dmg.sh").asFile.absolutePath,
                layout.buildDirectory.file("$dmgDirectory/Shinsou X-${settings.releaseVersion.get()}.dmg")
                    .get().asFile.absolutePath,
                identity,
                defaultEntitlements,
                keychain,
            )
        }

    val signApp = appSigner("signMacOsDistributable", "createDistributable", "compose/binaries/main/app/Shinsou X.app")
    val signReleaseApp = appSigner(
        "signMacOsReleaseDistributable",
        "createReleaseDistributable",
        "compose/binaries/main-release/app/Shinsou X.app",
    )
    val signDmg = dmgSigner("signMacOsDmg", "packageDmg", "compose/binaries/main/dmg")
    val signReleaseDmg = dmgSigner("signMacOsReleaseDmg", "packageReleaseDmg", "compose/binaries/main-release/dmg")

    tasks.named { it == "createDistributable" }.configureEach { finalizedBy(signApp) }
    tasks.named { it == "createReleaseDistributable" }.configureEach { finalizedBy(signReleaseApp) }
    tasks.named { it == "packageDmg" }.configureEach {
        dependsOn(signApp)
        finalizedBy(signDmg)
    }
    tasks.named { it == "packageReleaseDmg" }.configureEach {
        dependsOn(signReleaseApp)
        finalizedBy(signReleaseDmg)
    }
    tasks.named { it == "runDistributable" }.configureEach { dependsOn(signApp) }
    tasks.named { it == "runReleaseDistributable" }.configureEach { dependsOn(signReleaseApp) }
}
