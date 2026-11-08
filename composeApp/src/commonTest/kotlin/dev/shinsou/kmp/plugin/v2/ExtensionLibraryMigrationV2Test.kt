package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.local.typedLocalChapterUnitKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ExtensionLibraryMigrationV2Test {
    private val old = SourceKey(2, "zh.oldnovel", "1")
    private val target = SourceKey(2, "zh.newnovel", "2")

    private fun oldChapter(id: Long, name: String, read: Boolean = false, page: Int = 0) = Chapter(
        id = id,
        mangaId = 7,
        url = encodeTypedLocalChapterUrl(
            extensionPublicationKey(old, "book-1"),
            extensionAcquisitionId(old, "book-1"),
            extensionUnitKey(old, "book-1", "u$id"),
        ),
        name = name,
        read = read,
        lastPageRead = page,
    )

    @Test
    fun chaptersKeepTheirRowAndProgressUnderTheTargetUnitIdentity() {
        val plan = planExtensionLibraryMigrationV2(
            oldChapters = listOf(
                oldChapter(1, "第一章 開始", read = true),
                oldChapter(2, "第2章 相遇", page = 3),
                oldChapter(3, "番外 不存在"),
            ),
            targetSourceKey = target,
            targetRemotePublicationId = "remote-9",
            targetUnits = listOf(
                RemoteUnitV2("a", "第1章 开始"),
                RemoteUnitV2("b", "第二章：相遇"),
                RemoteUnitV2("c", "第三章"),
            ),
        )
        assertEquals(encodeExtensionLibraryPublicationUrl(target, "remote-9"), plan.libraryUrl)
        assertEquals(listOf(3L), plan.droppedChapterIds)
        val moved = plan.moved.associateBy(Chapter::id)
        assertEquals(true, moved.getValue(1).read)
        assertEquals(3, moved.getValue(2).lastPageRead)
        assertEquals("第二章：相遇", moved.getValue(2).name)
        assertEquals(1, moved.getValue(2).sourceOrder)
        // The URL is exactly what the reader writes once the target unit is materialized.
        assertEquals(extensionUnitKey(target, "remote-9", "a"), typedLocalChapterUnitKey(moved.getValue(1).url))
        assertEquals(
            encodeTypedLocalChapterUrl(
                extensionPublicationKey(target, "remote-9"),
                extensionAcquisitionId(target, "remote-9"),
                extensionUnitKey(target, "remote-9", "b"),
            ),
            moved.getValue(2).url,
        )
    }

    @Test
    fun anExactTitleIsNeverTakenByANumberMatchAndEachUnitIsUsedOnce() {
        val plan = planExtensionLibraryMigrationV2(
            oldChapters = listOf(oldChapter(1, "Chapter 1"), oldChapter(2, "Prologue"), oldChapter(3, "Afterword")),
            targetSourceKey = target,
            targetRemotePublicationId = "r",
            targetUnits = listOf(RemoteUnitV2("p", "Prologue"), RemoteUnitV2("one", "Ch. 1"), RemoteUnitV2("p", "dup")),
        )
        val unitOf = plan.moved.associate { it.id to typedLocalChapterUnitKey(it.url) }
        assertEquals(extensionUnitKey(target, "r", "p"), unitOf[2])
        assertEquals(extensionUnitKey(target, "r", "one"), unitOf[1])
        assertEquals(listOf(3L), plan.droppedChapterIds)
    }

    @Test
    fun aRepeatedChapterNumberNeverDecidesAMatch() {
        val plan = planExtensionLibraryMigrationV2(
            oldChapters = listOf(oldChapter(1, "第5章 本篇", read = true), oldChapter(2, "第5章 番外"), oldChapter(3, "第6章 舊名")),
            targetSourceKey = target,
            targetRemotePublicationId = "r",
            targetUnits = listOf(RemoteUnitV2("x", "第5章 甲"), RemoteUnitV2("y", "第5章 乙"), RemoteUnitV2("z", "第六章 新名")),
        )
        assertEquals(listOf(1L, 2L), plan.droppedChapterIds)
        assertEquals(extensionUnitKey(target, "r", "z"), typedLocalChapterUnitKey(plan.moved.single().url))
    }

    @Test
    fun unitIdentityDependsOnTheExactSourcePublicationAndUnit() {
        assertEquals(extensionUnitKey(target, "r", "u").publicationKey, extensionPublicationKey(target, "r"))
        assertNotEquals(extensionUnitKey(target, "r", "u"), extensionUnitKey(old, "r", "u"))
        assertNotEquals(extensionUnitKey(target, "r", "u"), extensionUnitKey(target, "r", "v"))
        assertNotEquals(extensionAcquisitionId(target, "r"), extensionAcquisitionId(target, "s"))
    }

    @Test
    fun chapterNumbersAreReadFromCommonTitleForms() {
        assertEquals(12, unitNumber("第十二章 風雨"))
        assertEquals(105, unitNumber("第一百零五话"))
        assertEquals(20, unitNumber("第２０回"))
        assertEquals(3, unitNumber("Chapter 3: Night"))
        assertEquals(42, unitNumber("42 結局"))
        assertNull(unitNumber("序章"))
    }
}
