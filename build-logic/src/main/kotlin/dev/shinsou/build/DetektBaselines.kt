package dev.shinsou.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * How a module's detekt baselines are stored (0007 §7 follow-up 6, [QualityPlugin]).
 *
 * detekt reads exactly one baseline file per task, and the typed `detektMainDesktop` and
 * `detektDebugAndroid` tasks both analyse the module's common sources, so a one-file-per-task
 * layout listed every common finding twice. Each finding is now listed once, in
 * `config/detekt/<module>/`:
 *
 *  - `baseline-common.xml`: reported by both typed tasks (commonMain, jvmCommon);
 *  - `baseline-main.xml`: reported only by `detektMainDesktop` (desktopMain);
 *  - `baseline-debug.xml`: reported only by `detektDebugAndroid` and not by `androidMainSourceSet`;
 *  - `baseline-androidMainSourceSet.xml`: androidMain, analysed by `detektAndroidMainSourceSet` and,
 *    as part of the debug compilation, by `detektDebugAndroid`;
 *  - `baseline-iosMainSourceSet.xml`: iosMain, untyped (`detektIosMainSourceSet`).
 *
 * A task's baseline is the union of the files it reads ([MergeDetektBaselines], written to the
 * build directory), so what each task suppresses is exactly what it suppressed before. Regenerate
 * with `./gradlew detektBaselineProduct` ([SplitDetektBaselines]); the baselines may only shrink
 * (scripts/ci/check_baselines_shrink.sh counts every finding once).
 */
internal object DetektBaselineLayout {
    /** What a product detekt task suppresses: the baseline files of these kinds, merged. */
    class Task(val detektTask: String, val createTask: String, val kind: String, val reads: List<String>)

    val product: List<Task> = listOf(
        Task("detektMainDesktop", "detektBaselineMainDesktop", "main", listOf("common", "main")),
        Task("detektDebugAndroid", "detektBaselineDebugAndroid", "debug", listOf("common", "debug", "androidMainSourceSet")),
        Task("detektAndroidMainSourceSet", "detektBaselineAndroidMainSourceSet", "androidMainSourceSet", listOf("androidMainSourceSet")),
        Task("detektIosMainSourceSet", "detektBaselineIosMainSourceSet", "iosMainSourceSet", listOf("iosMainSourceSet")),
    )

    fun fileName(kind: String): String = "baseline-$kind.xml"

    private const val HEAD = "<?xml version=\"1.0\" ?>\n<SmellBaseline>\n  <ManuallySuppressedIssues/>\n  <CurrentIssues>\n"
    private const val FOOT = "  </CurrentIssues>\n</SmellBaseline>\n"
    private val ENTRY = Regex("""^\s*(<ID>.*</ID>)\s*$""", RegexOption.MULTILINE)

    /** The `<ID>` entries of a baseline file, in file order; a missing file has none. */
    fun entries(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (Regex("""<ManuallySuppressedIssues>""").containsMatchIn(text)) {
            throw GradleException("${file.path}: manually suppressed issues are not supported by the merged baselines")
        }
        return ENTRY.findAll(text).map { it.groupValues[1] }.toList()
    }

    fun render(entries: Collection<String>): String =
        HEAD + entries.joinToString("") { "    $it\n" } + FOOT
}

/** One task's baseline: the entries of [parts] (the ones that exist), each once, as a detekt baseline file. */
@CacheableTask
public abstract class MergeDetektBaselines : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val parts: ConfigurableFileCollection

    @get:OutputFile
    public abstract val merged: RegularFileProperty

    @TaskAction
    public fun merge() {
        val entries = LinkedHashSet<String>()
        parts.files.forEach { entries += DetektBaselineLayout.entries(it) }
        merged.get().asFile.apply { parentFile.mkdirs() }.writeText(DetektBaselineLayout.render(entries))
    }
}

/**
 * `detektBaselineProduct`: rewrites a module's committed baselines from what its product detekt
 * tasks report now. The create-baseline tasks write the full per-task findings to
 * `build/detekt/regenerated/` (their output is redirected there, so they never put the duplicated
 * layout back); this splits them into the layout of [DetektBaselineLayout]. Findings that no longer
 * exist drop out, so the result only shrinks unless the code gained findings.
 */
public abstract class SplitDetektBaselines : DefaultTask() {
    /** The kinds whose create-baseline task exists in this module (a module has no Android target, say). */
    @get:Input
    public abstract val kinds: SetProperty<String>

    @get:Internal
    public abstract val regenerated: DirectoryProperty

    @get:Internal
    public abstract val baselineDirectory: DirectoryProperty

    @TaskAction
    public fun split() {
        val kinds = kinds.get()
        val fresh = { kind: String -> if (kind in kinds) DetektBaselineLayout.entries(regenerated.get().file(DetektBaselineLayout.fileName(kind)).asFile) else null }
        val main = fresh("main")
        val debug = fresh("debug")
        val androidMain = fresh("androidMainSourceSet")
        val iosMain = fresh("iosMainSourceSet")
        val debugSet = debug.orEmpty().toSet()
        val mainSet = main.orEmpty().toSet()
        val androidSet = androidMain.orEmpty().toSet()
        write("common", if (main != null && debug != null) main.filter { it in debugSet } else emptyList())
        write("main", main.orEmpty().filter { debug == null || it !in debugSet })
        write("debug", debug?.filter { it !in mainSet && it !in androidSet }.orEmpty())
        androidMain?.let { write("androidMainSourceSet", it) }
        iosMain?.let { write("iosMainSourceSet", it) }
    }

    /** A kind without findings has no file (detekt runs a task whose baseline file is missing without one). */
    private fun write(kind: String, entries: List<String>) {
        val file = baselineDirectory.get().file(DetektBaselineLayout.fileName(kind)).asFile
        if (entries.isEmpty()) {
            file.delete()
        } else {
            file.parentFile.mkdirs()
            file.writeText(DetektBaselineLayout.render(entries))
        }
    }
}
