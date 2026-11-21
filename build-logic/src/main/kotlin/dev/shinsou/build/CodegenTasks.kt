package dev.shinsou.build

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.net.IDN
import javax.inject.Inject

/**
 * The generated sources (R5-2, docs/architecture/0006). Each module registers the ones whose
 * package it holds (R5-1) through the `shinsouCodegen` extension: the build info (`buildinfo`), the
 * Public Suffix List (`plugin.network`), and Android's launcher icon copied from the Compose resource.
 */
public class CodegenPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("shinsouCodegen", CodegenExtension::class.java, target)
    }
}

public abstract class CodegenExtension @Inject constructor(private val project: Project) {
    /** `dev.shinsou.kmp.buildinfo.SHINSOU_APP_VERSION` in commonMain (task `generateBuildInfo`). */
    public fun buildInfo(): Unit = with(project) {
        val generateBuildInfo = tasks.register("generateBuildInfo", GenerateBuildInfo::class.java) {
            displayVersion.set(shinsouBuild().releaseDisplayVersion)
            outputDirectory.set(layout.buildDirectory.dir("generated/buildInfo/commonMain/kotlin"))
        }
        commonMainSource(generateBuildInfo.map { it.outputDirectory })
    }

    /** The vendored `psl/public_suffix_list.dat` as commonMain data (task `generatePublicSuffixData`). */
    public fun publicSuffixData(): Unit = with(project) {
        val generatePublicSuffixData = tasks.register("generatePublicSuffixData", GeneratePublicSuffixData::class.java) {
            listFile.set(layout.projectDirectory.file("psl/public_suffix_list.dat"))
            outputDirectory.set(layout.buildDirectory.dir("generated/publicSuffix/commonMain/kotlin"))
        }
        commonMainSource(generatePublicSuffixData.map { it.outputDirectory })
    }

    /** The Android launcher icon, copied from [icon] (task `copyAndroidAppIcon`). */
    public fun androidAppIcon(icon: String): Unit = with(project) {
        val copyAndroidAppIcon = tasks.register("copyAndroidAppIcon", CopyAppIcon::class.java) {
            this.icon.set(rootProject.layout.projectDirectory.file(icon))
            // outputDirectory is set by addGeneratedSourceDirectory below (build/generated/res/copyAndroidAppIcon).
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
                variant.sources.res?.addGeneratedSourceDirectory(copyAndroidAppIcon, CopyAppIcon::outputDirectory)
            }
        }
    }

    private fun commonMainSource(directory: Any) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
                .getByName("commonMain").kotlin.srcDir(directory)
        }
    }
}

/**
 * Empties a generator's output directory first, so a file an earlier version wrote elsewhere in it
 * (a moved package, a renamed file) does not stay behind and keep compiling.
 */
private fun freshOutputDirectory(directory: DirectoryProperty): File =
    directory.get().asFile.apply {
        deleteRecursively()
        mkdirs()
    }

/**
 * The vendored Public Suffix List (psl/public_suffix_list.dat, see psl/README.md) as Kotlin data:
 * rules only, internationalized ones converted to ASCII (punycode) with IDN so the app
 * compares plain lowercase ASCII host names on every platform. Built at compile time only; the app
 * never downloads the list.
 */
public abstract class GeneratePublicSuffixData : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val listFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val rules = listFile.get().asFile.readLines()
            .map { it.substringBefore(' ').substringBefore('\t').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map { rule ->
                val prefix = when {
                    rule.startsWith("!") -> "!"
                    rule.startsWith("*.") -> "*."
                    else -> ""
                }
                val ascii = IDN.toASCII(rule.removePrefix(prefix), IDN.ALLOW_UNASSIGNED).lowercase()
                require(ascii.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) { "Unexpected rule '$rule'" }
                prefix + ascii
            }
            .distinct()
        require(rules.size > 5_000) { "Public Suffix List looks truncated (${rules.size} rules)" }
        val chunks = rules.joinToString("\n").chunked(30_000)
        val directory = freshOutputDirectory(outputDirectory).resolve("dev/shinsou/kmp/plugin/network")
        directory.mkdirs()
        directory.resolve("PublicSuffixData.kt").writeText(
            buildString {
                appendLine("// Generated from feature-plugin/psl/public_suffix_list.dat by generatePublicSuffixData. Do not edit.")
                appendLine("package dev.shinsou.kmp.plugin.network")
                appendLine()
                appendLine("internal val PUBLIC_SUFFIX_RULE_CHUNKS: Array<String> = arrayOf(")
                chunks.forEach { chunk ->
                    append("    \"")
                    chunk.forEach { c -> append(if (c == '\n') "\\n" else c.toString()) }
                    appendLine("\",")
                }
                appendLine(")")
            },
        )
    }
}

/**
 * The display version (`-PreleaseDisplayVersion`, else the default above) as a Kotlin constant, so
 * the About screen, backups and the sync device record always name the version that was built.
 */
public abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val displayVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val version = displayVersion.get()
        require(Regex("""[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?""").matches(version)) {
            "Unexpected release display version '$version'"
        }
        val directory = freshOutputDirectory(outputDirectory).resolve("dev/shinsou/kmp/buildinfo")
        directory.mkdirs()
        directory.resolve("ShinsouBuildInfo.kt").writeText(
            buildString {
                appendLine("// Generated by generateBuildInfo from the release display version. Do not edit.")
                appendLine("package dev.shinsou.kmp.buildinfo")
                appendLine()
                appendLine("public const val SHINSOU_APP_VERSION: String = \"$version\"")
            },
        )
    }
}

/**
 * R2-6 (0006): the app icon has one source, the Compose resource; Android's launcher icon is
 * copied from it at build time. (Xcode compiles the iOS asset catalog before Gradle runs, so the
 * iOS copy stays committed; AppIconCopiesTest keeps it identical.)
 */
public abstract class CopyAppIcon : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val drawable = freshOutputDirectory(outputDirectory).resolve("drawable")
        drawable.mkdirs()
        icon.get().asFile.copyTo(drawable.resolve("shinsou_icon.png"), overwrite = true)
    }
}
