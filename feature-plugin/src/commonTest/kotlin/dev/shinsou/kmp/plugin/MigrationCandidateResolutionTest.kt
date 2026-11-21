package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** R5-1: from BrowseSearchTest (:ui), which keeps the migration targets the browse screen offers. */
class MigrationCandidateResolutionTest {
    @Test
    fun theInstalledSourceNamesTheTitlesCurrentSourceAndKind() {
        val novelKey = SourceKey(2, "zh.current", "1")
        val sources = listOf(
            BrowseSource(id = 1, name = "Legacy manga", language = "zh", contentType = PluginContentType.MANGA),
            BrowseSource(id = 2, name = "Legacy unknown", language = "zh"),
            BrowseSource(id = 3, name = "Current", language = "zh", sourceKey = novelKey, contentType = PluginContentType.NOVEL),
            BrowseSource(id = 4, name = "Other novel", language = "zh", sourceKey = SourceKey(2, "zh.other", "1"), contentType = PluginContentType.NOVEL),
        )
        val novel = MigrationCandidate(
            mangaId = 1, title = "書", currentSourceName = "zh.current", sourceId = 0,
            sourceKey = novelKey, remotePublicationId = "b", contentType = PluginContentType.NOVEL,
        )

        val resolved = novel.copy(contentType = PluginContentType.BOTH).resolvedAgainst(sources)
        assertEquals("Current", resolved.currentSourceName)
        assertEquals(PluginContentType.NOVEL, resolved.contentType)
    }
}
