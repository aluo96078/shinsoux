package dev.shinsou.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import javax.inject.Inject

/**
 * The Kotlin targets (Android, desktop JVM, iOS device and simulator) and the manual source-set
 * hierarchy (jvmCommonMain/Test shared by Android and desktop, iosMain/Test) of every Kotlin module,
 * and the iOS cinterops of the module that owns them (R5-1, docs/architecture/0006: a module owns a
 * cinterop when its `src/nativeInterop/cinterop/<name>.def` is there). Dependencies stay in the
 * module scripts; the iOS framework is IosFrameworkPlugin's.
 */
public class KmpPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            target.configureKotlin(target.extensions.getByType(KotlinMultiplatformExtension::class.java))
        }
    }
}

/**
 * The app's iOS framework, ShinsouKit, that the Xcode project links (iosApp/). Its header has the
 * app module's public API; `shinsouIosFramework { export(":module") }` adds a library module's
 * (R5-1: the Swift app calls into more than the app module).
 */
public class IosFrameworkPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("shinsouIosFramework", IosFrameworkExtension::class.java, target)
        target.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            target.extensions.getByType(KotlinMultiplatformExtension::class.java)
                .targets.withType(KotlinNativeTarget::class.java).configureEach {
                    binaries.framework {
                        baseName = "ShinsouKit"
                        isStatic = true
                        binaryOption("bundleId", "dev.aluo.shinsoux.shared")

                        // Kotlin/Native 2.4.0's interprocedural static-initializer analysis can
                        // exhaust the compiler heap while linking this optimized iOS framework. This
                        // phase only removes redundant initializer checks, so skipping it preserves
                        // initialization semantics while avoiding the pathological memory spike
                        // during Archive/Ad Hoc builds.
                        if (buildType == NativeBuildType.RELEASE) {
                            freeCompilerArgs += "-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase"
                        }
                    }
                }
        }
    }
}

public abstract class IosFrameworkExtension @Inject constructor(private val project: Project) {
    /** Puts the public API of the module at [path] into ShinsouKit's header, unprefixed. */
    public fun export(path: String) {
        val module = project.project(path)
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.sourceSets.getByName("iosMain").dependencies { api(module) }
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.withType(Framework::class.java).configureEach { export(module) }
        }
    }
}

internal object NativeLibraryLayout {
    fun iosSdk(target: KonanTarget): String =
        if (target == KonanTarget.IOS_ARM64) "iphoneos" else "iphonesimulator"

    fun iosSandboxHostDir(project: Project, sdk: String): File =
        project.layout.buildDirectory.dir("sandbox-ios-$sdk").get().asFile

    fun iosTranslationCoreDir(project: Project, sdk: String): File =
        project.layout.buildDirectory.dir("translation-ios-$sdk").get().asFile

    fun iosVisionDir(project: Project): File = project.layout.buildDirectory.dir("ios-vision").get().asFile
}

private fun Project.configureKotlin(kotlin: KotlinMultiplatformExtension) {
    kotlin.androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    kotlin.jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    kotlin.iosArm64()
    kotlin.iosSimulatorArm64()

    kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
        configureCinterops(this)
        binaries.all {
            linkerOpts("-lsqlite3")
        }
    }

    // kotlin.mpp.applyDefaultHierarchyTemplate=false: the hierarchy is declared here.
    val sourceSets = kotlin.sourceSets
    val commonMain = sourceSets.getByName("commonMain")
    val commonTest = sourceSets.getByName("commonTest")
    val jvmCommonMain = sourceSets.create("jvmCommonMain") { dependsOn(commonMain) }
    sourceSets.getByName("androidMain").dependsOn(jvmCommonMain)
    val iosMain = sourceSets.create("iosMain") { dependsOn(commonMain) }
    sourceSets.getByName("iosArm64Main").dependsOn(iosMain)
    sourceSets.getByName("iosSimulatorArm64Main").dependsOn(iosMain)
    // ADR-0002 S4: iOS-only tests (the WASM sandbox on the simulator).
    val iosTest = sourceSets.create("iosTest") { dependsOn(commonTest) }
    sourceSets.getByName("iosArm64Test").dependsOn(iosTest)
    sourceSets.getByName("iosSimulatorArm64Test").dependsOn(iosTest)
    sourceSets.getByName("desktopMain").dependsOn(jvmCommonMain)
    // R4-5 (docs/architecture/0006): the in-process Rhino engine only serves tests and parity
    // checks (jvmCommonTest's dependencies); packages always run sandboxed.
    val jvmCommonTest = sourceSets.create("jvmCommonTest") { dependsOn(commonTest) }
    sourceSets.getByName("androidUnitTest").dependsOn(jvmCommonTest)
    sourceSets.getByName("desktopTest").dependsOn(jvmCommonTest)
}

private fun Project.configureCinterops(target: KotlinNativeTarget) {
    val sdk = NativeLibraryLayout.iosSdk(target.konanTarget)
    val iosVisionDir = NativeLibraryLayout.iosVisionDir(this)
    val visionSdk = iosVisionDir.resolve(sdk)
    val main = target.compilations.getByName("main")
    fun cinterop(name: String, configure: DefaultCInteropSettings.() -> Unit) {
        val def = file("src/nativeInterop/cinterop/$name.def")
        if (def.isFile) main.cinterops.create(name) { defFile(def); configure() }
    }
    cinterop("networkReceive") {
        includeDirs(file("src/nativeInterop/cinterop"))
    }
    // ADR-0002 §4.5: the plugin sandbox host (WAMR + the sandbox-core WASM guest).
    cinterop("sandboxWasm") {
        includeDirs(rootProject.file("sandbox-core/ios"))
        extraOpts("-libraryPath", NativeLibraryLayout.iosSandboxHostDir(this@configureCinterops, sdk).absolutePath)
    }
    // ADR-0004 §4.5: the offline translation engine.
    cinterop("translationCore") {
        includeDirs(rootProject.file("translation-core/src"))
        extraOpts("-libraryPath", NativeLibraryLayout.iosTranslationCoreDir(this@configureCinterops, sdk).absolutePath)
    }
    // ADR-0005 §4: the manga translation's inference and image libraries.
    cinterop("mangaOnnx") {
        includeDirs(iosVisionDir.resolve("onnxruntime-headers"))
        extraOpts("-libraryPath", visionSdk.absolutePath)
    }
    cinterop("mangaOpenCv") {
        compilerOpts("-F${visionSdk.absolutePath}")
        extraOpts("-libraryPath", visionSdk.absolutePath)
    }
}
