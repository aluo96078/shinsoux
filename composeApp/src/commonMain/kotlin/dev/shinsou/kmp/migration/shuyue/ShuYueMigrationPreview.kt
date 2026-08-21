package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.ContentMigrationLedgerMutation
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.PortableCategoryId
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.reader.TextOffsetUnit

/**
 * Creates an insertion-ordered map from lexicographically sorted entries. Unlike `toSortedMap`,
 * this representation is available in common code and remains deterministic on every target.
 */
private fun <V> Map<String, V>.toDeterministicMap(): Map<String, V> =
    entries.sortedBy { it.key }.associateTo(linkedMapOf()) { it.key to it.value }

/**
 * A compact canonical stream whose every value is prefixed by its UTF-8 byte length. Field names,
 * collection sizes, null markers, and primitive values all use the same framing, so untrusted text
 * cannot create delimiter ambiguity. The final UTF-8 conversion is identical on every KMP target.
 */
private class ShuYueFingerprintWriter {
    private val output = StringBuilder()

    fun string(value: String) {
        output.append(value.encodeToByteArray().size)
        output.append(':')
        output.append(value)
    }

    fun nullableString(value: String?) {
        boolean(value != null)
        if (value != null) string(value)
    }

    fun int(value: Int): Unit = string(value.toString())
    fun long(value: Long): Unit = string(value.toString())
    fun float(value: Float): Unit = int(value.toRawBits())
    fun double(value: Double): Unit = long(value.toRawBits())
    fun boolean(value: Boolean): Unit = string(if (value) "1" else "0")

    fun nullableLong(value: Long?) {
        boolean(value != null)
        if (value != null) long(value)
    }

    fun nullableDouble(value: Double?) {
        boolean(value != null)
        if (value != null) double(value)
    }

    fun bytes(): ByteArray = output.toString().encodeToByteArray()
}

/** Counts exposed by the preview without exposing any body, script, or secret value. */
public data class ShuYueMigrationCounts(
    public val books: Long,
    public val chapters: Long,
    public val categories: Long,
    public val progress: Long,
    public val installedPlugins: Long,
    public val pluginInstallations: Long,
    public val repositories: Long,
    public val repositoryEntries: Long,
    public val credentials: Long,
    public val cookies: Long,
    public val preferences: Long,
    public val imageParsingPolicies: Long,
    public val totalChapterChars: Long,
    public val totalPluginScriptBytes: Long,
    public val uniquePluginScriptBytes: Long = 0L,
) {
    public val bookCount: Long get() = books
    public val chapterCount: Long get() = chapters
    public val progressCount: Long get() = progress
    public val categoryCount: Long get() = categories
    public val pluginCount: Long get() = installedPlugins + pluginInstallations
}

/** Non-sensitive metadata shown for one legacy book.  Chapter bodies are never included. */
public data class ShuYueMigrationBookSummary(
    public val id: String,
    public val title: String,
    public val author: String?,
    public val origin: String,
    public val sourceId: String?,
    public val chapterCount: Long,
    public val totalTextChars: Long,
    public val category: String,
) {
    override fun toString(): String =
        "ShuYueMigrationBookSummary(id=<redacted>, chapters=$chapterCount, textChars=$totalTextChars)"
}

/** Non-sensitive metadata shown for one legacy chapter. */
public data class ShuYueMigrationChapterSummary(
    public val id: String,
    public val bookId: String,
    public val title: String,
    public val index: Int,
    public val textChars: Int,
    public val wordCount: Int,
) {
    override fun toString(): String =
        "ShuYueMigrationChapterSummary(id=<redacted>, index=$index, textChars=$textChars)"
}

/** A plugin descriptor whose executable payload remains quarantined. */
public data class QuarantinedPluginPreview(
    public val id: String,
    public val version: String,
    public val versionCode: Int = 0,
    public val sourceIds: List<String>,
    public val scriptByteCount: Long,
    public val requiresExplicitTrust: Boolean = true,
    public val origin: String = "unknown",
    public val ordinal: Int = 0,
    public val sha256: String = "",
    public val uniqueScriptByteCount: Long = scriptByteCount,
    public val scriptOccurrenceCount: Int = 1,
) {
    override fun toString(): String =
        "QuarantinedPluginPreview(id=<redacted>, version=<redacted>, origin=$origin, " +
            "ordinal=$ordinal, sourceIds=${sourceIds.size}, scriptByteCount=$scriptByteCount, " +
            "sha256=${sha256.take(8)}…, requiresExplicitTrust=$requiresExplicitTrust)"
}

/** Summary of secret material encountered in the backup. */
public data class SecretMaterialSummary(
    public val credentialCount: Long,
    public val cookieCount: Long,
    public val automaticImportAllowed: Boolean = false,
) {
    override fun toString(): String =
        "SecretMaterialSummary(credentialCount=$credentialCount, cookieCount=$cookieCount, " +
            "automaticImportAllowed=$automaticImportAllowed)"
}

public enum class ShuYueRejectedSecretKind {
    CREDENTIAL,
    COOKIE,
}

/** A secret rejection record contains only a kind and stable ordinal, never its value. */
public data class RejectedSecret(
    public val kind: ShuYueRejectedSecretKind,
    public val ordinal: Int,
    public val reasonCode: String = ShuYueMigrationIssueCode.SECRET_REQUIRES_CONSENT,
) {
    override fun toString(): String =
        "RejectedSecret(kind=$kind, ordinal=$ordinal, reasonCode=$reasonCode)"
}

