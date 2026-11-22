package dev.shinsou.build

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * The native code the app ships or links (R5-2, docs/architecture/0006): the macOS and Windows
 * helper executables packaged with the desktop app, and the fetched sources Android's external
 * native build needs. The iOS libraries behind the cinterops are [NativeInteropPlugin]'s, applied
 * here too for the cinterops the app module still owns. Every task keeps the name CI and the
 * scripts call.
 */
public class NativeLibrariesPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val settings = shinsouBuild()
        pluginManager.apply(NativeInteropPlugin::class.java)
        val fetchSandbox = ownerTask("sandboxWasm", "fetchSandboxCoreDeps")
        val fetchTranslation = ownerTask("translationCore", "fetchTranslationCoreDeps")
        // The app's Android external native build is translation-core's (configureAndroidNativeBuild).
        tasks.named { it.startsWith("configureCMake") || it.startsWith("buildCMake") }.configureEach {
            dependsOn(fetchTranslation)
        }
        val desktopHelpers = buildList {
            if (settings.isMacOsBuildHost) addAll(registerMacOsHelpers(settings, fetchSandbox, fetchTranslation))
            if (settings.isWindowsBuildHost) addAll(registerWindowsHelpers(fetchSandbox, fetchTranslation))
        }
        // The packaged app takes its helpers from the resources directory these tasks fill.
        tasks.named { it == "prepareAppResources" }.configureEach { dependsOn(desktopHelpers) }
    }
}

/**
 * R5-1: the native code behind the iOS cinterops a module owns (KmpPlugin creates a cinterop for
 * each `src/nativeInterop/cinterop/<name>.def` there): the pinned third-party sources of
 * sandbox-core (`sandboxWasm`) and translation-core (`translationCore`) and the iOS static
 * libraries, built before the cinterop that embeds them.
 */
public class NativeInteropPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        registerFetchTasks(shinsouBuild())
        // The owner of sandbox-core also builds its Android library (configureAndroidNativeBuild).
        if (ownsCinterop("sandboxWasm")) {
            tasks.named { it.startsWith("configureCMake") || it.startsWith("buildCMake") }.configureEach {
                dependsOn("fetchSandboxCoreDeps")
            }
        }
        if (ownsCinterop("sandboxWasm")) registerIosSandboxLibraries()
        if (ownsCinterop("translationCore")) registerIosTranslationLibraries()
        if (ownsCinterop("mangaOnnx")) registerIosVisionLibraries()
    }
}

private fun Project.ownsCinterop(name: String): Boolean = file("src/nativeInterop/cinterop/$name.def").isFile

/**
 * The path of [task] in the module that owns cinterop [cinterop] (R5-1): that module fetches the
 * native sources behind it, and the app's Android and desktop builds of the same sources use them.
 */
internal fun Project.ownerTask(cinterop: String, task: String): String {
    val owner = rootProject.allprojects.firstOrNull { it.ownsCinterop(cinterop) }
        ?: error("No module has src/nativeInterop/cinterop/$cinterop.def")
    return if (owner == this) task else "${owner.path}:$task"
}

private fun Project.registerFetchTasks(settings: ShinsouBuildSettings) {
    // sandbox-core's pinned third-party sources (quickjs-ng, lexbor) are fetched, not committed.
    // On Windows `bash` on PATH is often System32's WSL launcher: use the bash of Git for Windows.
    val bash = if (settings.isWindowsBuildHost) {
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "git.exe") }
            .firstOrNull { it.isFile }
            ?.let { File(it.parentFile.parentFile, "bin/bash.exe") }
            ?.takeIf { it.isFile }
            ?.absolutePath
            ?: "C:/Program Files/Git/bin/bash.exe"
    } else {
        "bash"
    }
    if (ownsCinterop("sandboxWasm")) {
        tasks.register("fetchSandboxCoreDeps", Exec::class.java) {
            description = "Fetches sandbox-core's pinned third-party sources and applies its patches"
            workingDir = rootProject.file("sandbox-core")
            commandLine(bash, "scripts/fetch-deps.sh")
            stampedFetch(rootProject.file("sandbox-core"))
        }
    }
    if (ownsCinterop("translationCore")) {
        tasks.register("fetchTranslationCoreDeps", Exec::class.java) {
            description = "Fetches translation-core's pinned engine sources and applies its patches"
            workingDir = rootProject.file("translation-core")
            commandLine(bash, "scripts/fetch-deps.sh")
            stampedFetch(rootProject.file("translation-core"))
        }
    }
}

