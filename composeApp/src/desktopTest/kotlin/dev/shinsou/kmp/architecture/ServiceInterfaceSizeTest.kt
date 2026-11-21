package dev.shinsou.kmp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** R4-2 (docs/architecture/0006): the UI's service interfaces stay small enough to depend on one at a time. */
class ServiceInterfaceSizeTest {
    // R5-1: from the repository root (the tests run in composeApp/); the files are in their modules.
    private val files = listOf(
        "../ui/src/commonMain/kotlin/dev/shinsou/kmp/ui/AppServices.kt",
        "../feature-plugin/src/commonMain/kotlin/dev/shinsou/kmp/plugin/BrowseContracts.kt",
    )
    private val member = Regex("""^ {4}(?:suspend )?(?:fun|val|var) """)

    @Test
    fun noServiceInterfaceHasMoreThanFifteenMembers() {
        val tooLarge = files.flatMap { path ->
            val lines = File(path).readLines()
            lines.indices.filter { lines[it].startsWith("interface ") }.mapNotNull { start ->
                val end = (start until lines.size).first { lines[it] == "}" }
                val count = (start + 1 until end).count { member.containsMatchIn(lines[it]) }
                if (count > MAX_MEMBERS) "${lines[start].substringBefore(':').trim()}: $count" else null
            }
        }
        assertTrue(tooLarge.isEmpty(), "Split these interfaces (max $MAX_MEMBERS members): $tooLarge")
    }

    private companion object {
        const val MAX_MEMBERS = 15
    }
}