/** A compact warning projection convenient for UI clients. */
public data class ShuYueMigrationWarning(
    public val code: String,
    public val entityRef: ShuYueMigrationEntityRef?,
    public val message: String,
) {
    override fun toString(): String = "ShuYueMigrationWarning(code=$code, message=$message)"
}

/** User-visible, deterministic, and redacted result of a ShuYue v1 staging preview. */
public data class ShuYueMigrationPreview(
    public val counts: ShuYueMigrationCounts,
    public val bookSummaries: List<ShuYueMigrationBookSummary>,
    public val chapterSummaries: List<ShuYueMigrationChapterSummary>,
    public val quarantinedPlugins: List<QuarantinedPluginPreview>,
    public val secrets: SecretMaterialSummary,
    public val issues: List<ShuYueMigrationIssue>,
    public val canStage: Boolean,
    /** Secret counts are sufficient for M0; individual secret records are never materialized. */
    public val rejectedSecrets: List<RejectedSecret> = emptyList(),
    public val bookSummariesTruncated: Boolean = false,
    public val chapterSummariesTruncated: Boolean = false,
    public val quarantinedPluginsTruncated: Boolean = false,
) {
    /** Compatibility aliases for clients that use shorter summary names. */
    public val books: List<ShuYueMigrationBookSummary> get() = bookSummaries
    public val chapters: List<ShuYueMigrationChapterSummary> get() = chapterSummaries

    public val warnings: List<ShuYueMigrationWarning>
        get() = issues
            .filter { it.severity == ShuYueMigrationIssueSeverity.WARNING }
            .map { ShuYueMigrationWarning(it.code, it.entityRef, it.message) }

    override fun toString(): String =
        "ShuYueMigrationPreview(counts=$counts, books=${bookSummaries.size}, " +
            "chapters=${chapterSummaries.size}, quarantinedPlugins=${quarantinedPlugins.size}, " +
            "secrets=$secrets, rejectedSecrets=${rejectedSecrets.size}, issues=${issues.size}, " +
            "canStage=$canStage)"
}

/**
 * Safe staging facade.  It deliberately has no apply/install/execute operation; an eventual
 * transactional importer can consume this redacted preview after adding explicit user consent.
 */