/**
 * 0007 A6: the parallel jobs of a native build - this machine's processors rather than a fixed 8,
 * which overloaded small CI runners and left larger machines idle.
 */
private val nativeBuildJobs: String = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toString()

/**
 * 0007 A6: a fetch reruns only when its script or patches change, or its sources went: the stamp
 * lives inside `third_party/`, so deleting that directory fetches again. The stamp is written only
 * after the script checked every source against its pinned commit and applied the patches.
 */
private fun Exec.stampedFetch(root: File) {
    val stamp = File(root, "third_party/.fetched")
    inputs.file(File(root, "scripts/fetch-deps.sh"))
    File(root, "patches").takeIf { it.isDirectory }?.let { inputs.dir(it) }
    outputs.file(stamp)
    doLast { stamp.writeText("fetched\n") }
}

/**
 * What a desktop helper's CMake build reads from the repository, as inputs of its configure and
 * compile steps: the tracked sources (not `third_party/`, which is hundreds of megabytes: the
 * fetch's [stampedFetch] stamp, script and patches stand for it) and [extra] files the build embeds.
 * Without these the Exec tasks had no inputs or outputs and ran on every build.
 */
private class CmakeSources(val root: File, private val directories: List<String>, private val extra: List<File> = emptyList()) {
    fun Task.readsSources() {
        inputs.file(File(root, "CMakeLists.txt"))
        directories.map { File(root, it) }.filter { it.isDirectory }.forEach { inputs.dir(it) }
        extra.forEach { inputs.file(it) }
        inputs.file(File(root, "scripts/fetch-deps.sh"))
        File(root, "patches").takeIf { it.isDirectory }?.let { inputs.dir(it) }
        inputs.files(File(root, "third_party/.fetched"))
    }
}

/**
 * A CMake configure step: its [arguments] are an input (another architecture or generator
 * configures again), its `CMakeCache.txt` the output that says it ran.
 */
private fun Exec.configuresCmake(sources: CmakeSources, buildDir: String, arguments: List<String>) {
    with(sources) { readsSources() }
    inputs.property("cmakeArguments", arguments)
    outputs.file(File(buildDir, "CMakeCache.txt"))
    commandLine(listOf("cmake") + arguments)
}

/**
 * A CMake build of one [target] that produces [executable]: it reruns when a source, the configure
 * [arguments] or the executable change, and is up to date otherwise.
 */
private fun Exec.buildsCmakeTarget(
    sources: CmakeSources,
    buildDir: String,
    target: String,
    executable: File,
    configureArguments: List<String>,
    extraArguments: List<String> = emptyList(),
) {
    with(sources) { readsSources() }
    inputs.property("cmakeArguments", configureArguments)
    outputs.file(executable)
    commandLine(listOf("cmake", "--build", buildDir, "--target", target) + extraArguments)
}

private fun Project.sandboxCoreSources() = CmakeSources(
    root = rootProject.file("sandbox-core"),
    directories = listOf("src", "scripts", "test"),
    // Embedded into the driver by the CMake build (ssc_bootstrap_js.c).
    extra = listOf(rootProject.file("feature-plugin/src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt")),
)

private fun Project.translationCoreSources() = CmakeSources(
    root = rootProject.file("translation-core"),
    directories = listOf("src"),
)

/**
 * Copies [built] to [output] and signs it ([signedHelperCommand]). Up to date while the built
 * helper, the signing identity and the signed output stay as they were: a changed or removed
 * output signs again.
 */
private fun Exec.signsHelper(built: String, output: String, settings: ShinsouBuildSettings) {
    inputs.file(built)
    inputs.property("signingIdentity", settings.macOsSigning.identity ?: "-")
    inputs.property("signingKeychain", settings.macOsSigning.keychain ?: "")
    outputs.file(output)
    commandLine(signedHelperCommand(built, output, settings))
}

