package dev.shinsou.kmp.plugin.trust

import dev.shinsou.kmp.plugin.storage.InMemoryPluginKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0008 §6: the block list is persisted polymorphically, so its discriminators are the entries'
 * original fully-qualified names, whatever package [PluginBlockEntry] lives in.
 */
class LocalPluginBlocklistPersistenceTest {
    @Test
    fun entriesPersistedBeforeThePackageMoveStillDecode() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        val sha = "a".repeat(64)
        kv.putString(
            KEY,
            """[
              {"type":"dev.shinsou.kmp.plugin.PluginBlockEntry.Package","packageId":"p","publisherId":""},
              {"type":"dev.shinsou.kmp.plugin.PluginBlockEntry.Artifact","sha256":"$sha"},
              {"type":"dev.shinsou.kmp.plugin.PluginBlockEntry.Publisher","publisherId":"pub"}
            ]""",
        )

        assertEquals(
            listOf(PluginBlockEntry.Package("p", ""), PluginBlockEntry.Artifact(sha), PluginBlockEntry.Publisher("pub")),
            LocalPluginBlocklist(kv).entries(),
        )
    }

    @Test
    fun entriesAreWrittenUnderTheOriginalDiscriminators() = runTest {
        val kv = InMemoryPluginKeyValueStore()
        LocalPluginBlocklist(kv).block(PluginBlockEntry.Publisher("pub"))

        val stored = kv.getString(KEY).orEmpty()
        assertTrue("dev.shinsou.kmp.plugin.PluginBlockEntry.Publisher" in stored, stored)
    }

    private companion object {
        const val KEY = "plugin.blocklist.v1"
    }
}
