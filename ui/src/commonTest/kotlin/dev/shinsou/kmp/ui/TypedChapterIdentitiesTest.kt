package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TypedChapterIdentitiesTest {
    private val typed = Manga(
        id = 1,
        source = LOCAL_SOURCE_ID,
        url = encodeTypedLocalPublicationUrl(extensionPublicationKey(SourceKey(packageId = "pkg.test", sourceId = "s"), "typed-1")),
        title = "Typed",
    )
    private val ordinary = Manga(id = 2, source = 7, url = "/title", title = "Ordinary")
    private val snapshot = AppSnapshot(
        mangas = listOf(typed, ordinary),
        chapters = listOf(
            Chapter(id = 10, mangaId = 1, url = "typed/1"),
            Chapter(id = 20, mangaId = 2, url = "/title/1"),
        ),
    )

    @Test
    fun readingProgressDoesNotAskForTheContentKindsAgain() {
        val read = snapshot.copy(
            revision = snapshot.revision + 1,
            chapters = snapshot.chapters.map { it.copy(read = true, lastPageRead = 12) },
        )

        assertEquals(typedChapterIdentities(snapshot), typedChapterIdentities(read))
    }

    @Test
    fun aNewChapterOfATypedTitleDoes() {
        val added = snapshot.copy(chapters = snapshot.chapters + Chapter(id = 11, mangaId = 1, url = "typed/2"))

        assertNotEquals(typedChapterIdentities(snapshot), typedChapterIdentities(added))
    }

    @Test
    fun otherTitlesChaptersDoNot() {
        val added = snapshot.copy(chapters = snapshot.chapters + Chapter(id = 21, mangaId = 2, url = "/title/2"))

        assertEquals(typedChapterIdentities(snapshot), typedChapterIdentities(added))
    }
}