/** Copies a built helper into the app resources and signs it (hardened runtime, no entitlements). */
private fun signedHelperCommand(built: String, output: String, settings: ShinsouBuildSettings): List<String> {
    // The configured identity when there is one (so a Developer ID build can be notarized), ad-hoc
    // otherwise.
    val identity = settings.macOsSigning.identity ?: "-"
    val keychain = settings.macOsSigning.keychain?.let { listOf("--keychain", it) }.orEmpty()
    return listOf(
        "/bin/sh", "-c",
        "cp \"$1\" \"$2\" && shift 2 && /usr/bin/codesign --force --options runtime --timestamp=none \"$@\"",
        "sign-helper", built, output,
    ) + keychain + listOf("--sign", identity, output)
}

private fun Project.registerMacOsHelpers(
    settings: ShinsouBuildSettings,
    fetchSandbox: String,
    fetchTranslation: String,
): List<TaskProvider<out Task>> {
    val macOsDir = settings.desktopAppResourcesRoot.map { it.dir("macos") }
    val webChallengeHelper = macOsDir.map { it.file("shinsou-web-challenge") }
    val architecture = settings.macOsBuildArchitecture

    // Keep task actions free of build-script closures so Gradle can serialize the configuration
    // cache. swiftc creates an executable output; the packaged app still copies it to a private
    // directory and restores owner-execute permission before launch. Gradle creates the output
    // file's parent directory, so no task has to make it first.
    val compileWebChallengeHelper = tasks.register("compileMacOsWebChallengeHelper", Exec::class.java) {
        group = "build"
        description = "Builds the native macOS WKWebView challenge helper"
        val sourcePath = layout.projectDirectory
            .file("src/desktopMain/swift/ShinsouWebChallenge/main.swift")
            .asFile.absolutePath
        inputs.file(sourcePath)
        inputs.property("architecture", architecture)
        outputs.file(webChallengeHelper)
        commandLine(
            "xcrun", "--sdk", "macosx", "swiftc", "-swift-version", "5", "-Osize",
            "-target", "$architecture-apple-macosx12.0",
            "-framework", "AppKit", "-framework", "WebKit", "-framework", "Network",
            sourcePath, "-o", webChallengeHelper.get().asFile.absolutePath,
        )
    }
    tasks.named { it == "desktopTest" }.configureEach { dependsOn(compileWebChallengeHelper) }

    // ADR-0002 S3: the plugin sandbox helper (sandbox-core's shinsou-sandbox), built for the host
    // architecture and shipped next to the WKWebView helper. It confines itself with sandbox_init at
    // startup; the signature carries no entitlements, so it gains nothing the deny-all profile removes.
    val sandboxSources = sandboxCoreSources()
    val sandboxBuild = layout.buildDirectory.dir("sandbox-core-macos").get().asFile.absolutePath
    val sandboxConfigure = listOf(
        "-S", sandboxSources.root.absolutePath, "-B", sandboxBuild,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_OSX_ARCHITECTURES=$architecture",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0",
    )
    val configureSandbox = tasks.register("configureMacOsSandboxHelper", Exec::class.java) {
        dependsOn(fetchSandbox)
        configuresCmake(sandboxSources, sandboxBuild, sandboxConfigure)
    }
    val compileSandbox = tasks.register("compileMacOsSandboxHelper", Exec::class.java) {
        dependsOn(configureSandbox)
        buildsCmakeTarget(
            sandboxSources, sandboxBuild, "shinsou-sandbox", File(sandboxBuild, "shinsou-sandbox"),
            sandboxConfigure, listOf("-j", nativeBuildJobs),
        )
    }
    val buildSandbox = tasks.register("buildMacOsSandboxHelper", Exec::class.java) {
        group = "build"
        description = "Builds and signs the plugin sandbox helper for the macOS app resources"
        dependsOn(compileSandbox)
        signsHelper("$sandboxBuild/shinsou-sandbox", macOsDir.get().file("shinsou-sandbox").asFile.absolutePath, settings)
    }

    // ADR-0004 §4.5: the offline translation helper (translation-core's shinsou-translate), built
    // for the host architecture and shipped next to the other helpers. A build without the helper
    // reports offline translation as unavailable.
    val translationSources = translationCoreSources()
    val translationBuild = layout.buildDirectory.dir("translation-core-macos").get().asFile.absolutePath
    // The host architecture: the engine's architecture detection refuses an explicit
    // CMAKE_OSX_ARCHITECTURES=arm64.
    val translationConfigure = listOf(
        "-S", translationSources.root.absolutePath, "-B", translationBuild,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0",
    )
    val configureTranslation = tasks.register("configureMacOsTranslationHelper", Exec::class.java) {
        dependsOn(fetchTranslation)
        configuresCmake(translationSources, translationBuild, translationConfigure)
    }
    val compileTranslation = tasks.register("compileMacOsTranslationHelper", Exec::class.java) {
        dependsOn(configureTranslation)
        buildsCmakeTarget(
            translationSources, translationBuild, "shinsou-translate", File(translationBuild, "shinsou-translate"),
            translationConfigure, listOf("-j", nativeBuildJobs),
        )
    }
    val buildTranslation = tasks.register("buildMacOsTranslationHelper", Exec::class.java) {
        group = "build"
        description = "Builds and signs the offline translation helper for the macOS app resources"
        dependsOn(compileTranslation)
        signsHelper("$translationBuild/shinsou-translate", macOsDir.get().file("shinsou-translate").asFile.absolutePath, settings)
    }
    return listOf(compileWebChallengeHelper, buildSandbox, buildTranslation)
}

