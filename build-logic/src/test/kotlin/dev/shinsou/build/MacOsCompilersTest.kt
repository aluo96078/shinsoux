package dev.shinsou.build

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** [effectiveMacOsCompilers]: the compilers whose versions a macOS CMake task fingerprints. */
class MacOsCompilersTest {
    @Test
    fun xcodeCompilersAreTheDefaultForBothLanguages() {
        val xcode = listOf(listOf("xcrun", "clang"), listOf("xcrun", "clang++"))
        assertEquals(xcode, effectiveMacOsCompilers(cc = null, cxx = null))
        assertEquals(xcode, effectiveMacOsCompilers(cc = " ", cxx = ""))
    }

    @Test
    fun theCompilersCMakeTakesFromTheEnvironmentReplaceTheDefaultOneByOne() {
        assertEquals(
            listOf(listOf("/opt/llvm/bin/clang"), listOf("xcrun", "clang++")),
            effectiveMacOsCompilers(cc = "/opt/llvm/bin/clang", cxx = null),
        )
        assertEquals(
            listOf(listOf("xcrun", "clang"), listOf("ccache", "g++-14")),
            effectiveMacOsCompilers(cc = null, cxx = " ccache  g++-14 "),
        )
    }

    @Test
    fun aQuotedCompilerPathWithSpacesStaysOneWord() {
        assertEquals(
            listOf(
                listOf("/Applications/My Xcode.app/usr/bin/clang", "-arch", "arm64"),
                listOf("ccache", "/opt/my llvm/bin/clang++"),
            ),
            effectiveMacOsCompilers(
                cc = "\"/Applications/My Xcode.app/usr/bin/clang\" -arch arm64",
                cxx = "ccache '/opt/my llvm/bin/clang++'",
            ),
        )
        assertEquals(
            listOf(listOf("/opt/my llvm/bin/clang"), listOf("xcrun", "clang++")),
            effectiveMacOsCompilers(cc = "/opt/my\\ llvm/bin/clang", cxx = "  "),
        )
    }

    @Test
    fun cmakeIsPinnedToTheXcodeCompilersItIsFingerprintedWith() {
        assertEquals(
            listOf("CMAKE_C_COMPILER" to "clang", "CMAKE_CXX_COMPILER" to "clang++"),
            macOsCompilerPinTools(effectiveMacOsCompilers(cc = null, cxx = null)),
        )
        // A compiler from the environment is CMake's own to read: only the other one is pinned.
        assertEquals(
            listOf("CMAKE_CXX_COMPILER" to "clang++"),
            macOsCompilerPinTools(effectiveMacOsCompilers(cc = "ccache clang", cxx = null)),
        )
        assertEquals(emptyList(), macOsCompilerPinTools(effectiveMacOsCompilers(cc = "gcc-14", cxx = "g++-14")))
    }

    @Test
    fun commandLinesSplitAsAShellSplitsThem() {
        assertEquals(listOf("a b", "c"), splitCommandLine(""" "a b"   c """))
        assertEquals(listOf("a'b", "c\"d", "e\\f"), splitCommandLine("""a\'b "c\"d" 'e\f'"""))
        // Inside double quotes a backslash is kept before an ordinary character.
        assertEquals(listOf("a\\b\\c"), splitCommandLine(""""a\b\\c""""))
        assertEquals(listOf("pre fix", ""), splitCommandLine("""pre" "fix '' """))
        // An unclosed quote and a trailing backslash are taken as they are.
        assertEquals(listOf("clang", "-D x"), splitCommandLine("""clang "-D x"""))
        assertEquals(listOf("clang\\"), splitCommandLine("""clang\"""))
        assertEquals(listOf("a\$" + "b`c"), splitCommandLine("\"a\\\$" + "b\\`c\""))
        assertEquals(emptyList(), splitCommandLine(" \t "))
    }

    @Test
    fun aCommandThatCannotStartHasNoOutputInsteadOfFailing() {
        assertEquals("", commandOutput(listOf("/no/such/compiler \"quoted", "--version")))
        assertEquals("", commandOutput(emptyList()))
    }

    @Test
    fun aCommandsOutputIsReadWhateverItsExitValue() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        assertEquals("version 1\n", commandOutput(listOf("sh", "-c", "echo 'version 1'; echo noise >&2; exit 3")))
    }

    @Test
    fun gradleReadsTheOutputThroughAValueSourceThatDoesNotFailTheBuild() {
        val project = ProjectBuilder.builder().build()
        val missing = project.providers.of(CommandOutputSource::class.java) {
            parameters.command.set(listOf("/no/such/compiler", "--version"))
        }
        assertEquals("", missing.get())
    }
}
