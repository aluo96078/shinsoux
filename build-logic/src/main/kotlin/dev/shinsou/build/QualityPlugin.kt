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
        // R2-1 (0006): existing findings live in the baseline; it may only shrink. Each module has its
        // own (R5-1), config/detekt/<module>/baseline-<source set>.xml, so regenerating one module's
        // baseline leaves the others' entries alone; scripts/ci/check_baselines_shrink.sh adds them up.
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

            // What CI runs: the desktop and Android (debug variant) compilations with type resolution
            // (common, JVM and platform sources; the serialization plugin and expect/actual are not
            // seen there, so a few type-based rules are approximate) and the Android and iOS source
            // sets without it. detekt has no typed task for Kotlin/Native targets (C6, 0007), so iOS
            // stays untyped. The typed Android baselines are config/detekt/<module>/baseline-debug.xml.
            tasks.register("detektProduct") {
                group = "verification"
                description = "Runs detekt over every product source set against the committed baselines."
                dependsOn("detektMainDesktop", "detektDebugAndroid", "detektAndroidMainSourceSet", "detektIosMainSourceSet")
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