private fun Project.registerWindowsHelpers(fetchSandbox: String, fetchTranslation: String): List<TaskProvider<out Task>> {
    val windowsDir = shinsouBuild().desktopAppResourcesRoot.map { it.dir("windows").asFile }

    // ADR-0002 S3 (Windows): the same helper, built with MinGW-w64 (UCRT64; sandbox-core needs
    // pthreads and POSIX I/O, so not MSVC) as one static executable. It relaunches itself in a Low
    // Privilege AppContainer and a Job object (sandbox-core/src/ssc_os_windows.c).
    val sandboxSources = sandboxCoreSources()
    val sandboxBuild = layout.buildDirectory.dir("sandbox-core-windows").get().asFile.absolutePath
    val sandboxConfigure = listOf(
        "-S", sandboxSources.root.absolutePath, "-B", sandboxBuild, "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_COMPILER=gcc",
    )
    val configureSandbox = tasks.register("configureWindowsSandboxHelper", Exec::class.java) {
        dependsOn(fetchSandbox)
        configuresCmake(sandboxSources, sandboxBuild, sandboxConfigure)
    }
    val compileSandbox = tasks.register("compileWindowsSandboxHelper", Exec::class.java) {
        dependsOn(configureSandbox)
        buildsCmakeTarget(
            sandboxSources, sandboxBuild, "shinsou-sandbox", File(sandboxBuild, "shinsou-sandbox.exe"), sandboxConfigure,
        )
    }
    val buildSandbox = tasks.register("buildWindowsSandboxHelper", Copy::class.java) {
        group = "build"
        description = "Builds the plugin sandbox helper for the Windows app resources"
        dependsOn(compileSandbox)
        from(File(sandboxBuild, "shinsou-sandbox.exe"))
        into(windowsDir)
    }

    // ADR-0004 §4.5 (Windows): the offline translation helper, built with the same MinGW-w64
    // (UCRT64) toolchain as the sandbox helper, as one static executable.
    val translationSources = translationCoreSources()
    val translationBuild = layout.buildDirectory.dir("translation-core-windows").get().asFile.absolutePath
    val translationConfigure = listOf(
        "-S", translationSources.root.absolutePath, "-B", translationBuild, "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_COMPILER=gcc", "-DCMAKE_CXX_COMPILER=g++",
    )
    val configureTranslation = tasks.register("configureWindowsTranslationHelper", Exec::class.java) {
        dependsOn(fetchTranslation)
        configuresCmake(translationSources, translationBuild, translationConfigure)
    }
    val compileTranslation = tasks.register("compileWindowsTranslationHelper", Exec::class.java) {
        dependsOn(configureTranslation)
        buildsCmakeTarget(
            translationSources, translationBuild, "shinsou-translate", File(translationBuild, "shinsou-translate.exe"),
            translationConfigure,
        )
    }
    val buildTranslation = tasks.register("buildWindowsTranslationHelper", Copy::class.java) {
        group = "build"
        description = "Builds the offline translation helper for the Windows app resources"
        dependsOn(compileTranslation)
        from(File(translationBuild, "shinsou-translate.exe"))
        into(windowsDir)
    }
    return listOf(buildTranslation, buildSandbox)
}