internal object ShuYueBackupV1Stager {
    /**
     * Stages and fingerprints the exact bytes through one host-owned path. The result fingerprint
     * is domain-separated by the deterministic staging algorithm version; changing identity or
     * metadata projection rules requires a version bump rather than accepting caller-supplied
     * fingerprint text.
     */
    internal fun stageWithLedger(
        encoded: ByteArray,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueStagedMigration? = stageWithLedgerAfterSnapshot(encoded, limits) {}

    /** Test seam proving that decode and digest both consume the same isolated input snapshot. */
    internal fun stageWithLedgerAfterSnapshotForTest(
        encoded: ByteArray,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
        afterSnapshot: () -> Unit,
    ): ShuYueStagedMigration? = stageWithLedgerAfterSnapshot(encoded, limits, afterSnapshot)

    private fun stageWithLedgerAfterSnapshot(
        encoded: ByteArray,
        limits: ShuYueBackupV1Limits,
        afterSnapshot: () -> Unit,
    ): ShuYueStagedMigration? {
        val codec = ShuYueBackupV1Codec(limits)
        // Reject an oversized array before allocating its defensive copy. Calling the codec keeps
        // invalid-limit and input-too-large failures identical to the normal decode contract.
        if (encoded.size > limits.maxRawBytes) {
            codec.decode(encoded)
            error("The ShuYue decoder accepted input beyond its configured byte limit")
        }
        val snapshot = encoded.copyOf()
        afterSnapshot()
        val backup = codec.decode(snapshot)
        val session = stage(backup, limits) ?: return null
        val sourceDigest = Sha256.hex(snapshot)
        val resultFingerprint = fingerprintStagedResult(sourceDigest, session)
        return ShuYueStagedMigration(
            session = session,
            ledgerMutation = ContentMigrationLedgerMutation(
                namespace = MIGRATION_LEDGER_NAMESPACE,
                sourceDigestSha256 = sourceDigest,
                resultFingerprintSha256 = resultFingerprint,
            ),
        )
    }

    /**
     * Canonical deep fingerprint for the exact staged import result. This is internal both so
     * tests can prove field sensitivity with a fixed source digest and so callers cannot supply a
     * replacement fingerprint at the migration-ledger boundary.
     */
    internal fun fingerprintStagedResult(
        sourceDigestSha256: String,
        session: ShuYueStagingSession,
    ): String {
        require(SHA256_HEX.matches(sourceDigestSha256)) {
            "ShuYue source digest must be lowercase SHA-256"
        }
        val writer = ShuYueFingerprintWriter()
        writer.string("domain")
        writer.string(STAGING_FINGERPRINT_DOMAIN)
        writer.string("sourceDigestSha256")
        writer.string(sourceDigestSha256)

        writer.string("books")
        writer.int(session.books.size)
        session.books.forEach { book ->
            writer.string("book")
            writer.string(book.id)
            writer.string(book.title)
            writer.nullableString(book.author)
            writer.nullableString(book.description)
            writer.string(book.origin)
            writer.nullableString(book.sourceId)
            writer.nullableString(book.originalUri)
            writer.string(book.category)
            writer.string(book.categoryId.value)
            writer.long(book.addedAt)
            writer.long(book.updatedAt)
            writer.string(ShuYueReadingLocatorMapper.publicationId(book).value)
            writer.string(ShuYueReadingLocatorMapper.acquisitionId(book))
        }

        val booksById = session.books.associateBy { it.id }
        writer.string("chapters")
        writer.int(session.chapters.size)
        session.chapters.forEach { chapter ->
            val book = requireNotNull(booksById[chapter.bookId]) {
                "Staged chapter has no staged parent book"
            }
            val textBytes = chapter.text.encodeToByteArray()
            writer.string("chapter")
            writer.string(chapter.id)
            writer.string(chapter.bookId)
            writer.string(chapter.title)
            writer.int(chapter.index)
            writer.nullableString(chapter.href)
            writer.int(chapter.wordCount)
            writer.long(textBytes.size.toLong())
            writer.string(Sha256.hex(textBytes))
            writer.string(ShuYueReadingLocatorMapper.unitId(book, chapter.id).value)
            writer.string(ShuYueReadingLocatorMapper.resourceId(book, chapter.id))
        }

        writer.string("categories")
        val categories = session.categories.sortedBy { it.name }
        writer.int(categories.size)
        categories.forEach { category ->
            writer.string(category.name)
            writer.string(category.id.value)
        }

        writer.string("progress")
        writer.int(session.progress.size)
        session.progress.forEach { stagedProgress ->
            val raw = stagedProgress.raw
            writer.string("rawProgress")
            writer.string(raw.bookId)
            writer.string(raw.chapterId)
            writer.int(raw.charOffset)
            writer.float(raw.progress)
            writer.long(raw.updatedAt)

            val locator = stagedProgress.locator
            val scope = locator.scope
            writer.string("textLocator")
            writer.int(locator.schemaVersion)
            writer.int(scope.schemaVersion)
            writer.string(scope.publicationId.value)
            writer.string(scope.acquisitionId)
            writer.string(scope.unitId.publicationKey.value)
            writer.string(scope.unitId.value)
            writer.long(scope.contentRevision)
            writer.string(locator.resourceId)
            writer.string(locator.blockId)
            writer.int(locator.offset)
            writer.string(locator.offsetUnit.name)
            writer.nullableDouble(locator.progression)
            writer.string(locator.direction.name)
            val quote = locator.quote
            writer.boolean(quote != null)
            if (quote != null) {
                writer.string(quote.exact)
                writer.string(quote.prefix)
                writer.string(quote.suffix)
                writer.int(quote.occurrence)
            }
        }

        val settings = session.readerSettings
        writer.string("readerSettings")
        writer.string(settings.language.name)
        writer.float(settings.fontSizeSp)
        writer.int(settings.lineHeightPercent)
        writer.int(settings.pageChars)
        writer.string(settings.theme.name)
        writer.string(settings.accentColor.name)
        writer.boolean(settings.volumeKeysEnabled)
        writer.string(settings.volumeUpAction.name)
        writer.string(settings.volumeDownAction.name)
        writer.boolean(settings.keepScreenOn)
        writer.boolean(settings.syncOnLaunch)
        writer.boolean(settings.appLockEnabled)
        writer.boolean(settings.secureScreen)
        writer.boolean(settings.incognitoMode)
        writer.boolean(settings.showNsfwSources)
        writer.boolean(settings.imageParsingEnabled)
        writer.boolean(settings.showPluginErrors)

        writer.string("preferences")
        val preferences = session.preferences.entries.sortedBy { it.key }
        writer.int(preferences.size)
        preferences.forEach { (key, value) ->
            writer.string(key)
            writer.string(value)
        }

        writer.string("imageParsingPolicies")
        val imagePolicies = session.imageParsingPolicies.entries.sortedBy { it.key }
        writer.int(imagePolicies.size)
        imagePolicies.forEach { (key, value) ->
            writer.string(key)
            writer.string(value.name)
        }

        writer.string("pluginQuarantine")
        writer.int(session.pluginInstallations.size)
        session.pluginInstallations.forEach { plugin ->
            val scriptBytes = plugin.script.encodeToByteArray()
            writer.string(plugin.id)
            writer.string(plugin.version)
            writer.int(plugin.versionCode)
            writer.int(plugin.sourceIds.size)
            plugin.sourceIds.forEach(writer::string)
            writer.string(plugin.origin)
            writer.int(plugin.ordinal)
            // Bind both staged metadata and the payload-derived values. A corrupted or modified
            // staging object therefore cannot retain the fingerprint by changing only one side.
            writer.string(plugin.sha256)
            writer.long(plugin.scriptByteCount)
            writer.string(Sha256.hex(scriptBytes))
            writer.long(scriptBytes.size.toLong())
            writer.boolean(plugin.enabled != null)
            if (plugin.enabled != null) writer.boolean(plugin.enabled)
            writer.nullableLong(plugin.installedAt)
        }

        return Sha256.hex(writer.bytes())
    }

    internal fun preview(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueMigrationPreview {
        val report = ShuYueBackupV1Validator.validate(backup, limits)
        val bookPreviewLimit = limits.maxPreviewBooks.coerceAtLeast(0)
        val chapterPreviewLimit = limits.maxPreviewChapters.coerceAtLeast(0)
        val pluginPreviewLimit = limits.maxPreviewPlugins.coerceAtLeast(0)
        val categories = mutableSetOf<String>()
        val bookSummaries = ArrayList<ShuYueMigrationBookSummary>(minOf(backup.books.size, bookPreviewLimit))
        val chapterSummaries = ArrayList<ShuYueMigrationChapterSummary>(
            minOf(chapterPreviewLimit, 16_384),
        )
        var chapterCount = 0L
        var totalChapterChars = 0L
        backup.books.forEach { book ->
            categories += book.category
            var bookChapterCount = 0L
            var bookTextChars = 0L
            book.chapters.forEach { chapter ->
                bookChapterCount = safeAdd(bookChapterCount, 1L)
                bookTextChars = safeAdd(bookTextChars, chapter.text.length.toLong())
                chapterCount = safeAdd(chapterCount, 1L)
                totalChapterChars = safeAdd(totalChapterChars, chapter.text.length.toLong())
                if (chapterSummaries.size < chapterPreviewLimit) {
                    chapterSummaries += ShuYueMigrationChapterSummary(
                        id = chapter.id,
                        bookId = chapter.bookId,
                        title = chapter.title,
                        index = chapter.index,
                        textChars = chapter.text.length,
                        wordCount = chapter.wordCount,
                    )
                }
            }
            if (bookSummaries.size < bookPreviewLimit) {
                bookSummaries += ShuYueMigrationBookSummary(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    origin = book.origin.serialName,
                    sourceId = book.sourceId,
                    chapterCount = bookChapterCount,
                    totalTextChars = bookTextChars,
                    category = book.category,
                )
            }
        }
        val scriptAccounting = buildQuarantinedPlugins(backup, pluginPreviewLimit)
        val counts = ShuYueMigrationCounts(
            books = backup.books.size.toLong(),
            chapters = chapterCount,
            categories = categories.size.toLong(),
            progress = backup.progress.size.toLong(),
            installedPlugins = backup.installedPlugins.size.toLong(),
            pluginInstallations = backup.pluginInstallations.size.toLong(),
            repositories = backup.pluginRepositories.size.toLong(),
            repositoryEntries = countRepositoryEntries(backup),
            credentials = backup.pluginCredentials.size.toLong(),
            cookies = backup.pluginCookies.size.toLong(),
            preferences = backup.pluginPreferences.size.toLong(),
            imageParsingPolicies = backup.pluginImageParsingPolicies.size.toLong(),
            totalChapterChars = totalChapterChars,
            totalPluginScriptBytes = scriptAccounting.totalRawBytes,
            uniquePluginScriptBytes = scriptAccounting.totalUniqueBytes,
        )
        bookSummaries.sortBy { it.id }
        chapterSummaries.sortWith(
            compareBy<ShuYueMigrationChapterSummary> { it.bookId }
                .thenBy { it.index }
                .thenBy { it.id },
        )
        return ShuYueMigrationPreview(
            counts = counts,
            bookSummaries = bookSummaries,
            chapterSummaries = chapterSummaries,
            quarantinedPlugins = scriptAccounting.entries,
            secrets = SecretMaterialSummary(
                credentialCount = backup.pluginCredentials.size.toLong(),
                cookieCount = backup.pluginCookies.size.toLong(),
                automaticImportAllowed = false,
            ),
            issues = report.issues,
            canStage = report.canStage,
            bookSummariesTruncated = backup.books.size > bookPreviewLimit,
            chapterSummariesTruncated = chapterCount > chapterPreviewLimit.toLong(),
            quarantinedPluginsTruncated = scriptAccounting.totalOccurrences > pluginPreviewLimit,
        )
    }

    /** Internal-only materialization boundary for a future transactional importer. */
    internal fun stage(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueStagingSession? {
        val preview = preview(backup, limits)
        if (!preview.canStage) return null
        val books = backup.books.map {
            ShuYueStagedBook(
                id = it.id,
                title = it.title,
                author = it.author,
                description = it.description,
                origin = it.origin.serialName,
                sourceId = it.sourceId,
                originalUri = it.originalUri,
                category = it.category,
                categoryId = ShuYueReadingLocatorMapper.portableCategoryId(it.category),
                addedAt = it.addedAt,
                updatedAt = it.updatedAt,
            )
        }
        val chapters = ArrayList<ShuYueStagedChapter>()
        backup.books.forEach { book ->
            book.chapters.forEach { chapter ->
                chapters += ShuYueStagedChapter(
                    id = chapter.id,
                    bookId = chapter.bookId,
                    title = chapter.title,
                    index = chapter.index,
                    href = chapter.href,
                    text = chapter.text,
                    wordCount = chapter.wordCount,
                )
            }
        }
        val categories = backup.books
            .map { it.category }
            .distinct()
            .sorted()
            .map { name ->
                ShuYueStagedCategory(
                    name = name,
                    id = ShuYueReadingLocatorMapper.portableCategoryId(name),
                )
            }
        val pluginDescriptions = ArrayList<ShuYueStagedPluginInstallationDescription>()
        fun quarantine(
            id: String,
            version: String,
            versionCode: Int,
            sourceIds: List<String>,
            origin: String,
            ordinal: Int,
            script: String,
            enabled: Boolean?,
            installedAt: Long?,
        ) {
            val bytes = script.encodeToByteArray()
            pluginDescriptions += ShuYueStagedPluginInstallationDescription(
                id = id,
                version = version,
                versionCode = versionCode,
                sourceIds = sourceIds.sorted(),
                origin = origin,
                ordinal = ordinal,
                sha256 = Sha256.hex(bytes),
                scriptByteCount = bytes.size.toLong(),
                script = script,
                enabled = enabled,
                installedAt = installedAt,
            )
        }
        backup.installedPlugins.forEachIndexed { index, installed ->
            val manifest = installed.manifest
            quarantine(
                id = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode,
                sourceIds = manifest.sources.map { it.id },
                origin = "installedPlugins.manifest.script",
                ordinal = index,
                script = manifest.script,
                enabled = installed.enabled,
                installedAt = installed.installedAt,
            )
        }
        backup.pluginInstallations.forEachIndexed { index, installation ->
            val manifest = installation.plugin
            quarantine(
                id = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode,
                sourceIds = manifest.sources.map { it.id },
                origin = "pluginInstallations.plugin.script",
                ordinal = index,
                script = manifest.script,
                enabled = null,
                installedAt = null,
            )
            quarantine(
                id = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode,
                sourceIds = manifest.sources.map { it.id },
                origin = "pluginInstallations.script",
                ordinal = index,
                script = installation.script,
                enabled = null,
                installedAt = null,
            )
        }
        return ShuYueStagingSession(
            preview = preview,
            books = books,
            chapters = chapters,
            categories = categories,
            progress = ShuYueReadingLocatorMapper.mapAll(backup),
            readerSettings = backup.readerSettings,
            preferences = backup.pluginPreferences.toDeterministicMap(),
            imageParsingPolicies = backup.pluginImageParsingPolicies.toDeterministicMap(),
            pluginInstallations = pluginDescriptions,
            secretMaterial = ShuYueStagedSecretMaterial(
                credentials = backup.pluginCredentials.map { it.copy() },
                cookies = backup.pluginCookies.map { it.copy() },
            ),
        )
    }

    private fun buildQuarantinedPlugins(
        backup: ShuYueBackupV1,
        maxPreviewPlugins: Int,
    ): ScriptQuarantineBuild {
        val safePreviewLimit = maxPreviewPlugins.coerceAtLeast(0)
        val entries = ArrayList<QuarantinedPluginPreview>(minOf(safePreviewLimit, 1_024))
        val seenDigests = mutableSetOf<String>()
        var totalRawBytes = 0L
        var totalUniqueBytes = 0L
        var totalOccurrences = 0
        fun addOccurrence(
            pluginId: String,
            version: String,
            versionCode: Int,
            sourceIds: List<String>,
            script: String,
            origin: String,
            ordinal: Int,
        ) {
            val bytes = script.encodeToByteArray()
            val digest = Sha256.hex(bytes)
            val size = bytes.size.toLong()
            totalRawBytes = safeAdd(totalRawBytes, size)
            val isUnique = seenDigests.add(digest)
            totalOccurrences = if (totalOccurrences == Int.MAX_VALUE) Int.MAX_VALUE else totalOccurrences + 1
            if (isUnique) totalUniqueBytes = safeAdd(totalUniqueBytes, size)
            if (entries.size < safePreviewLimit) {
                entries += QuarantinedPluginPreview(
                    id = pluginId,
                    version = version,
                    versionCode = versionCode,
                    sourceIds = sourceIds.sorted(),
                    scriptByteCount = size,
                    requiresExplicitTrust = true,
                    origin = origin,
                    ordinal = ordinal,
                    sha256 = digest,
                    uniqueScriptByteCount = if (isUnique) size else 0L,
                    scriptOccurrenceCount = 1,
                )
            }
        }
        backup.installedPlugins.forEachIndexed { index, installed ->
            val manifest = installed.manifest
            addOccurrence(
                pluginId = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode,
                sourceIds = manifest.sources.map { it.id },
                script = manifest.script,
                origin = "installedPlugins.manifest.script",
                ordinal = index,
            )
        }
        backup.pluginInstallations.forEachIndexed { index, installation ->
            val manifest = installation.plugin
            addOccurrence(
                pluginId = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode,
                sourceIds = manifest.sources.map { it.id },
                script = manifest.script,
                origin = "pluginInstallations.plugin.script",
                ordinal = index,
            )
            addOccurrence(
                pluginId = manifest.id,
                version = manifest.version,
                versionCode = manifest.versionCode,
                sourceIds = manifest.sources.map { it.id },
                script = installation.script,
                origin = "pluginInstallations.script",
                ordinal = index,
            )
        }
        entries.sortWith(
            compareBy<QuarantinedPluginPreview> { it.id }
                .thenBy { it.origin }
                .thenBy { it.ordinal }
                .thenBy { it.sha256 },
        )
        return ScriptQuarantineBuild(entries, totalRawBytes, totalUniqueBytes, totalOccurrences)
    }

    private fun countRepositoryEntries(backup: ShuYueBackupV1): Long =
        backup.pluginRepositories.fold(0L) { total, repository ->
            safeAdd(total, repository.entries.size.toLong())
        }

    private data class ScriptQuarantineBuild(
        val entries: List<QuarantinedPluginPreview>,
        val totalRawBytes: Long,
        val totalUniqueBytes: Long,
        val totalOccurrences: Int,
    )

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private const val MIGRATION_LEDGER_NAMESPACE: String = "shuyue.backup.v1"
    private const val STAGING_FINGERPRINT_DOMAIN: String = "shuyue-v1-staging-result/v2"
    private val SHA256_HEX: Regex = Regex("[0-9a-f]{64}")
}

/** Raw content is retained only inside the internal staging session until a future transaction. */
internal data class ShuYueStagedBook(
    val id: String,
    val title: String,
    val author: String?,
    val description: String?,
    val origin: String,
    val sourceId: String?,
    val originalUri: String?,
    val category: String,
    val categoryId: PortableCategoryId,
    val addedAt: Long,
    val updatedAt: Long,
) {
    override fun toString(): String = "ShuYueStagedBook(<redacted>, category=<redacted>)"
}

/** Exact legacy category name bound to its portable migration identity. */
internal data class ShuYueStagedCategory(
    val name: String,
    val id: PortableCategoryId,
)

internal data class ShuYueStagedChapter(
    val id: String,
    val bookId: String,
    val title: String,
    val index: Int,
    val href: String?,
    val text: String,
    val wordCount: Int,
) {
    override fun toString(): String =
        "ShuYueStagedChapter(<redacted>, textChars=${text.length})"
}

internal data class ShuYueStagedPluginInstallationDescription(
    val id: String,
    val version: String,
    val versionCode: Int,
    val sourceIds: List<String>,
    val origin: String,
    val ordinal: Int,
    val sha256: String,
    val scriptByteCount: Long,
    val script: String,
    val enabled: Boolean?,
    val installedAt: Long?,
) {
    override fun toString(): String =
        "ShuYueStagedPluginInstallationDescription(<redacted>, origin=$origin, " +
            "scriptByteCount=$scriptByteCount, sha256=${sha256.take(8)}…)"
}

/** Plaintext exists only inside the prepared in-memory import and is never exposed by preview. */
internal data class ShuYueStagedSecretMaterial(
    val credentials: List<ShuYueV1PluginCredential>,
    val cookies: List<ShuYueV1PluginCookie>,
) {
    override fun toString(): String =
        "ShuYueStagedSecretMaterial(credentials=${credentials.size}, cookies=${cookies.size}, values=<redacted>)"
}

internal data class ShuYueStagingSession(
    val preview: ShuYueMigrationPreview,
    val books: List<ShuYueStagedBook>,
    val chapters: List<ShuYueStagedChapter>,
    val categories: List<ShuYueStagedCategory>,
    /**
     * Progress is staged as an executable locator plus its redacted source metadata.  Keeping
     * the raw record alongside the locator makes migration auditing possible without forcing a
     * later importer to reverse-engineer a locator back into the legacy DTO.
     */
    val progress: List<ShuYueStagedReadingProgress>,
    val readerSettings: ShuYueV1ReaderSettings,
    val preferences: Map<String, String>,
    val imageParsingPolicies: Map<String, ShuYueV1PluginImageParsingPolicy>,
    val pluginInstallations: List<ShuYueStagedPluginInstallationDescription>,
    val secretMaterial: ShuYueStagedSecretMaterial,
) {
    /** Compatibility view for reports/importers that only need the exact legacy names. */
    val categoryNames: List<String> get() = categories.map { it.name }

    /** Compatibility view for importers that still need the legacy progress fields. */
    val rawProgress: List<ShuYueV1ReaderProgress> get() = progress.map { it.raw }

    /** The executable anchors consumed by reader/search/TTS/annotation code. */
    val readingLocators: List<ReadingLocator.Text> get() = progress.map { it.locator }

    override fun toString(): String =
        "ShuYueStagingSession(books=${books.size}, chapters=${chapters.size}, " +
            "pluginInstallations=${pluginInstallations.size}, preview=$preview)"
}

/** Exact staged result and the ledger mutation that must accompany its eventual SQL commit. */
internal data class ShuYueStagedMigration(
    val session: ShuYueStagingSession,
    val ledgerMutation: ContentMigrationLedgerMutation,
)

/** One legacy progress row after it has been made executable by the v2 reader contract. */
internal data class ShuYueStagedReadingProgress(
    val raw: ShuYueV1ReaderProgress,
    val locator: ReadingLocator.Text,
) {
    val bookId: String get() = raw.bookId
    val chapterId: String get() = raw.chapterId
    val charOffset: Int get() = raw.charOffset
    val progression: Float get() = raw.progress
    val updatedAt: Long get() = raw.updatedAt

    override fun toString(): String =
        "ShuYueStagedReadingProgress(book=<redacted>, chapter=<redacted>, " +
            "offset=$charOffset, hasQuote=${locator.quote != null})"
}

/**
 * Converts ShuYue v1's source-local string identities and UTF-16 character offsets into the
 * versioned locator contract.  The mapper deliberately uses UUIDv5 names rather than hashing or
 * truncating legacy IDs; equal backup records therefore produce equal portable identities on all
 * targets, while source/book/chapter scopes remain distinct.
 */
internal object ShuYueReadingLocatorMapper {
    private val namespace = MigrationNamespaceId("b9a1a2d8-4e43-5f2b-91e9-1a6e7d8c9f02")

    internal fun mapAll(backup: ShuYueBackupV1): List<ShuYueStagedReadingProgress> {
        val books = backup.books.associateBy { it.id }
        val chapters = backup.books
            .asSequence()
            .flatMap { book -> book.chapters.asSequence().map { chapter -> (book.id to chapter.id) to chapter } }
            .toMap()
        return backup.progress.map { raw ->
            val book = requireNotNull(books[raw.bookId]) {
                "Progress book ${raw.bookId} is missing during staging"
            }
            val chapter = requireNotNull(chapters[raw.bookId to raw.chapterId]) {
                "Progress chapter ${raw.chapterId} is missing during staging"
            }
            ShuYueStagedReadingProgress(raw, map(book, chapter, raw))
        }
    }

    internal fun map(
        book: ShuYueV1Book,
        chapter: ShuYueV1Chapter,
        progress: ShuYueV1ReaderProgress,
    ): ReadingLocator.Text {
        require(book.id == progress.bookId && chapter.id == progress.chapterId) {
            "Progress does not belong to the supplied ShuYue chapter"
        }
        require(chapter.bookId == book.id) { "ShuYue chapter does not belong to its book" }
        require(progress.charOffset in 0..chapter.text.length) {
            "ShuYue character offset is outside the chapter text"
        }
        val publicationId = publicationId(book)
        val acquisitionId = acquisitionId(book)
        val unitId = unitId(book, chapter.id)
        val resourceId = resourceId(book, chapter.id)
        val quote = buildQuote(chapter.text, progress.charOffset)
        // A legacy UTF-16 offset may split a surrogate pair. Normally the quote re-anchors that
        // point to the scalar start; when quote construction fails soft at the occurrence cap,
        // materialize the same safe boundary directly while retaining the raw value for audit.
        val locatorOffset = if (quote == null && !isUtf16Boundary(chapter.text, progress.charOffset)) {
            previousBoundary(chapter.text, progress.charOffset)
        } else {
            progress.charOffset
        }
        return ReadingLocator.Text(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            scope = ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = publicationId,
                acquisitionId = acquisitionId,
                unitId = unitId,
                // Staging has not published a content revision yet.  The transactional importer
                // will replace this materialized value when it commits the blob/manifest.
                contentRevision = 0,
            ),
            resourceId = resourceId,
            // A ShuYue chapter is initially one plain-text block.  The content importer may
            // split it later, but this stable block remains the safe fallback for old progress.
            blockId = DEFAULT_TEXT_BLOCK_ID,
            offset = locatorOffset,
            offsetUnit = TextOffsetUnit.UTF_16_CODE_UNIT,
            progression = progress.progress.toDouble(),
            quote = quote,
        )
    }

    private fun buildQuote(text: String, requestedOffset: Int): TextQuote? {
        // A point at EOF has no following exact text. Attaching a quote that starts before EOF
        // would make resolveOffset() move a valid unchanged EOF position backwards.
        if (text.isEmpty() || requestedOffset == text.length) return null
        val boundedOffset = requestedOffset.coerceIn(0, text.length)
        // Preserve the exact legacy UTF-16 offset in the locator, but move the quote start to a
        // scalar boundary when a legacy record points between a surrogate pair.  The quote then
        // remains executable instead of asking the reader to split an emoji in half.
        val start = when {
            boundedOffset < text.length && isUtf16Boundary(text, boundedOffset) -> boundedOffset
            boundedOffset < text.length -> boundedOffset - 1
            else -> previousBoundary(text, boundedOffset)
        }.coerceAtLeast(0)
        val end = boundedWindowEnd(text, start, MAX_STAGED_QUOTE_EXACT_LENGTH)
        if (end <= start) return null
        val exact = text.substring(start, end)
        val prefixStart = boundedWindowStart(text, start, MAX_STAGED_QUOTE_CONTEXT_LENGTH)
        val suffixEnd = boundedWindowEnd(text, end, MAX_STAGED_QUOTE_CONTEXT_LENGTH)
        val prefix = text.substring(prefixStart, start)
        val suffix = text.substring(end, suffixEnd)
        val base = TextQuote(exact = exact, prefix = prefix, suffix = suffix)
        val occurrence = countMatchingOccurrences(text, base, start) ?: return null
        return base.copy(occurrence = occurrence)
    }

    private fun countMatchingOccurrences(text: String, quote: TextQuote, target: Int): Int? {
        var cursor = 0
        var count = 0
        while (cursor <= text.length - quote.exact.length) {
            val index = text.indexOf(quote.exact, cursor)
            if (index < 0 || index >= target) break
            if (quote.matchesAt(text, index)) {
                if (count == MAX_STAGED_QUOTE_OCCURRENCE) return null
                count++
            }
            cursor = index + 1
        }
        return count
    }

    private fun boundedWindowEnd(text: String, start: Int, maxLength: Int): Int {
        var end = (start + maxLength).coerceAtMost(text.length)
        if (end < text.length && end > start && text[end - 1].isHighSurrogate() &&
            text[end].isLowSurrogate()
        ) {
            end--
        }
        return end
    }

    private fun boundedWindowStart(text: String, end: Int, maxLength: Int): Int {
        var start = (end - maxLength).coerceAtLeast(0)
        if (start > 0 && start < end && text[start - 1].isHighSurrogate() &&
            text[start].isLowSurrogate()
        ) {
            // Move into the window rather than widening it beyond maxLength.  The low surrogate
            // cannot stand alone, so exclude the complete pair from this optional context.
            start++
        }
        return start
    }

    private fun previousBoundary(text: String, offset: Int): Int {
        var result = offset.coerceIn(0, text.length)
        if (result > 0 && result < text.length && text[result - 1].isHighSurrogate() &&
            text[result].isLowSurrogate()
        ) {
            result--
        }
        return result
    }

    private fun isUtf16Boundary(text: String, offset: Int): Boolean =
        offset == 0 || offset == text.length ||
            !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

    internal fun publicationId(book: ShuYueV1Book): PublicationKey =
        PublicationKey(derive("publication", bookIdentity(book)))

    internal fun acquisitionId(book: ShuYueV1Book): String =
        derive("acquisition", bookIdentity(book))

    internal fun unitId(book: ShuYueV1Book, chapterId: String): UnitKey =
        UnitKey(publicationId(book), derive("unit", bookIdentity(book), chapterId))

    internal fun resourceId(book: ShuYueV1Book, chapterId: String): String =
        derive("resource", bookIdentity(book), chapterId)

    internal fun publicationId(book: ShuYueStagedBook): PublicationKey =
        PublicationKey(derive("publication", bookIdentity(book)))

    internal fun acquisitionId(book: ShuYueStagedBook): String =
        derive("acquisition", bookIdentity(book))

    internal fun unitId(book: ShuYueStagedBook, chapterId: String): UnitKey =
        UnitKey(publicationId(book), derive("unit", bookIdentity(book), chapterId))

    internal fun resourceId(book: ShuYueStagedBook, chapterId: String): String =
        derive("resource", bookIdentity(book), chapterId)

    internal fun manifestId(book: ShuYueStagedBook, chapterId: String): String =
        derive("manifest", bookIdentity(book), chapterId)

    internal fun representationId(book: ShuYueStagedBook, chapterId: String): String =
        derive("representation", bookIdentity(book), chapterId)

    internal fun rightsGrantId(book: ShuYueStagedBook): String =
        derive("rights-grant", bookIdentity(book))

    internal fun portableCategoryId(name: String): PortableCategoryId =
        if (name == DEFAULT_CATEGORY_NAME) {
            PortableCategoryId.DEFAULT
        } else {
            PortableCategoryId(derive("category", name))
        }

    private fun bookIdentity(book: ShuYueV1Book): String =
        bookIdentity(book.origin.name, book.sourceId, book.id)

    private fun bookIdentity(book: ShuYueStagedBook): String =
        bookIdentity(originIdentityName(book.origin), book.sourceId, book.id)

    private fun bookIdentity(origin: String, sourceId: String?, id: String): String = buildString {
        append(origin)
        append('|')
        append(sourceId?.length ?: -1)
        append(':')
        append(sourceId ?: "")
        append('|')
        append(id.length)
        append(':')
        append(id)
    }

    private fun originIdentityName(serialName: String): String = when (serialName) {
        "LocalTxt" -> ShuYueV1BookOrigin.LOCAL_TXT.name
        "LocalEpub" -> ShuYueV1BookOrigin.LOCAL_EPUB.name
        "RemotePlugin" -> ShuYueV1BookOrigin.REMOTE_PLUGIN.name
        else -> error("Unknown staged ShuYue book origin")
    }

    private fun derive(kind: String, vararg parts: String): String {
        val name = buildString {
            append("shuyue-v1|")
            append(kind)
            parts.forEach {
                append('|')
                append(it.length)
                append(':')
                append(it)
            }
        }
        return Rfc9562UuidV5.derive(namespace, name)
    }

    internal const val DEFAULT_TEXT_BLOCK_ID: String = "body"
    internal const val DEFAULT_CATEGORY_NAME: String = "Default"
    private const val MAX_STAGED_QUOTE_EXACT_LENGTH: Int = 256
    private const val MAX_STAGED_QUOTE_CONTEXT_LENGTH: Int = 64
    private const val MAX_STAGED_QUOTE_OCCURRENCE: Int = 1_000_000
}

/** Public safe import result: callers never receive the raw wire DTO graph. */
public data class ShuYueMigrationInspectionResult(
    public val preview: ShuYueMigrationPreview,
    public val accepted: Boolean,
    public val errorCode: ShuYueBackupV1ErrorCode? = null,
    public val errorPath: String? = null,
) {
    public val canStage: Boolean get() = accepted && preview.canStage

    override fun toString(): String =
        "ShuYueMigrationInspectionResult(accepted=$accepted, errorCode=$errorCode, " +
            "errorPath=$errorPath, preview=$preview)"
}

/** Safe public facade for file import and UI inspection. */
public object ShuYueBackupV1Inspector {
    public fun inspect(
        encoded: ByteArray,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueMigrationInspectionResult = inspectDecoded(limits) {
        ShuYueBackupV1Codec(limits).decode(encoded)
    }

    public fun inspect(
        encoded: String,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueMigrationInspectionResult = inspectDecoded(limits) {
        // The String decoder checks maxRawChars before allocating UTF-8 bytes for its byte cap.
        ShuYueBackupV1Codec(limits).decode(encoded)
    }

    private fun inspectDecoded(
        limits: ShuYueBackupV1Limits,
        decode: () -> ShuYueBackupV1,
    ): ShuYueMigrationInspectionResult = try {
        ShuYueMigrationInspectionResult(
            preview = ShuYueBackupV1Stager.preview(decode(), limits),
            accepted = true,
        )
    } catch (exception: ShuYueBackupV1Exception) {
        ShuYueMigrationInspectionResult(
            preview = rejectedPreview(exception.report?.issues.orEmpty(), exception.code),
            accepted = false,
            errorCode = exception.code,
            errorPath = exception.path,
        )
    }

    private fun rejectedPreview(
        issues: List<ShuYueMigrationIssue>,
        code: ShuYueBackupV1ErrorCode,
    ): ShuYueMigrationPreview {
        val safeIssues = if (issues.isNotEmpty()) issues else listOf(
            ShuYueMigrationIssue(
                severity = ShuYueMigrationIssueSeverity.ERROR,
                code = code.name.lowercase(),
                entityRef = ShuYueMigrationEntityRef("backup"),
                message = "The backup could not be inspected.",
            ),
        )
        return ShuYueMigrationPreview(
            counts = ShuYueMigrationCounts(
                books = 0,
                chapters = 0,
                categories = 0,
                progress = 0,
                installedPlugins = 0,
                pluginInstallations = 0,
                repositories = 0,
                repositoryEntries = 0,
                credentials = 0,
                cookies = 0,
                preferences = 0,
                imageParsingPolicies = 0,
                totalChapterChars = 0,
                totalPluginScriptBytes = 0,
            ),
            bookSummaries = emptyList(),
            chapterSummaries = emptyList(),
            quarantinedPlugins = emptyList(),
            secrets = SecretMaterialSummary(0, 0, automaticImportAllowed = false),
            issues = safeIssues,
            canStage = false,
        )
    }
}

private val ShuYueV1BookOrigin.serialName: String
    get() = when (this) {
        ShuYueV1BookOrigin.LOCAL_TXT -> "LocalTxt"
        ShuYueV1BookOrigin.LOCAL_EPUB -> "LocalEpub"
        ShuYueV1BookOrigin.REMOTE_PLUGIN -> "RemotePlugin"
    }
