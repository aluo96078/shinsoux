package dev.shinsou.build

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File

/**
 * The values several convention plugins share: release versions, build-host facts, the plugin
 * repository the tests read and the signing inputs (Signing.kt). Created once per project by the
 * first convention plugin applied; [shinsouBuild] returns it.
 */
public class ShinsouBuildSettings internal constructor(project: Project) {
    private val providers = project.providers

    val releaseVersion: Provider<String> = providers.gradleProperty("releaseVersion").orElse("1.0.2")

    // Xcode runs Gradle from a build phase, where a -P argument cannot be passed: CI names the
    // version through the environment there.
    val releaseDisplayVersion: Provider<String> = providers.gradleProperty("releaseDisplayVersion")
        .orElse(providers.environmentVariable("SHINSOU_RELEASE_DISPLAY_VERSION").filter { it.isNotBlank() })
        .orElse("1.0.2-beta.1")

    val windowsPackageVersion: Provider<String> =
        providers.gradleProperty("windowsPackageVersion").orElse("1.0.201")

    val releaseVersionCode: Provider<Int> = providers.gradleProperty("releaseVersionCode")
        .map { rawValue ->
            rawValue.toIntOrNull()?.takeIf { it > 0 }
                ?: error("releaseVersionCode must be a positive integer, got: $rawValue")
        }
        .orElse(25600201)

    val isMacOsBuildHost: Boolean = System.getProperty("os.name").lowercase().contains("mac")
    val isWindowsBuildHost: Boolean = System.getProperty("os.name").lowercase().startsWith("windows")

    val macOsBuildArchitecture: String = System.getProperty("os.arch").lowercase().let { architecture ->
        if (architecture.contains("aarch64") || architecture.contains("arm64")) "arm64" else "x86_64"
    }

    // Device tests install (and afterwards uninstall) the app: give that build its own application
    // id so a developer's or tester's real installation and its library are never touched.
    val sandboxDeviceTest: Provider<Boolean> = providers.gradleProperty("shinsouSandboxDeviceTest")
        .map { it.toBooleanStrict() }
        .orElse(false)

    // Blank values (e.g. a shell that mangles `-P...=` arguments) fall back instead of breaking file().
    private val pluginRepository: Provider<String> = providers.gradleProperty("shinsouPluginRepository")
        .filter { it.isNotBlank() }
        .orElse(providers.systemProperty("shinsou.pluginRepo").filter { it.isNotBlank() })
        .orElse("../../shinsou_plugin")

    // The repository keeps its unsigned sources in src/ (the root serves signed copies); older
    // checkouts, such as the commit CI pins, keep them at the root.
    val pluginSources: Provider<File> = pluginRepository.map { repository ->
        val root = project.file(repository)
        root.resolve("src").takeIf { it.resolve("index.json").isFile } ?: root
    }

    /**
     * Compose places OS-specific children of this directory in `compose.application.resources.dir`:
     * the macOS and Windows helpers are built into it.
     */
    val desktopAppResourcesRoot: Provider<Directory> =
        project.layout.buildDirectory.dir("generated/desktopAppResources")

    val macOsSigning: MacOsSigning = MacOsSigning(providers)
    val androidReleaseSigning: AndroidSigningValues = AndroidSigningValues.release(providers)
    val androidPublicDebugSigning: AndroidSigningValues = AndroidSigningValues.publicDebug(providers)
}

internal fun Project.shinsouBuild(): ShinsouBuildSettings =
    extensions.findByType(ShinsouBuildSettings::class.java)
        ?: ShinsouBuildSettings(this).also {
            extensions.add(ShinsouBuildSettings::class.java, "shinsouBuildSettings", it)
        }

/** The desktop host's native-artifact classifiers for the dependencies in composeApp's build script. */
public object HostPlatform {
    private val osName: String get() = System.getProperty("os.name").lowercase()
    private val arm: Boolean
        get() = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }

    /**
     * The `desktop-jvm-<os>-<arch>` suffix of Compose's `compose.desktop.currentOs`, for a module
     * without the Compose plugins that needs the host's Skia runtime (R5-1: :core's desktop tests).
     */
    public val composeDesktop: String
        get() = when {
            osName.contains("mac") -> if (arm) "macos-arm64" else "macos-x64"
            osName.contains("win") -> if (arm) "windows-arm64" else "windows-x64"
            osName.contains("linux") -> if (arm) "linux-arm64" else "linux-x64"
            else -> error("Unsupported Compose desktop host: $osName/${System.getProperty("os.arch").lowercase()}")
        }

    /** ADR-0005: the OpenCV natives for this desktop host (bytedeco's per-platform jars). */
    public val bytedeco: String
        get() = when {
            osName.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
            osName.contains("win") -> "windows-x86_64"
            osName.contains("linux") -> if (arm) "linux-arm64" else "linux-x86_64"
            else -> error("Unsupported OpenCV host: $osName/${System.getProperty("os.arch").lowercase()}")
        }

    /**
     * JavaFX WebView embeds a WebKit-grade EPUB surface in Desktop distributions. Native OpenJFX
     * artifacts use the build host classifier; release CI already builds DMG and MSI on their
     * native hosts, so each installer receives only its own browser runtime.
     */
    public val javafx: String
        get() = when {
            osName.contains("mac") && arm -> "mac-aarch64"
            osName.contains("mac") -> "mac"
            osName.contains("win") -> "win"
            osName.contains("linux") && arm -> "linux-aarch64"
            osName.contains("linux") -> "linux"
            else -> error("Unsupported JavaFX EPUB renderer host: $osName/${System.getProperty("os.arch").lowercase()}")
        }
}