private val iosSdks = listOf("iphoneos", "iphonesimulator")

/** ADR-0002 S4 (iOS): the sandbox-core WASM guest and WAMR host library behind `sandboxWasm`. */
private fun Project.registerIosSandboxLibraries() {
    val sandboxCore = rootProject.layout.projectDirectory.dir("sandbox-core")

    // Per Apple SDK, the WAMR host library the iOS targets link through the `sandboxWasm` cinterop.
    // Needs clang/lld with the wasm32 target, wasi-libc and its builtins (Homebrew: llvm lld
    // wasi-libc wasi-runtimes wabt), CMake and Xcode.
    val guest = layout.buildDirectory.file("sandbox-wasm/shinsou_sandbox.wasm")
    val buildGuest = tasks.register("buildIosSandboxGuest", Exec::class.java) {
        group = "build"
        description = "Builds the sandbox-core WASM guest for the iOS plugin sandbox"
        dependsOn("fetchSandboxCoreDeps")
        inputs.dir(sandboxCore.dir("src"))
        inputs.dir(sandboxCore.dir("wasm"))
        inputs.file(layout.projectDirectory.file("src/iosMain/kotlin/dev/shinsou/kmp/plugin/IosJavaScriptBootstrap.kt"))
        outputs.file(guest)
        commandLine(
            "sh",
            sandboxCore.file("wasm/build-guest.sh").asFile.absolutePath,
            guest.get().asFile.parentFile.absolutePath,
        )
    }
    iosSdks.forEach { sdk ->
        val dir = NativeLibraryLayout.iosSandboxHostDir(this, sdk).absolutePath
        tasks.register("buildIosSandboxHost${sdk.replaceFirstChar(Char::uppercase)}", Exec::class.java) {
            group = "build"
            description = "Builds the iOS plugin sandbox host library for $sdk"
            dependsOn(buildGuest)
            inputs.dir(sandboxCore.dir("ios"))
            inputs.file(guest)
            outputs.file("$dir/libshinsou_wasm_host.a")
            commandLine(
                "/bin/sh", "-c",
                "cmake -S \"$1\" -B \"$2\" -DCMAKE_BUILD_TYPE=Release -DCMAKE_SYSTEM_NAME=iOS " +
                    "-DCMAKE_OSX_SYSROOT=$3 -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 " +
                    "-DSSC_WASM_MODULE=\"$4\" && cmake --build \"$2\" --target shinsou_wasm_host -j $nativeBuildJobs",
                "build-ios-sandbox-host", sandboxCore.dir("ios").asFile.absolutePath, dir, sdk,
                guest.get().asFile.absolutePath,
            )
        }
    }
    tasks.named { it.startsWith("cinteropSandboxWasmIosArm64") }.configureEach {
        dependsOn("buildIosSandboxHostIphoneos")
    }
    tasks.named { it.startsWith("cinteropSandboxWasmIosSimulatorArm64") }.configureEach {
        dependsOn("buildIosSandboxHostIphonesimulator")
    }
}

