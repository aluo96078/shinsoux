package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.annotation.ContentAnnotationService
import dev.shinsou.kmp.content.access.ProtectedContentOperations
import dev.shinsou.kmp.contract.RevokedContentCleanupResult
import dev.shinsou.kmp.domain.content.ContentKind
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.ReadingScope
import dev.shinsou.kmp.reader.EpubRenderRequests
import dev.shinsou.kmp.reader.EpubSemanticDocuments
import dev.shinsou.kmp.reader.ImageRenderPages
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.search.ReaderSearchIndex
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.tts.PlatformSpeechCapability
import dev.shinsou.kmp.tts.ProtectedTextToSpeech

/**
 * The rights-enforced content features the screens use: search, annotations, speech, translation
 * and the EPUB/image renderers. The host composes them (`app.ContentFeatureRuntime`).
 *
 * It is declared here, not in `contract`, because its members are services of the data and
 * feature layers; `ui` is the lowest layer that may see all of them at once. Each member is the
 * narrow interface its module declares for the screens; the implementations stay with the host.
 */
public interface ContentFeatures {
    /** Reader translation (ADR-0004); null where the platform has no offline engine. */
    public val translation: ReaderTranslation?
    public val operations: ProtectedContentOperations
    public val searchIndex: ReaderSearchIndex
    public val annotations: ContentAnnotationService
    public val textToSpeech: ProtectedTextToSpeech
    public val speechCapability: PlatformSpeechCapability

    /** Reads exact EPUB resources lazily, only after the reader has passed the DISPLAY gate. */
    public val epubRenderRequests: EpubRenderRequests

    /** Rebuildable XHTML text/CFI map shared by EPUB progress, search, TTS and annotations. */
    public val epubSemanticDocuments: EpubSemanticDocuments

    /** Reads only visible image bodies; opening a long comic never hydrates every page at once. */
    public val imageRenderPages: ImageRenderPages

    /** Read-only format metadata used by library chrome; bodies remain lazy and unopened. */
    public fun publicationContentKinds(publicationKeys: Set<PublicationKey>): Map<PublicationKey, Set<ContentKind>>

    /** Called after foreground/resume and before presenting a typed content session. */
    public suspend fun cleanupRevokedDerivedData(scope: ReadingScope, access: ContentAccessRequest): RevokedContentCleanupResult
}
