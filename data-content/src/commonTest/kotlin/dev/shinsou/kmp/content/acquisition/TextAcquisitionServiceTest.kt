package dev.shinsou.kmp.content.acquisition

import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.content.MAX_CONTENT_METADATA_JSON_CHARS
import dev.shinsou.kmp.domain.content.MAX_STORABLE_TEXT_BLOCKS
import dev.shinsou.kmp.domain.content.TextBlock
import dev.shinsou.kmp.content.blob.InMemoryContentBlobStore
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class TextAcquisitionServiceTest {
    @Test
    fun strictDecoderSupportsBomAndExplicitUtf16WithoutReplacement() {
        val utf8 = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "前😀後".encodeToByteArray()
        val utf16Le = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "前😀後".utf16(littleEndian = true)
        val utf16Be = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + "前😀後".utf16(littleEndian = false)
        val utf16BeWithoutBom = "前😀後".utf16(littleEndian = false)

        assertEquals("前😀後", StrictTextDecoder.decode(utf8).text)
        assertEquals(SourceTextEncoding.UTF_16_LE, StrictTextDecoder.decode(utf16Le).sourceEncoding)
        assertEquals(SourceTextEncoding.UTF_16_BE, StrictTextDecoder.decode(utf16Be).sourceEncoding)
        assertEquals(
            "前😀後",
            StrictTextDecoder.decode(utf16BeWithoutBom, TextEncodingHint.UTF_16_BE).text,
        )
        assertFailsWith<IllegalArgumentException> {
            StrictTextDecoder.decode(utf16Le, TextEncodingHint.UTF_8)
        }
        assertFailsWith<IllegalArgumentException> {
            StrictTextDecoder.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertFailsWith<IllegalArgumentException> {
            StrictTextDecoder.decode(byteArrayOf(0x00, 0xd8.toByte()), TextEncodingHint.UTF_16_LE)
        }
    }

    @Test
    fun acquisitionCanonicalizesLinesAndBuildsUtf16SafeStableChapterAndBlockMaps() {
        val source = "前😀\r\n\r\nChapter 1\r\nBody\r\n\r\n第2章\r\n猫"
        val firstStore = InMemoryContentBlobStore()
        val first = TextAcquisitionService(firstStore).acquire(
            TextAcquisitionRequest(target(), source.encodeToByteArray()),
        )
        val representation = first.representation as ContentRepresentation.PlainText

        assertEquals("前😀\n\nChapter 1\nBody\n\n第2章\n猫", first.metadata.canonicalText)
        assertEquals(first.metadata.canonicalText.length, representation.canonicalUtf16Length)
        assertEquals(listOf("Unit", "Chapter 1", "第2章"), first.metadata.chapters.map(TextChapter::title))
        assertEquals(3, representation.blocks.size)
        representation.blocks.forEach { block ->
            assertFalse(first.metadata.canonicalText.splitsSurrogate(block.startUtf16))
            assertFalse(first.metadata.canonicalText.splitsSurrogate(block.endUtf16))
        }
        assertContentEquals(
            first.metadata.canonicalText.encodeToByteArray(),
            firstStore.read(representation.resource.blob),
        )

        val second = TextAcquisitionService(InMemoryContentBlobStore()).acquire(
            TextAcquisitionRequest(target(), source.replace("\r\n", "\n").encodeToByteArray()),
        )
        val secondRepresentation = second.representation as ContentRepresentation.PlainText
        assertEquals(representation.representationId, secondRepresentation.representationId)
        assertEquals(representation.blocks, secondRepresentation.blocks)
        assertEquals(first.metadata.chapters, second.metadata.chapters)
        assertEquals(first.manifest.manifestId, second.manifest.manifestId)
    }

    @Test
    fun blockIdentitySurvivesInsertionOfADifferentParagraphAndTracksDuplicateOccurrence() {
        val serviceA = TextAcquisitionService(InMemoryContentBlobStore())
        val serviceB = TextAcquisitionService(InMemoryContentBlobStore())
        val original = serviceA.acquire(
            TextAcquisitionRequest(target(), "alpha\n\nbeta\n\nbeta".encodeToByteArray()),
        ).representation as ContentRepresentation.PlainText
        val inserted = serviceB.acquire(
            TextAcquisitionRequest(target(), "new\n\nalpha\n\nbeta\n\nbeta".encodeToByteArray()),
        ).representation as ContentRepresentation.PlainText

        assertEquals(original.blocks[0].blockId, inserted.blocks[1].blockId)
        assertEquals(original.blocks[1].blockId, inserted.blocks[2].blockId)
        assertEquals(original.blocks[2].blockId, inserted.blocks[3].blockId)
        assertNotEquals(original.blocks[1].blockId, original.blocks[2].blockId)
    }

    @Test
    fun aTextWithMoreParagraphsThanBlocksMergesThemEvenlyWithinItsChapters() {
        // 120,400 paragraphs: 400 chapters of a heading and 300 paragraphs each.
        val source = buildString {
            repeat(400) { chapter ->
                append("第").append(chapter + 1).append("章\n\n")
                repeat(300) { paragraph -> append("c").append(chapter).append("p").append(paragraph).append("\n\n") }
            }
        }
        val acquired = TextAcquisitionService(InMemoryContentBlobStore()).acquire(
            TextAcquisitionRequest(target(), source.encodeToByteArray()),
        )
        val text = acquired.metadata.canonicalText
        val blocks = (acquired.representation as ContentRepresentation.PlainText).blocks

        // Four paragraphs to a block is the fewest that fits; 301 paragraphs make 76 blocks.
        assertEquals(400 * 76, blocks.size)
        assertTrue(blocks.size <= MAX_STORABLE_TEXT_BLOCKS)
        val paragraphCounts = blocks.map { text.substring(it.startUtf16, it.endUtf16).split("\n\n").size }
        assertEquals(setOf(3, 4), paragraphCounts.toSet())
        // Nothing but blank lines lies outside the blocks.
        assertTrue(text.substring(0, blocks.first().startUtf16).isBlank())
        assertTrue(text.substring(blocks.last().endUtf16).isBlank())
        assertTrue(blocks.zipWithNext().all { (left, right) -> text.substring(left.endUtf16, right.startUtf16).isBlank() })

        val blockByStart = blocks.associateBy { it.startUtf16 }
        assertEquals(400, acquired.metadata.chapters.size)
        acquired.metadata.chapters.forEach { chapter ->
            assertEquals(blockByStart[chapter.startUtf16]?.blockId, chapter.blockIds.first(), chapter.title)
            assertEquals(76, chapter.blockIds.size, chapter.title)
        }
        assertEquals(blocks.size, blocks.map { it.blockId }.toSet().size)
        // What is stored with the manifest fits the store.
        assertTrue(Json.encodeToString(ContentManifest.serializer(), acquired.manifest).length < MAX_CONTENT_METADATA_JSON_CHARS)

        val again = TextAcquisitionService(InMemoryContentBlobStore()).acquire(
            TextAcquisitionRequest(target(), source.encodeToByteArray()),
        )
        assertEquals(blocks, (again.representation as ContentRepresentation.PlainText).blocks)
        assertEquals(acquired.metadata.chapters, again.metadata.chapters)
    }

    @Test
    fun mergedBlocksKeepTheBlankLinesBetweenTheirParagraphsAndStopAtAChapterHeading() {
        val source = "a\n\nb\n\n\n# Two\nc\n\nd\n\ne\n\nf"
        val acquired = TextAcquisitionService(
            InMemoryContentBlobStore(),
            policy = TextAcquisitionPolicy(maximumBlocks = 3),
        ).acquire(TextAcquisitionRequest(target(), source.encodeToByteArray()))
        val blocks = (acquired.representation as ContentRepresentation.PlainText).blocks

        // Five paragraphs, two before the heading: a block of three would cross it.
        assertEquals(listOf("a\n\nb", "# Two\nc\n\nd", "e\n\nf"), blocks.map { source.substring(it.startUtf16, it.endUtf16) })
        assertEquals(listOf(1, 2), acquired.metadata.chapters.map { it.blockIds.size })
        assertEquals(blocks[1].blockId, acquired.metadata.chapters[1].blockIds.first())
    }

    @Test
    fun aTextWithinTheBlockLimitKeepsOneBlockForEachParagraph() {
        val source = "alpha\n\n# Two\nbeta\n\ngamma\n\nalpha"
        fun blocksWith(policy: TextAcquisitionPolicy) = (
            TextAcquisitionService(InMemoryContentBlobStore(), policy = policy)
                .acquire(TextAcquisitionRequest(target(), source.encodeToByteArray()))
                .representation as ContentRepresentation.PlainText
            ).blocks
        val blocks = blocksWith(TextAcquisitionPolicy())

        assertEquals(
            listOf("alpha", "# Two\nbeta", "gamma", "alpha"),
            blocks.map { source.substring(it.startUtf16, it.endUtf16) },
        )
        // The limit itself changes nothing until a text exceeds it, so these ids are the ones
        // an earlier import of the text has.
        assertEquals(blocks, blocksWith(TextAcquisitionPolicy(maximumBlocks = 100_000)))
        assertEquals(blocks, blocksWith(TextAcquisitionPolicy(maximumBlocks = 4)))
        assertNotEquals(blocks, blocksWith(TextAcquisitionPolicy(maximumBlocks = 3)))
    }

    @Test
    fun moreChaptersThanBlocksAreRefusedBeforeAnythingIsPublished() {
        val store = InMemoryContentBlobStore()
        val failure = assertFailsWith<IllegalArgumentException> {
            TextAcquisitionService(store, policy = TextAcquisitionPolicy(maximumBlocks = 2))
                .acquire(TextAcquisitionRequest(target(), "# One\n\n# Two\n\n# Three".encodeToByteArray()))
        }
        assertEquals("Text contains more chapters than it may have locator blocks", failure.message)
        assertEquals(0, store.count)
    }

    @Test
    fun theLargestStoredBlockMapFitsTheMetadataStore() {
        val acquired = TextAcquisitionService(InMemoryContentBlobStore())
            .acquire(TextAcquisitionRequest(target(), "text".encodeToByteArray()))
        val representation = acquired.representation as ContentRepresentation.PlainText
        // The longest a block gets: a derived id and offsets near the end of a 64 MiB text.
        val idLength = representation.blocks.single().blockId.length
        val blocks = List(MAX_STORABLE_TEXT_BLOCKS) { index ->
            val start = 60_000_000 + index * 2
            TextBlock("block-" + index.toString().padStart(idLength - 6, '0'), start, start + 1)
        }
        val manifest = acquired.manifest.copy(
            representations = listOf(representation.copy(canonicalUtf16Length = 67_108_864, blocks = blocks)),
        )
        val encoded = Json.encodeToString(ContentManifest.serializer(), manifest)
        // The rest goes to what is stored around the manifest: its owner, the unit and its title.
        assertTrue(encoded.length < MAX_CONTENT_METADATA_JSON_CHARS - 400_000, "Encoded ${encoded.length} characters")
    }

    @Test
    fun emptyTextStillHasOneAddressableBlockAndChapter() {
        val acquired = TextAcquisitionService(InMemoryContentBlobStore()).acquire(
            TextAcquisitionRequest(target(), byteArrayOf()),
        )
        val representation = acquired.representation as ContentRepresentation.PlainText

        assertEquals(1, representation.blocks.size)
        assertEquals(0, representation.blocks.single().startUtf16)
        assertEquals(0, representation.blocks.single().endUtf16)
        assertEquals(1, acquired.metadata.chapters.size)
        assertTrue(acquired.metadata.chapters.single().blockIds.isNotEmpty())
    }

    @Test
    fun sizeLimitFailsBeforePublishingCanonicalText() {
        val store = InMemoryContentBlobStore()
        assertFailsWith<IllegalArgumentException> {
            TextAcquisitionService(
                blobStore = store,
                policy = TextAcquisitionPolicy(maximumSourceBytes = 4),
            ).acquire(TextAcquisitionRequest(target(), "12345".encodeToByteArray()))
        }
        assertEquals(0, store.currentGeneration)
    }

    @Test
    fun cancellationAbortsCanonicalTextStageBeforeTheNext64KiBAppend() {
        val store = InMemoryContentBlobStore(configuredStoreInstanceId = "text-cancellation-test")
        var publishing = false
        var publishCheckpoints = 0
        val service = TextAcquisitionService(
            blobStore = store,
            authorizeOfflineStore = { publishing = true },
            cancellationCheckpoint = {
                if (publishing) {
                    publishCheckpoints++
                    if (publishCheckpoints == 2) throw CancellationException("text import left")
                }
            },
        )

        assertFailsWith<CancellationException> {
            service.acquire(
                TextAcquisitionRequest(
                    target(),
                    ByteArray(128 * 1024) { 'a'.code.toByte() },
                ),
            )
        }

        assertEquals(2, publishCheckpoints)
        assertEquals(0, store.count)
        assertEquals(0, store.pendingReceiptCount)
    }

    private fun target(): LocalAcquisitionTarget = LocalAcquisitionTarget(
        publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111"),
        publicationTitle = "Book",
        stableImportId = "fixtures/text/book-1",
        unitTitle = "Unit",
        contentRevision = 4,
        acquiredAtEpochMillis = 100,
    )
}

private fun String.utf16(littleEndian: Boolean): ByteArray {
    val output = ByteArray(length * 2)
    forEachIndexed { index, character ->
        val high = (character.code ushr 8).toByte()
        val low = character.code.toByte()
        if (littleEndian) {
            output[index * 2] = low
            output[index * 2 + 1] = high
        } else {
            output[index * 2] = high
            output[index * 2 + 1] = low
        }
    }
    return output
}

private fun String.splitsSurrogate(offset: Int): Boolean =
    offset > 0 && offset < length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()
