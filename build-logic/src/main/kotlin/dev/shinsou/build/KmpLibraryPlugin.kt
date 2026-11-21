package dev.shinsou.build

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * A library module of the app (R5-1, docs/architecture/0006 §4.2): the Kotlin targets and
 * source-set hierarchy of [KmpPlugin], an Android library, detekt and Kover of [QualityPlugin].
 * The module script keeps its dependencies and, when it has Compose code, the Compose plugins.
 */
public class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("dev.detekt")
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        pluginManager.apply(QualityPlugin::class.java)
        pluginManager.apply(KmpPlugin::class.java)

        // A distinct Kotlin module name for the product classes: a bare "core" could clash with
        // another library's META-INF/core.kotlin_module in the packaged app.
        tasks.withType(KotlinJvmCompile::class.java)
            .matching { !it.name.contains("Test") }
            .configureEach { compilerOptions.moduleName.set("shinsou-${project.name}") }

        extensions.getByType(LibraryExtension::class.java).apply {
            namespace = "dev.shinsou.kmp.module." + name.replace('-', '.')
            compileSdk = catalogVersion("android-compileSdk").toInt()
            defaultConfig.minSdk = catalogVersion("android-minSdk").toInt()
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            sourceSets.getByName("test").resources.srcDir(hostNativeTestResourcesDir())
            // R5-3: the R8 keep rules for what this module owns (JNI entry points, reflective
            // libraries, components named outside the code) travel with it into the app's release build.
            if (file(CONSUMER_RULES).isFile) defaultConfig.consumerProguardFiles(CONSUMER_RULES)
            configureAndroidNativeBuild(this)
        }
        configureHostNativeTestResources()
    }
}

private const val CONSUMER_RULES = "consumer-rules.pro"
