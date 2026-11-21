package dev.shinsou.kmp.domain.model

/** One extension (novel) chapter waiting for, or done with, an offline download. */
public data class ExtensionDownloadEntryV2(
    val id: String,
    val sourceKey: SourceKey,
    val remotePublicationId: String,
    val remoteUnitId: String,
    val publicationTitle: String,
    val unitTitle: String,
    val state: DownloadState = DownloadState.QUEUED,
    val errorMessage: String? = null,
)