/** ADR-0004 §4.5 (iOS): translation-core and its engine behind `translationCore`. */
private fun Project.registerIosTranslationLibraries() {
    val translationCore = rootProject.layout.projectDirectory.dir("translation-core")

    // ADR-0004 §4.5 (iOS): translation-core and the engine it links, merged into one static
    // archive per Apple SDK for the `translationCore` cinterop.
    iosSdks.forEach { sdk ->
        val dir = NativeLibraryLayout.iosTranslationCoreDir(this, sdk).absolutePath
        tasks.register("buildIosTranslationCore${sdk.replaceFirstChar(Char::uppercase)}", Exec::class.java) {
            group = "build"
            description = "Builds the offline translation engine for $sdk"
            dependsOn("fetchTranslationCoreDeps")
            inputs.dir(translationCore.dir("src"))
            inputs.dir(translationCore.dir("ios"))
            inputs.dir(translationCore.dir("patches"))
            inputs.file(translationCore.file("CMakeLists.txt"))
            outputs.file("$dir/libshinsou_translate.a")
            commandLine(
                "/bin/sh", "-c",
                "export STC_IOS_SDK=$3 && cmake -S \"$1\" -B \"$2/cmake\" -DCMAKE_BUILD_TYPE=Release " +
                    "-DCMAKE_TOOLCHAIN_FILE=\"$1/ios/ios.toolchain.cmake\" -DSTC_IOS_SDK=$3 && " +
                    "cmake --build \"$2/cmake\" --target shinsou_translation_core -j $nativeBuildJobs && " +
                    "find \"$2/cmake\" -name '*.a' -print0 | xargs -0 xcrun libtool -static -o \"$2/libshinsou_translate.a\"",
                "build-ios-translation-core", translationCore.asFile.absolutePath, dir, sdk,
            )
        }
    }

    // The archives are embedded in the klibs: a rebuilt library must rerun its cinterop.
    tasks.named { it.startsWith("cinteropTranslationCoreIosArm64") }.configureEach {
        dependsOn("buildIosTranslationCoreIphoneos")
        inputs.file(NativeLibraryLayout.iosTranslationCoreDir(project, "iphoneos").resolve("libshinsou_translate.a"))
    }
    tasks.named { it.startsWith("cinteropTranslationCoreIosSimulatorArm64") }.configureEach {
        dependsOn("buildIosTranslationCoreIphonesimulator")
        inputs.file(NativeLibraryLayout.iosTranslationCoreDir(project, "iphonesimulator").resolve("libshinsou_translate.a"))
    }
}

/** ADR-0005 §4 (iOS): ONNX Runtime and OpenCV behind `mangaOnnx` and `mangaOpenCv`. */
private fun Project.registerIosVisionLibraries() {
    // ADR-0005 §4 (iOS): ONNX Runtime and OpenCV for the manga translation, per Apple SDK.
    val visionDir = NativeLibraryLayout.iosVisionDir(this)
    tasks.register("buildIosVision", Exec::class.java) {
        group = "build"
        description = "Fetches ONNX Runtime and builds OpenCV (core, imgproc, photo) for iOS"
        val script = rootProject.file("ios-vision/scripts/build-ios-vision.sh")
        inputs.file(script)
        outputs.files(
            visionDir.resolve("iphoneos/libonnxruntime.a"), visionDir.resolve("iphonesimulator/libonnxruntime.a"),
            visionDir.resolve("iphoneos/libopencv2.a"), visionDir.resolve("iphonesimulator/libopencv2.a"),
        )
        commandLine("/bin/bash", script.absolutePath, visionDir.absolutePath)
    }
    tasks.named { it.startsWith("cinteropMangaOnnx") || it.startsWith("cinteropMangaOpenCv") }.configureEach {
        dependsOn("buildIosVision")
        val sdk = if (name.contains("Simulator")) "iphonesimulator" else "iphoneos"
        inputs.file(visionDir.resolve("$sdk/libonnxruntime.a"))
        inputs.file(visionDir.resolve("$sdk/libopencv2.a"))
    }
}

/** The NDK of the Android external native builds (ADR-0002 S2, ADR-0004 §4.5). */
private const val ANDROID_NDK_VERSION = "27.0.12077973"

/**
 * R5-1: the Android external native build of a module, when it has `src/androidMain/cpp/CMakeLists.txt`:
 * sandbox-core's libshinsou_sandbox.so in :feature-plugin (ADR-0002 S2, the isolated-process sandbox
 * loads it from the app's APK, where the library's native libraries are merged), translation-core's
 * libshinsou_translate.so in the app (ADR-0004 §4.5, arm64-v8a only).
 */
internal fun Project.configureAndroidNativeBuild(android: CommonExtension<*, *, *, *, *, *>) {
    val cmakeLists = file("src/androidMain/cpp/CMakeLists.txt")
    if (!cmakeLists.isFile) return
    android.ndkVersion = ANDROID_NDK_VERSION
    android.defaultConfig.externalNativeBuild.cmake.apply {
        // Only sandbox-core's ABIs are listed here: dependencies keep shipping all of theirs.
        // On another ABI the library is missing and the sandbox fails closed.
        abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        // Optimized in every variant: debug builds are used to test the sandbox too, and an -O0
        // QuickJS is an order of magnitude slower.
        arguments += "-DCMAKE_BUILD_TYPE=Release"
    }
    android.externalNativeBuild.cmake {
        path = cmakeLists
        version = "3.22.1"
    }
}
