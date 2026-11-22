package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.annotation.RightsEnforcedAnnotationService
import dev.shinsou.kmp.content.access.RightsEnforcedContentOperations
import dev.shinsou.kmp.contract.RevokedContentCleanupResult
import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.ReadingScope
import dev.shinsou.kmp.reader.EpubRenderRequestFactory
import dev.shinsou.kmp.reader.EpubSemanticDocumentFactory
import dev.shinsou.kmp.reader.ImageRenderPageFactory
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.search.DerivedLocalFullTextIndex
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.tts.PlatformSpeechCapability
import dev.shinsou.kmp.tts.RightsEnforcedTextToSpeechService

/**
 * The rights-enforced content features the screens use: search, annotations, speech, translation
 * and the EPUB/image renderers. The host composes them (`app.ContentFeatureRuntime`).
 *
 * It is declared here, not in `contract`, because its members are services of the data and
 * feature layers; `ui` is the lowest layer that may see all of them at once.
 */
interface ContentFeatures {
    /** Reader translation (ADR-0004); null where the platform has no offline engine. */
    val translation: ReaderTranslation?
    val operations: RightsEnforcedContentOperations
    val searchIndex: DerivedLocalFullTextIndex
    val annotations: RightsEnforcedAnnotationService
    val textToSpeech: RightsEnforcedTextToSpeechService
    val speechCapability: PlatformSpeechCapability

    /** Reads exact EPUB resources lazily, only after the reader has passed the DISPLAY gate. */
    val epubRenderRequests: EpubRenderRequestFactory

    /** Rebuildable XHTML text/CFI map shared by EPUB progress, search, TTS and annotations. */
    val epubSemanticDocuments: EpubSemanticDocumentFactory

    /** Reads only visible image bodies; opening a long comic never hydrates every page at once. */
    val imageRenderPages: ImageRenderPageFactory

    /** Read-only format metadata used by library chrome; bodies remain lazy and unopened. */
    fun publicationContentKinds(publicationKeys: Set<PublicationKey>): Map<PublicationKey, Set<ContentKind>>

    /** Called after foreground/resume and before presenting a typed content session. */
    suspend fun cleanupRevokedDerivedData(scope: ReadingScope, access: ContentAccessRequest): RevokedContentCleanupResult
}
