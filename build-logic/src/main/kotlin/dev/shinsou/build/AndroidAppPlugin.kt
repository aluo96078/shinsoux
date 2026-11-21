package dev.shinsou.build

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import javax.inject.Inject

/**
 * The Android application: identity, versions, the native build of translation-core, signing
 * (Signing.kt), packaging, the host native resources Android unit tests need, and the guard that
 * keeps device tests off a real installation.
 */
public class AndroidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.shinsouBuild().androidReleaseSigning.checkComplete()
        target.shinsouBuild().androidPublicDebugSigning.checkComplete()
        target.pluginManager.withPlugin("com.android.application") {
            target.configureHostNativeTestResources()
            target.configureAndroid(target.extensions.getByType(ApplicationExtension::class.java))
            target.guardDeviceTests()
        }
    }
}

internal abstract class ArchiveAccess @Inject constructor(val archives: ArchiveOperations)

// Android local unit tests execute Android bytecode on the host JVM. The libsodium and JNA Android
// AARs only carry device JNI libraries, while their loaders correctly observe the host OS during
// those tests and request the matching JVM native resources. Resolve the host artifacts in an
// isolated configuration and expose only their native resources to Android unit tests; no JVM
// classes or libraries are added to the application classpath.
internal fun Project.configureHostNativeTestResources() {
    val hostResources = configurations.create("androidHostNativeTestResources") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, LibraryElements.JAR),
            )
        }
    }
    dependencies.add(
        hostResources.name,
        "com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings-jvm:${catalogVersion("libsodium")}",
    )
    // Must match the JNA version required by the configured libsodium Android AAR; JNA rejects a
    // native dispatch library from a different release.
    dependencies.add(hostResources.name, "net.java.dev.jna:jna:${catalogVersion("jna")}@jar")

    val archives = objects.newInstance(ArchiveAccess::class.java).archives
    val prepare = tasks.register("prepareAndroidUnitTestLibsodiumResources", Sync::class.java) {
        from(
            hostResources.incoming.files.elements.map { artifacts ->
                artifacts.map { archive -> archives.zipTree(archive.asFile) }
            },
        ) {
            include("libdynamic-*")
            include("com/sun/jna/**/libjnidispatch.*")
            include("com/sun/jna/**/jnidispatch.*")
        }
        into(hostNativeTestResourcesDir())
    }
    tasks.named { it.startsWith("process") && it.endsWith("UnitTestJavaRes") }.configureEach {
        dependsOn(prepare)
    }
}

internal fun Project.hostNativeTestResourcesDir(): Provider<Directory> =
    layout.buildDirectory.dir("generated/androidUnitTest/libsodiumResources")

private fun Project.configureAndroid(android: ApplicationExtension) {
    val settings = shinsouBuild()
    android.apply {
        namespace = "dev.aluo.shinsoux"
        compileSdk = catalogVersion("android-compileSdk").toInt()

        defaultConfig {
            applicationId = "dev.aluo.shinsoux"
            minSdk = catalogVersion("android-minSdk").toInt()
            targetSdk = catalogVersion("android-targetSdk").toInt()
            versionCode = settings.releaseVersionCode.get()
            versionName = settings.releaseDisplayVersion.get()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        // translation-core, for arm64-v8a (R5-1: sandbox-core's library is :feature-plugin's).
        configureAndroidNativeBuild(this)

        val publicDebug = settings.androidPublicDebugSigning
        val release = settings.androidReleaseSigning
        signingConfigs {
            if (publicDebug.configured) {
                create("publicDebug") {
                    storeFile = file(requireNotNull(publicDebug.storeFile))
                    storePassword = requireNotNull(publicDebug.storePassword)
                    keyAlias = requireNotNull(publicDebug.keyAlias)
                    keyPassword = requireNotNull(publicDebug.keyPassword)
                }
            }
            if (release.configured) {
                create("release") {
                    storeFile = file(requireNotNull(release.storeFile))
                    storePassword = requireNotNull(release.storePassword)
                    keyAlias = requireNotNull(release.keyAlias)
                    keyPassword = requireNotNull(release.keyPassword)
                }
            }
        }

        buildTypes {
            getByName("debug") {
                if (settings.sandboxDeviceTest.get()) applicationIdSuffix = ".sandboxtest"
                if (publicDebug.configured) {
                    signingConfig = signingConfigs.getByName("publicDebug")
                }
            }
            // R5-3 (docs/architecture/0006): the published APK is this variant, shrunk and
            // obfuscated by R8 (keep rules: ANDROID_R8_RULES here, consumer-rules.pro of the library
            // modules). Signed with the release key when its four variables are set, otherwise with
            // the fixed public test key CI already restores for the published APK, otherwise unsigned.
            getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), ANDROID_R8_RULES)
                when {
                    release.configured -> signingConfig = signingConfigs.getByName("release")
                    publicDebug.configured -> signingConfig = signingConfigs.getByName("publicDebug")
                }
            }
        }

        buildFeatures.compose = true
        // Java compiled into the app (generated sources included) matches Kotlin's JVM target.
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        sourceSets.getByName("test").resources.srcDir(hostNativeTestResourcesDir())

        packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // minSdk 29 would store dex and native libraries uncompressed (about 2.5x the APK size).
        // The APK is downloaded from GitHub Releases, not split by a store, so keep it compressed.
        packaging.dex.useLegacyPackaging = true
        packaging.jniLibs.useLegacyPackaging = true
        // ADR-0005: manga translation runs on arm64 devices only. Its inference and image
        // libraries add about 60 MB per ABI (x86_64 alone would add over 100 MB), so the other
        // ABIs are left out and the feature reports itself unavailable there (emulators included).
        packaging.jniLibs.excludes += listOf("armeabi-v7a", "x86", "x86_64").flatMap { abi ->
            listOf("lib/$abi/libonnxruntime.so", "lib/$abi/libonnxruntime4j_jni.so", "lib/$abi/libopencv_java4.so")
        }
        // The NPU's QNN libraries (about 60 MB) are not shipped: Snapdragon devices download them
        // from Maven Central when the user picks the NPU (docs/research/manga-gpu-npu.md).
        packaging.jniLibs.excludes += "**/libQnn*.so"
    }
}

private fun Project.guardDeviceTests() {
    val ownApplicationId = shinsouBuild().sandboxDeviceTest.get()
    tasks.named { it.startsWith("connected") && it.endsWith("AndroidTest") }.configureEach {
        doFirst {
            check(ownApplicationId) {
                "Run device tests with -PshinsouSandboxDeviceTest=true: they uninstall the app afterwards"
            }
        }
    }
}

/** The app's own R8 rules for the release variant (R5-3); each library module adds its consumer-rules.pro. */
private const val ANDROID_R8_RULES = "proguard-android-rules.pro"

internal fun Project.catalogVersion(alias: String): String =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs").findVersion(alias).get().requiredVersion
