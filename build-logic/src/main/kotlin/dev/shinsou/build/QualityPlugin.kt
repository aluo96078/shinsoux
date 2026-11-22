package dev.shinsou.build

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Static analysis (detekt, R2-1) and coverage reporting (Kover, R2-5) of docs/architecture/0006. */
public class QualityPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        // R2-1 (0006): existing findings live in the baselines; they may only shrink. Each module has
        // its own (R5-1), config/detekt/<module>/baseline-<kind>.xml, and each finding is listed once
        // (0007 §7 follow-up 6): detekt reads one file per task, so a task's baseline is the merge of
        // the kinds it reads (DetektBaselineLayout) and `detektBaselineProduct` rewrites the files.
        // scripts/ci/check_baselines_shrink.sh counts the unique findings of all of them.
        pluginManager.withPlugin("dev.detekt") {
            extensions.getByType(DetektExtension::class.java).apply {
                buildUponDefaultConfig.set(true)
                parallel.set(true)
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                baseline.set(rootProject.file("config/detekt/${project.name}/baseline.xml"))
            }

            // Generated sources (SQLDelight's query code, C4 of 0007) are not the app's code to lint.
            tasks.withType(Detekt::class.java).configureEach { exclude { it.file.isGenerated() } }
            tasks.withType(DetektCreateBaselineTask::class.java).configureEach { exclude { it.file.isGenerated() } }

            registerBaselineLayout()

            // What CI runs: the desktop and Android (debug variant) compilations with type resolution
            // (common, JVM and platform sources; the serialization plugin and expect/actual are not
            // seen there, so a few type-based rules are approximate) and the Android and iOS source
            // sets without it. detekt has no typed task for Kotlin/Native targets (C6, 0007), so iOS
            // stays untyped. Which baseline files each of them reads is DetektBaselineLayout.product.
            tasks.register("detektProduct") {
                group = "verification"
                description = "Runs detekt over every product source set against the committed baselines."
                dependsOn(DetektBaselineLayout.product.map { it.detektTask })
            }
        }

        // R2-5 (0006): coverage is reported; generated code is left out of it. The app's report
        // merges every module's (its kover(project(...)) dependencies) and is held to
        // MERGED_LINE_COVERAGE_FLOOR: :composeApp:koverVerifyDesktop, run by checks.yml.
        pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
            extensions.getByType(KoverProjectExtension::class.java).reports {
                filters {
                    excludes {
                        packages("dev.aluo.shinsoux.generated.resources")
                        // SQLDelight's generated query code (SqlDelightPlugin): its .sq files live in `*.sql` packages.
                        packages("*.sql")
                        classes("*ComposableSingletons*", "*.BuildInfoKt")
                        annotatedBy("androidx.compose.runtime.Composable")
                    }
                }
                if (path == APP_PROJECT_PATH) {
                    verify {
                        rule("Merged line coverage") { minBound(MERGED_LINE_COVERAGE_FLOOR) }
                    }
                }
            }
        }
    }
}

/**
 * The baseline layout of [DetektBaselineLayout]: the product tasks that read several kinds get
 * their merged baseline, the create-baseline tasks write to the build directory (so they cannot
 * put the duplicated layout back) and `detektBaselineProduct` splits that into the committed files.
 */
private fun Project.registerBaselineLayout() {
    val committed = rootProject.layout.projectDirectory.dir("config/detekt/$name")
    val regenerated = layout.buildDirectory.dir("detekt/regenerated")
    val byCreateTask = DetektBaselineLayout.product.associateBy { it.createTask }

    val merged = DetektBaselineLayout.product.filter { it.reads.size > 1 }.associate { product ->
        product.detektTask to tasks.register("merge${product.detektTask.removePrefix("detekt")}Baseline", MergeDetektBaselines::class.java) {
            parts.from(product.reads.map { committed.file(DetektBaselineLayout.fileName(it)) })
            merged.set(layout.buildDirectory.file("detekt/merged/${DetektBaselineLayout.fileName(product.kind)}"))
        }
    }
    tasks.withType(Detekt::class.java).configureEach {
        val merge = merged[name] ?: return@configureEach
        baseline.set(merge.flatMap { it.merged })
    }

    // The create-baseline tasks this module has (a module without an Android target has no debug one).
    val createTasks = tasks.withType(DetektCreateBaselineTask::class.java).matching { it.name in byCreateTask }
    tasks.register("detektBaselineProduct", SplitDetektBaselines::class.java) {
        group = "verification"
        description = "Rewrites config/detekt/$name/baseline-*.xml from what the product detekt tasks report now."
        baselineDirectory.set(committed)
        this.regenerated.set(regenerated)
        kinds.set(provider { createTasks.map { byCreateTask.getValue(it.name).kind }.toSet() })
        dependsOn(createTasks)
    }
    createTasks.configureEach {
        baseline.set(regenerated.map { it.file(DetektBaselineLayout.fileName(byCreateTask.getValue(name).kind)) })
    }
}

private fun java.io.File.isGenerated(): Boolean = invariantSeparatorsPath.contains("/build/generated/")

/** The module whose Kover report merges all the others'. */
private const val APP_PROJECT_PATH = ":composeApp"

/**
 * Kover ratchet (0006 R2-5): the lowest merged line coverage, in percent, that
 * `:composeApp:koverVerifyDesktop` accepts. It may only be raised, never lowered: when coverage has
 * grown, raise it to the new measurement rounded down to a whole percent.
 *
 * Measured 2026-11-18: 75.9 % on macOS with every desktop test. The Linux CI runner has no sandbox
 * helper and skips the macOS-only tests (Keychain, ImageIO AVIF, the WKWebView helper); measured
 * without the helper and with the pinned plugin fixtures, less every line of the macOS-only
 * classes, that is about 75.3 %, so the bound is 75.
 */
internal const val MERGED_LINE_COVERAGE_FLOOR: Int = 75
