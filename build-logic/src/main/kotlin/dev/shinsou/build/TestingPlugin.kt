package dev.shinsou.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/** What the test tasks read from outside their source sets: the sandbox helper, fixtures, host paths. */
public class TestingPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val settings = shinsouBuild()
        tasks.withType(Test::class.java).configureEach {
            // The sandbox tests run this helper: a rebuilt helper must rerun them, not reuse old results.
            inputs.files(
                rootProject.layout.projectDirectory.file("sandbox-core/build/release/shinsou-sandbox"),
                rootProject.layout.projectDirectory.file("sandbox-core/build/release/shinsou-sandbox.exe"),
            )
                .withPropertyName("sandboxHelper")
                .optional()
            systemProperty("shinsou.pluginRepo", settings.pluginSources.map { it.absolutePath }.get())
            // Plugin publishing contract shared with the @shinsou/plugin CLI (PluginContractVectorTest).
            systemProperty(
                "shinsou.pluginContractFixtures",
                rootProject.layout.projectDirectory.dir("fixtures/plugin-contract/v1").asFile.absolutePath,
            )
            systemProperty(
                "shinsou.writePluginContractVectors",
                providers.gradleProperty("writePluginContractVectors").orElse("false").get(),
            )
        }

        // ADR-0005: the manga pipeline test on the simulator reads its models and page from the
        // host; forward their paths (simctl passes SIMCTL_CHILD_* variables to the test process).
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            // The plugin repository's sources as desktop test resources (the bundled-plugin tests).
            extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
                .matching { it.name == "desktopTest" }
                .configureEach { resources.srcDir(settings.pluginSources) }
            tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach {
                listOf("SHINSOU_MANGA_PACKS", "SHINSOU_MANGA_PAGE", "SHINSOU_MANGA_EXPECTED", "SHINSOU_MANGA_ART")
                    .forEach { name ->
                        System.getenv(name)?.let { environment("SIMCTL_CHILD_$name", it) }
                    }
            }
        }
    }
}
