package dev.shinsou.kmp.migration.shuyue

/** Hard limits applied before and during ShuYue v1 decoding. */
public data class ShuYueBackupV1Limits(
    public val maxRawBytes: Int = 64 * 1024 * 1024,
    public val maxRawChars: Int = 64 * 1024 * 1024,
    public val maxJsonDepth: Int = 64,
    public val maxJsonObjectMembers: Int = 100_000,
    public val maxTotalJsonMembers: Int = 2_000_000,
    public val maxJsonArrayElements: Int = 1_000_000,
    public val maxTotalJsonValues: Int = 2_000_000,
    public val maxBooks: Int = 100_000,
    public val maxChapters: Int = 1_000_000,
    public val maxChaptersPerBook: Int = 100_000,
    public val maxChapterChars: Int = 8 * 1024 * 1024,
    public val maxTotalChapterChars: Long = 256L * 1024 * 1024,
    public val maxProgress: Int = 1_000_000,
    public val maxIssues: Int = 1_000,
    public val maxPreviewBooks: Int = 10_000,
    public val maxPreviewChapters: Int = 20_000,
    public val maxPreviewPlugins: Int = 10_000,
    public val maxFieldChars: Int = 1 * 1024 * 1024,
    public val maxIdentifierChars: Int = 4 * 1024,
    public val maxTitleChars: Int = 16 * 1024,
    public val maxDescriptionChars: Int = 1 * 1024 * 1024,
    public val maxCategoryChars: Int = 1 * 1024,
    public val maxPluginFieldChars: Int = 64 * 1024,
    public val maxRepositoryFieldChars: Int = 64 * 1024,
    public val maxUrlChars: Int = 16 * 1024,
    public val maxInstalledPlugins: Int = 10_000,
    public val maxPluginInstallations: Int = 10_000,
    public val maxPluginSources: Int = 2_000,
    public val maxTotalPluginSources: Int = 100_000,
    public val maxRepositories: Int = 1_000,
    public val maxRepositoryEntries: Int = 100_000,
    public val maxRepositoryEntriesPerRepository: Int = 100_000,
    public val maxPluginScriptBytes: Int = 8 * 1024 * 1024,
    public val maxTotalPluginScriptBytes: Long = 64L * 1024 * 1024,
    public val maxCredentials: Int = 100_000,
    public val maxCookies: Int = 100_000,
    public val maxPreferences: Int = 100_000,
    public val maxImageParsingPolicies: Int = 100_000,
    public val maxCredentialFieldChars: Int = 64 * 1024,
    public val maxCookieFieldChars: Int = 64 * 1024,
    public val maxPreferenceFieldChars: Int = 64 * 1024,
) {
    public companion object {
        public val Default: ShuYueBackupV1Limits = ShuYueBackupV1Limits()

        /** Upper-case alias for callers that use constant naming conventions. */
        public val DEFAULT: ShuYueBackupV1Limits = Default
    }
}

/** Severity is deliberately independent of the host UI's logging levels. */
public enum class ShuYueMigrationIssueSeverity {
    WARNING,
    ERROR,
}

/** Stable, non-localized issue identifiers used by validation reports and migration telemetry. */
public object ShuYueMigrationIssueCode {
    public const val INVALID_LIMITS: String = "invalid_limits"
    public const val UNSUPPORTED_VERSION: String = "unsupported_version"
    public const val NEGATIVE_TIMESTAMP: String = "negative_timestamp"
    public const val INVALID_IDENTIFIER: String = "invalid_identifier"
    public const val DUPLICATE_BOOK_ID: String = "duplicate_book_id"
    public const val DUPLICATE_CHAPTER_ID: String = "duplicate_chapter_id"
    public const val DUPLICATE_PROGRESS: String = "duplicate_progress"
    public const val MISSING_BOOK_REFERENCE: String = "missing_book_reference"
    public const val MISSING_CHAPTER_REFERENCE: String = "missing_chapter_reference"
    public const val PROGRESS_BOOK_MISMATCH: String = "progress_book_mismatch"
    public const val INVALID_CHAR_OFFSET: String = "invalid_char_offset"
    public const val INVALID_PROGRESS: String = "invalid_progress"
    public const val INVALID_CHAPTER_INDEX: String = "invalid_chapter_index"
    public const val INVALID_WORD_COUNT: String = "invalid_word_count"
    public const val WORD_COUNT_MISMATCH: String = "word_count_mismatch"
    public const val CHAPTER_LIMIT_EXCEEDED: String = "chapter_limit_exceeded"
    public const val BOOK_LIMIT_EXCEEDED: String = "book_limit_exceeded"
    public const val CHAPTER_TEXT_LIMIT_EXCEEDED: String = "chapter_text_limit_exceeded"
    public const val TOTAL_TEXT_LIMIT_EXCEEDED: String = "total_text_limit_exceeded"
    public const val DUPLICATE_SOURCE_ID: String = "duplicate_source_id"
    public const val INVALID_SOURCE_DESCRIPTOR: String = "invalid_source_descriptor"
    public const val DUPLICATE_PLUGIN_ID: String = "duplicate_plugin_id"
    public const val PLUGIN_LIMIT_EXCEEDED: String = "plugin_limit_exceeded"
    public const val PLUGIN_SCRIPT_LIMIT_EXCEEDED: String = "plugin_script_limit_exceeded"
    public const val TOTAL_PLUGIN_SCRIPT_LIMIT_EXCEEDED: String = "total_plugin_script_limit_exceeded"
    public const val PLUGIN_INSTALLATION_SET_MISMATCH: String = "plugin_installation_set_mismatch"
    public const val PLUGIN_MANIFEST_MISMATCH: String = "plugin_manifest_mismatch"
    public const val DUPLICATE_REPOSITORY_URL: String = "duplicate_repository_url"
    public const val REPOSITORY_LIMIT_EXCEEDED: String = "repository_limit_exceeded"
    public const val REPOSITORY_ENTRY_LIMIT_EXCEEDED: String = "repository_entry_limit_exceeded"
    public const val INVALID_REPOSITORY_URL: String = "invalid_repository_url"
    public const val INVALID_COOKIE: String = "invalid_cookie"
    public const val COOKIE_LIMIT_EXCEEDED: String = "cookie_limit_exceeded"
    public const val CREDENTIAL_LIMIT_EXCEEDED: String = "credential_limit_exceeded"
    public const val PREFERENCE_LIMIT_EXCEEDED: String = "preference_limit_exceeded"
    public const val IMAGE_POLICY_LIMIT_EXCEEDED: String = "image_policy_limit_exceeded"
    public const val INVALID_PREFERENCE: String = "invalid_preference"
    public const val SECRET_REQUIRES_CONSENT: String = "secret_requires_explicit_consent"
    public const val PLUGIN_SCRIPT_QUARANTINED: String = "plugin_script_quarantined"
    public const val INVALID_READER_SETTINGS: String = "invalid_reader_settings"
    public const val INVALID_TEXT_ENCODING: String = "invalid_text_encoding"
    public const val FIELD_LENGTH_LIMIT_EXCEEDED: String = "field_length_limit_exceeded"
    public const val PROGRESS_LIMIT_EXCEEDED: String = "progress_limit_exceeded"
    public const val DUPLICATE_CHAPTER_INDEX: String = "duplicate_chapter_index"
    public const val DUPLICATE_CREDENTIAL_SOURCE_ID: String = "duplicate_credential_source_id"
    public const val DUPLICATE_COOKIE: String = "duplicate_cookie"
    public const val ISSUES_TRUNCATED: String = "issues_truncated"
}

/** A stable reference to the affected record without copying untrusted bodies or secret values. */
public data class ShuYueMigrationEntityRef(
    public val kind: String,
    public val id: String? = null,
    public val parentId: String? = null,
    public val index: Int? = null,
) {
    override fun toString(): String = buildString {
        append(kind)
        if (index != null) append("[index=").append(index).append(']')
    }
}

/** One deterministic validation or migration finding.  Messages are deliberately value-free. */
public data class ShuYueMigrationIssue(
    public val severity: ShuYueMigrationIssueSeverity,
    public val code: String,
    public val entityRef: ShuYueMigrationEntityRef? = null,
    public val message: String,
) {
    init {
        require(code.isNotBlank()) { "Issue code must not be blank" }
        require(message.isNotBlank()) { "Issue message must not be blank" }
    }

    /** Compatibility alias for clients that call the reference an entity. */
    public val entity: ShuYueMigrationEntityRef? get() = entityRef

    override fun toString(): String = buildString {
        append(severity.name).append(':').append(code)
        if (entityRef != null) append(" @ ").append(entityRef)
        append(" - ").append(message)
    }
}

/** A validation result is usable by preview code even when it contains errors. */
public data class ShuYueMigrationValidationReport(
    public val issues: List<ShuYueMigrationIssue> = emptyList(),
) {
    public val errors: List<ShuYueMigrationIssue>
        get() = issues.filter { it.severity == ShuYueMigrationIssueSeverity.ERROR }

    public val warnings: List<ShuYueMigrationIssue>
        get() = issues.filter { it.severity == ShuYueMigrationIssueSeverity.WARNING }

    public val canStage: Boolean get() = errors.isEmpty()
    public val hasErrors: Boolean get() = errors.isNotEmpty()

    override fun toString(): String =
        "ShuYueMigrationValidationReport(errors=${errors.size}, warnings=${warnings.size}, " +
            "codes=${issues.map { it.code }})"
}

/**
 * Structural and safety validation for an already decoded DTO.  This function never evaluates a
 * plugin script and never copies a credential, cookie, chapter body, or preference value into an
 * issue message.
 */
internal object ShuYueBackupV1Validator {
    internal fun validate(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueMigrationValidationReport {
        val findings = CappedIssueList(limits.maxIssues)
        validateLimits(limits, findings)
        if (backup.version != 1) {
            findings += error(
                ShuYueMigrationIssueCode.UNSUPPORTED_VERSION,
                ShuYueMigrationEntityRef("backup"),
                "The backup version is not supported.",
            )
        }
        if (backup.createdAt < 0L) {
            findings += error(
                ShuYueMigrationIssueCode.NEGATIVE_TIMESTAMP,
                ShuYueMigrationEntityRef("backup"),
                "The backup creation time is invalid.",
            )
        }
        validateBooks(backup, limits, findings)
        validateFieldBounds(backup, limits, findings)
        validateTextEncoding(backup, findings)
        validateProgress(backup, limits, findings)
        validatePlugins(backup, limits, findings)
        validateRepositories(backup, limits, findings)
        validateSecretsAndPreferences(backup, limits, findings)
        validateReaderSettings(backup.readerSettings, findings)

        if (findings.dropped) {
            val truncated = error(
                ShuYueMigrationIssueCode.ISSUES_TRUNCATED,
                ShuYueMigrationEntityRef("report"),
                "The validation report reached its configured issue limit.",
            )
            if (findings.size == findings.capacity) {
                findings[findings.size - 1] = truncated
            } else {
                findings += truncated
            }
        }
        return ShuYueMigrationValidationReport(sortIssues(findings))
    }

    private fun validateLimits(
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        val invalid = listOf(
            limits.maxRawBytes.toLong() to "raw bytes",
            limits.maxRawChars.toLong() to "raw characters",
            limits.maxJsonDepth.toLong() to "JSON depth",
            limits.maxJsonObjectMembers.toLong() to "JSON object members",
            limits.maxTotalJsonMembers.toLong() to "total JSON members",
            limits.maxJsonArrayElements.toLong() to "JSON array elements",
            limits.maxTotalJsonValues.toLong() to "total JSON values",
            limits.maxBooks.toLong() to "books",
            limits.maxChapters.toLong() to "chapters",
            limits.maxChaptersPerBook.toLong() to "chapters per book",
            limits.maxChapterChars.toLong() to "chapter characters",
            limits.maxTotalChapterChars to "total chapter characters",
            limits.maxProgress.toLong() to "progress",
            limits.maxIssues.toLong() to "issues",
            limits.maxPreviewBooks.toLong() to "preview books",
            limits.maxPreviewChapters.toLong() to "preview chapters",
            limits.maxPreviewPlugins.toLong() to "preview plugins",
            limits.maxFieldChars.toLong() to "fields",
            limits.maxIdentifierChars.toLong() to "identifiers",
            limits.maxTitleChars.toLong() to "titles",
            limits.maxDescriptionChars.toLong() to "descriptions",
            limits.maxCategoryChars.toLong() to "categories",
            limits.maxPluginFieldChars.toLong() to "plugin fields",
            limits.maxRepositoryFieldChars.toLong() to "repository fields",
            limits.maxUrlChars.toLong() to "URLs",
            limits.maxInstalledPlugins.toLong() to "installed plugins",
            limits.maxPluginInstallations.toLong() to "plugin installations",
            limits.maxPluginSources.toLong() to "plugin sources",
            limits.maxTotalPluginSources.toLong() to "total plugin sources",
            limits.maxRepositories.toLong() to "repositories",
            limits.maxRepositoryEntries.toLong() to "repository entries",
            limits.maxRepositoryEntriesPerRepository.toLong() to "repository entries per repository",
            limits.maxPluginScriptBytes.toLong() to "plugin script bytes",
            limits.maxTotalPluginScriptBytes to "total plugin script bytes",
            limits.maxCredentials.toLong() to "credentials",
            limits.maxCookies.toLong() to "cookies",
            limits.maxPreferences.toLong() to "preferences",
            limits.maxImageParsingPolicies.toLong() to "image policies",
            limits.maxCredentialFieldChars.toLong() to "credential fields",
            limits.maxCookieFieldChars.toLong() to "cookie fields",
            limits.maxPreferenceFieldChars.toLong() to "preference fields",
        ).filter { it.first < 0L }
        invalid.forEach { (_, kind) ->
            findings += error(
                ShuYueMigrationIssueCode.INVALID_LIMITS,
                ShuYueMigrationEntityRef("limits"),
                "The configured $kind limit is invalid.",
            )
        }
    }

    private fun validateBooks(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (backup.books.size > limits.maxBooks) {
            findings += error(
                ShuYueMigrationIssueCode.BOOK_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("books"),
                "The book count exceeds the configured limit.",
            )
            return
        }

        val bookIds = mutableSetOf<String>()
        val chapterIdsByBook = mutableMapOf<String, MutableSet<String>>()
        val chapterIndicesByBook = mutableMapOf<String, MutableSet<Int>>()
        var chapterCount = 0L
        var totalTextChars = 0L
        backup.books.forEachIndexed { bookIndex, book ->
            val bookRef = ShuYueMigrationEntityRef("book", book.id, index = bookIndex)
            validateTextIdentifier(book.id, bookRef, findings)
            if (!bookIds.add(book.id)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_BOOK_ID,
                    bookRef,
                    "A book identifier occurs more than once.",
                )
            }
            validatePrintableField(book.title, "book title", bookRef, findings)
            validateOptionalPrintableField(book.author, "book author", bookRef, findings)
            validateOptionalPrintableField(book.description, "book description", bookRef, findings)
            validateOptionalPrintableField(book.coverImage, "book cover", bookRef, findings)
            // sourceId participates directly in portable publication/acquisition identity.
            // Treat every present value as an identifier, including controls that ordinary
            // multiline display metadata is allowed to retain.
            book.sourceId?.let { validateTextIdentifier(it, bookRef, findings) }
            validateOptionalPrintableField(book.originalUri, "book URI", bookRef, findings)
            validatePrintableField(book.category, "book category", bookRef, findings)
            validateTimestamp(book.addedAt, bookRef, findings)
            validateTimestamp(book.updatedAt, bookRef, findings)
            if (book.origin == ShuYueV1BookOrigin.REMOTE_PLUGIN && book.sourceId.isNullOrBlank()) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_SOURCE_DESCRIPTOR,
                    bookRef,
                    "A remote-plugin book must identify its source.",
                )
            }
            if (book.chapters.size > limits.maxChaptersPerBook) {
                findings += error(
                    ShuYueMigrationIssueCode.CHAPTER_LIMIT_EXCEEDED,
                    bookRef,
                    "The chapter count for a book exceeds the configured limit.",
                )
            }
            chapterCount = safeAddTextChars(chapterCount, book.chapters.size.toLong())
            val chapterIds = chapterIdsByBook.getOrPut(book.id) { mutableSetOf() }
            val chapterIndices = chapterIndicesByBook.getOrPut(book.id) { mutableSetOf() }
            book.chapters.forEachIndexed { chapterIndex, chapter ->
                val chapterRef = ShuYueMigrationEntityRef("chapter", chapter.id, book.id, chapterIndex)
                validateChapter(chapter, book, chapterRef, limits, findings)
                if (!chapterIds.add(chapter.id)) {
                    findings += error(
                        ShuYueMigrationIssueCode.DUPLICATE_CHAPTER_ID,
                        chapterRef,
                        "A chapter identifier occurs more than once.",
                    )
                }
                if (!chapterIndices.add(chapter.index)) {
                    findings += error(
                        ShuYueMigrationIssueCode.DUPLICATE_CHAPTER_INDEX,
                        chapterRef,
                        "A chapter index occurs more than once within a book.",
                    )
                }
                totalTextChars = safeAddTextChars(totalTextChars, chapter.text.length.toLong())
            }
        }
        if (chapterCount > limits.maxChapters.toLong()) {
            findings += error(
                ShuYueMigrationIssueCode.CHAPTER_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("chapters"),
                "The chapter count exceeds the configured limit.",
            )
        }
        if (totalTextChars > limits.maxTotalChapterChars) {
            findings += error(
                ShuYueMigrationIssueCode.TOTAL_TEXT_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("chapters"),
                "The total chapter text exceeds the configured limit.",
            )
        }
    }

    private fun validateChapter(
        chapter: ShuYueV1Chapter,
        book: ShuYueV1Book,
        ref: ShuYueMigrationEntityRef,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        validateTextIdentifier(chapter.id, ref, findings)
        validateTextIdentifier(chapter.bookId, ref, findings)
        if (chapter.bookId != book.id) {
            findings += error(
                ShuYueMigrationIssueCode.MISSING_BOOK_REFERENCE,
                ref,
                "A chapter does not belong to its containing book.",
            )
        }
        validatePrintableField(chapter.title, "chapter title", ref, findings)
        validateOptionalPrintableField(chapter.href, "chapter href", ref, findings)
        if (chapter.index < 0) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_CHAPTER_INDEX,
                ref,
                "A chapter index cannot be negative.",
            )
        }
        if (chapter.wordCount < 0) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_WORD_COUNT,
                ref,
                "A chapter word count cannot be negative.",
            )
        }
        if (chapter.wordCount != chapter.text.length) {
            findings += warning(
                ShuYueMigrationIssueCode.WORD_COUNT_MISMATCH,
                ref,
                "A chapter word count differs from its stored text length.",
            )
        }
        if (chapter.text.length > limits.maxChapterChars) {
            findings += error(
                ShuYueMigrationIssueCode.CHAPTER_TEXT_LIMIT_EXCEEDED,
                ref,
                "A chapter text exceeds the configured limit.",
            )
        }
        // Newlines, carriage returns, and tabs are legitimate prose.  NUL and the remaining
        // controls cannot safely survive canonical UTF-8 text-blob normalization.
        if (chapter.text.any(::isUnsafeTextControl)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_IDENTIFIER,
                ref,
                "A chapter contains an unsupported control character.",
            )
        }
    }

    /**
     * Reject malformed UTF-16 before any identifier, metadata, script, or map entry is encoded to
     * UTF-8. Kotlin replaces lone surrogates during encoding, which would otherwise collapse
     * distinct legacy strings onto the same digest/UUID input.
     */
    private fun validateTextEncoding(
        backup: ShuYueBackupV1,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        fun field(value: String?, ref: ShuYueMigrationEntityRef) {
            if (value != null && hasMalformedSurrogate(value)) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_TEXT_ENCODING,
                    ref,
                    "A text field contains malformed UTF-16.",
                )
            }
        }

        fun source(
            value: ShuYueV1PluginSourceDescriptor,
            ref: ShuYueMigrationEntityRef,
        ) {
            field(value.id, ref)
            field(value.name, ref)
            field(value.lang, ref)
            field(value.baseUrl, ref)
        }

        fun manifest(value: ShuYueV1PluginManifest, ref: ShuYueMigrationEntityRef) {
            field(value.id, ref)
            field(value.name, ref)
            field(value.version, ref)
            field(value.lang, ref)
            field(value.script, ref)
            field(value.signature, ref)
            field(value.minRuntimeVersion, ref)
            field(value.repository, ref)
            value.sources.forEachIndexed { index, item ->
                source(item, ShuYueMigrationEntityRef("pluginSource", index = index, parentId = value.id))
            }
        }

        backup.books.forEachIndexed { bookIndex, book ->
            val bookRef = ShuYueMigrationEntityRef("book", index = bookIndex)
            field(book.id, bookRef)
            field(book.title, bookRef)
            field(book.author, bookRef)
            field(book.description, bookRef)
            field(book.coverImage, bookRef)
            field(book.sourceId, bookRef)
            field(book.originalUri, bookRef)
            field(book.category, bookRef)
            book.chapters.forEachIndexed { chapterIndex, chapter ->
                val ref = ShuYueMigrationEntityRef("chapter", index = chapterIndex, parentId = book.id)
                field(chapter.id, ref)
                field(chapter.bookId, ref)
                field(chapter.title, ref)
                field(chapter.href, ref)
                field(chapter.text, ref)
            }
        }
        backup.progress.forEachIndexed { index, progress ->
            val ref = ShuYueMigrationEntityRef("progress", index = index)
            field(progress.bookId, ref)
            field(progress.chapterId, ref)
        }
        backup.installedPlugins.forEachIndexed { index, installed ->
            val ref = ShuYueMigrationEntityRef("installedPlugin", index = index)
            manifest(installed.manifest, ref)
            field(installed.trustedSigningKeyFingerprint, ref)
        }
        backup.pluginInstallations.forEachIndexed { index, installation ->
            val ref = ShuYueMigrationEntityRef("pluginInstallation", index = index)
            manifest(installation.plugin, ref)
            field(installation.script, ref)
        }
        backup.pluginRepositories.forEachIndexed { index, repository ->
            val ref = ShuYueMigrationEntityRef("repository", index = index)
            field(repository.baseUrl, ref)
            field(repository.manifest.meta.name, ref)
            field(repository.manifest.meta.website, ref)
            field(repository.manifest.meta.signingKeyFingerprint, ref)
            repository.entries.forEachIndexed { entryIndex, entry ->
                val entryRef = ShuYueMigrationEntityRef("repositoryEntry", index = entryIndex)
                field(entry.id, entryRef)
                field(entry.name, entryRef)
                field(entry.version, entryRef)
                field(entry.lang, entryRef)
                field(entry.scriptUrl, entryRef)
                field(entry.iconUrl, entryRef)
                field(entry.description, entryRef)
                entry.sources.forEachIndexed { sourceIndex, item ->
                    source(item, ShuYueMigrationEntityRef("repositorySource", index = sourceIndex, parentId = entry.id))
                }
            }
        }
        field(backup.selectedPluginRepositoryUrl, ShuYueMigrationEntityRef("selectedRepository"))
        backup.pluginCredentials.forEachIndexed { index, credential ->
            val ref = ShuYueMigrationEntityRef("credential", index = index)
            field(credential.sourceId, ref)
            field(credential.username, ref)
            field(credential.password, ref)
        }
        backup.pluginCookies.forEachIndexed { index, cookie ->
            val ref = ShuYueMigrationEntityRef("cookie", index = index)
            field(cookie.sourceId, ref)
            field(cookie.name, ref)
            field(cookie.value, ref)
            field(cookie.domain, ref)
            field(cookie.path, ref)
        }
        backup.pluginPreferences.forEach { (key, value) ->
            val ref = ShuYueMigrationEntityRef("preference")
            field(key, ref)
            field(value, ref)
        }
        backup.pluginImageParsingPolicies.keys.forEach { key ->
            field(key, ShuYueMigrationEntityRef("imagePolicy"))
        }
    }

    private fun validateFieldBounds(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        fun field(
            value: String?,
            max: Int,
            ref: ShuYueMigrationEntityRef,
            applyGenericFieldLimit: Boolean = true,
        ) {
            val effectiveMax = if (applyGenericFieldLimit) minOf(max, limits.maxFieldChars) else max
            if (value != null && value.length > effectiveMax) {
                findings += error(
                    ShuYueMigrationIssueCode.FIELD_LENGTH_LIMIT_EXCEEDED,
                    ref,
                    "A text field exceeds the configured length limit.",
                )
            }
        }
        backup.books.forEachIndexed { bookIndex, book ->
            val bookRef = ShuYueMigrationEntityRef("book", index = bookIndex)
            field(book.id, limits.maxIdentifierChars, bookRef)
            field(book.title, limits.maxTitleChars, bookRef)
            field(book.author, limits.maxTitleChars, bookRef)
            field(book.description, limits.maxDescriptionChars, bookRef)
            field(book.coverImage, limits.maxUrlChars, bookRef)
            field(book.sourceId, limits.maxIdentifierChars, bookRef)
            field(book.originalUri, limits.maxUrlChars, bookRef)
            field(book.category, limits.maxCategoryChars, bookRef)
            book.chapters.forEachIndexed { chapterIndex, chapter ->
                val ref = ShuYueMigrationEntityRef("chapter", index = chapterIndex, parentId = book.id)
                field(chapter.id, limits.maxIdentifierChars, ref)
                field(chapter.bookId, limits.maxIdentifierChars, ref)
                field(chapter.title, limits.maxTitleChars, ref)
                field(chapter.href, limits.maxUrlChars, ref)
            }
        }
        backup.progress.forEachIndexed { index, progress ->
            val ref = ShuYueMigrationEntityRef("progress", index = index)
            field(progress.bookId, limits.maxIdentifierChars, ref)
            field(progress.chapterId, limits.maxIdentifierChars, ref)
        }
        fun manifest(manifest: ShuYueV1PluginManifest, ref: ShuYueMigrationEntityRef) {
            field(manifest.id, limits.maxIdentifierChars, ref)
            field(manifest.name, limits.maxPluginFieldChars, ref)
            field(manifest.version, limits.maxPluginFieldChars, ref)
            field(manifest.lang, limits.maxPluginFieldChars, ref)
            field(manifest.signature, limits.maxPluginFieldChars, ref)
            field(manifest.minRuntimeVersion, limits.maxPluginFieldChars, ref)
            field(manifest.repository, limits.maxUrlChars, ref)
            // Executable bodies are bulk quarantine data with a byte-specific limit. They are not
            // ordinary metadata fields and therefore do not inherit maxFieldChars.
            field(manifest.script, limits.maxPluginScriptBytes, ref, applyGenericFieldLimit = false)
            manifest.sources.forEachIndexed { index, source ->
                val sourceRef = ShuYueMigrationEntityRef("pluginSource", index = index, parentId = manifest.id)
                field(source.id, limits.maxIdentifierChars, sourceRef)
                field(source.name, limits.maxPluginFieldChars, sourceRef)
                field(source.lang, limits.maxPluginFieldChars, sourceRef)
                field(source.baseUrl, limits.maxUrlChars, sourceRef)
            }
        }
        backup.installedPlugins.forEachIndexed { index, installed ->
            val ref = ShuYueMigrationEntityRef("installedPlugin", index = index)
            manifest(installed.manifest, ref)
            field(installed.trustedSigningKeyFingerprint, limits.maxRepositoryFieldChars, ref)
        }
        backup.pluginInstallations.forEachIndexed { index, installation ->
            val ref = ShuYueMigrationEntityRef("pluginInstallation", index = index)
            manifest(installation.plugin, ref)
            field(installation.script, limits.maxPluginScriptBytes, ref, applyGenericFieldLimit = false)
        }
        backup.pluginRepositories.forEachIndexed { index, repository ->
            val ref = ShuYueMigrationEntityRef("repository", index = index)
            field(repository.baseUrl, limits.maxUrlChars, ref)
            field(repository.manifest.meta.name, limits.maxRepositoryFieldChars, ref)
            field(repository.manifest.meta.website, limits.maxUrlChars, ref)
            field(repository.manifest.meta.signingKeyFingerprint, limits.maxRepositoryFieldChars, ref)
            repository.entries.forEachIndexed { entryIndex, entry ->
                val entryRef = ShuYueMigrationEntityRef("repositoryEntry", index = entryIndex, parentId = repository.baseUrl)
                field(entry.id, limits.maxIdentifierChars, entryRef)
                field(entry.name, limits.maxPluginFieldChars, entryRef)
                field(entry.version, limits.maxPluginFieldChars, entryRef)
                field(entry.lang, limits.maxPluginFieldChars, entryRef)
                field(entry.scriptUrl, limits.maxUrlChars, entryRef)
                field(entry.iconUrl, limits.maxUrlChars, entryRef)
                field(entry.description, limits.maxDescriptionChars, entryRef)
                entry.sources.forEachIndexed { sourceIndex, source ->
                    val sourceRef = ShuYueMigrationEntityRef("repositorySource", index = sourceIndex, parentId = entry.id)
                    field(source.id, limits.maxIdentifierChars, sourceRef)
                    field(source.name, limits.maxPluginFieldChars, sourceRef)
                    field(source.lang, limits.maxPluginFieldChars, sourceRef)
                    field(source.baseUrl, limits.maxUrlChars, sourceRef)
                }
            }
        }
        field(
            backup.selectedPluginRepositoryUrl,
            limits.maxUrlChars,
            ShuYueMigrationEntityRef("selectedRepository"),
        )
        backup.pluginCredentials.forEachIndexed { index, credential ->
            val ref = ShuYueMigrationEntityRef("credential", index = index)
            field(credential.sourceId, limits.maxIdentifierChars, ref)
            field(credential.username, limits.maxCredentialFieldChars, ref)
            field(credential.password, limits.maxCredentialFieldChars, ref)
        }
        backup.pluginCookies.forEachIndexed { index, cookie ->
            val ref = ShuYueMigrationEntityRef("cookie", index = index)
            field(cookie.sourceId, limits.maxIdentifierChars, ref)
            field(cookie.name, limits.maxCookieFieldChars, ref)
            field(cookie.value, limits.maxCookieFieldChars, ref)
            field(cookie.domain, limits.maxCookieFieldChars, ref)
            field(cookie.path, limits.maxCookieFieldChars, ref)
        }
        backup.pluginPreferences.entries.forEach { (key, value) ->
            val ref = ShuYueMigrationEntityRef("preference")
            field(key, limits.maxPreferenceFieldChars, ref)
            field(value, limits.maxPreferenceFieldChars, ref)
        }
        backup.pluginImageParsingPolicies.keys.forEach { key ->
            field(key, limits.maxIdentifierChars, ShuYueMigrationEntityRef("imagePolicy"))
        }
    }

    private fun validateProgress(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (backup.progress.size > limits.maxProgress) {
            findings += error(
                ShuYueMigrationIssueCode.PROGRESS_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("progress"),
                "The progress count exceeds the configured limit.",
            )
            return
        }
        val books = backup.books.associateBy { it.id }
        val chapters = backup.books
            .asSequence()
            .flatMap { book -> book.chapters.asSequence().map { chapter -> (book.id to chapter.id) to chapter } }
            .toMap()
        val seen = mutableSetOf<String>()
        backup.progress.forEachIndexed { index, progress ->
            val ref = ShuYueMigrationEntityRef(
                kind = "progress",
                id = "${progress.bookId}/${progress.chapterId}",
                index = index,
            )
            if (!seen.add(progress.bookId)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_PROGRESS,
                    ref,
                    "A progress record occurs more than once.",
                )
            }
            if (progress.bookId !in books) {
                findings += error(
                    ShuYueMigrationIssueCode.MISSING_BOOK_REFERENCE,
                    ref,
                    "Progress refers to a missing book.",
                )
            }
            val chapter = chapters[progress.bookId to progress.chapterId]
            if (chapter == null) {
                findings += error(
                    ShuYueMigrationIssueCode.MISSING_CHAPTER_REFERENCE,
                    ref,
                    "Progress refers to a missing chapter.",
                )
            } else if (chapter.bookId != progress.bookId) {
                findings += error(
                    ShuYueMigrationIssueCode.PROGRESS_BOOK_MISMATCH,
                    ref,
                    "Progress book and chapter references do not match.",
                )
            } else if (progress.charOffset !in 0..chapter.text.length) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_CHAR_OFFSET,
                    ref,
                    "A character offset is outside the chapter text bounds.",
                )
            }
            if (!progress.progress.isFinite() || progress.progress !in 0f..1f) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_PROGRESS,
                    ref,
                    "A progress fraction must be finite and between zero and one.",
                )
            }
            validateTimestamp(progress.updatedAt, ref, findings)
        }
        // The current DTO has no independent progress count limit.  Its records are bounded by
        // the chapter count plus the raw-size cap, so retain this parameter in the signature for
        // forward-compatible validation without silently inventing a second wire field.
        @Suppress("UNUSED_VARIABLE")
        val ignoredLimits = limits
    }

    private fun validatePlugins(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (backup.installedPlugins.size > limits.maxInstalledPlugins ||
            backup.pluginInstallations.size > limits.maxPluginInstallations
        ) {
            findings += error(
                ShuYueMigrationIssueCode.PLUGIN_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("plugins"),
                "The plugin count exceeds the configured limit.",
            )
            return
        }
        val installedById = backup.installedPlugins.mapIndexed { index, installed ->
            installed.manifest.id to (index to installed)
        }.toMap()
        val installationById = backup.pluginInstallations.mapIndexed { index, installation ->
            installation.plugin.id to (index to installation)
        }.toMap()
        // Keep the validation report stable without relying on JVM-only sorted collections.
        // `distinct().sorted()` returns a deterministic list on every Kotlin target.
        val allIds = (installedById.keys + installationById.keys).distinct().sorted()
        allIds.forEach { id ->
            val installed = installedById[id]?.second
            val installation = installationById[id]?.second
            if (installed == null || installation == null) {
                findings += warning(
                    ShuYueMigrationIssueCode.PLUGIN_INSTALLATION_SET_MISMATCH,
                    ShuYueMigrationEntityRef("plugin", id),
                    "Installed-plugin metadata and persisted installation metadata do not match.",
                )
            } else if (installed.manifest != installation.plugin) {
                findings += warning(
                    ShuYueMigrationIssueCode.PLUGIN_MANIFEST_MISMATCH,
                    ShuYueMigrationEntityRef("plugin", id),
                    "Installed-plugin metadata and persisted installation metadata differ.",
                )
            }
        }
        val installedIds = mutableSetOf<String>()
        backup.installedPlugins.forEachIndexed { index, installed ->
            val ref = ShuYueMigrationEntityRef("installedPlugin", installed.manifest.id, index = index)
            if (!installedIds.add(installed.manifest.id)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_PLUGIN_ID,
                    ref,
                    "A plugin identifier occurs more than once in installed-plugin metadata.",
                )
            }
            validateManifest(installed.manifest, ref, limits, findings)
            validateTimestamp(installed.installedAt, ref, findings)
        }
        val installationIds = mutableSetOf<String>()
        backup.pluginInstallations.forEachIndexed { index, installation ->
            val ref = ShuYueMigrationEntityRef("pluginInstallation", installation.plugin.id, index = index)
            if (!installationIds.add(installation.plugin.id)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_PLUGIN_ID,
                    ref,
                    "A plugin identifier occurs more than once in persisted installations.",
                )
            }
            validateManifest(installation.plugin, ref, limits, findings)
            val bytes = installation.script.encodeToByteArray().size
            if (bytes > limits.maxPluginScriptBytes) {
                findings += error(
                    ShuYueMigrationIssueCode.PLUGIN_SCRIPT_LIMIT_EXCEEDED,
                    ref,
                    "A plugin script exceeds the configured limit.",
                )
            }
            findings += warning(
                ShuYueMigrationIssueCode.PLUGIN_SCRIPT_QUARANTINED,
                ref,
                "Plugin script is retained only as untrusted quarantine metadata.",
            )
        }
        var totalScriptBytes = 0L
        backup.installedPlugins.forEach {
            totalScriptBytes = safeAddTextChars(
                totalScriptBytes,
                it.manifest.script.encodeToByteArray().size.toLong(),
            )
        }
        backup.pluginInstallations.forEach {
            totalScriptBytes = safeAddTextChars(
                totalScriptBytes,
                it.plugin.script.encodeToByteArray().size.toLong(),
            )
            totalScriptBytes = safeAddTextChars(
                totalScriptBytes,
                it.script.encodeToByteArray().size.toLong(),
            )
        }
        if (totalScriptBytes > limits.maxTotalPluginScriptBytes) {
            findings += error(
                ShuYueMigrationIssueCode.TOTAL_PLUGIN_SCRIPT_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("pluginScripts"),
                "The total plugin script size exceeds the configured limit.",
            )
        }
        var totalSourceCount = 0L
        backup.installedPlugins.forEach { totalSourceCount = safeAddTextChars(totalSourceCount, it.manifest.sources.size.toLong()) }
        backup.pluginInstallations.forEach { totalSourceCount = safeAddTextChars(totalSourceCount, it.plugin.sources.size.toLong()) }
        backup.pluginRepositories.forEach { repository ->
            repository.entries.forEach { entry ->
                totalSourceCount = safeAddTextChars(totalSourceCount, entry.sources.size.toLong())
            }
        }
        if (totalSourceCount > limits.maxTotalPluginSources.toLong()) {
            findings += error(
                ShuYueMigrationIssueCode.PLUGIN_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("pluginSources"),
                "The total plugin source count exceeds the configured limit.",
            )
        }
    }

    private fun validateManifest(
        manifest: ShuYueV1PluginManifest,
        ref: ShuYueMigrationEntityRef,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        validateTextIdentifier(manifest.id, ref, findings)
        validatePrintableField(manifest.name, "plugin name", ref, findings)
        validatePrintableField(manifest.version, "plugin version", ref, findings)
        validatePrintableField(manifest.lang, "plugin language", ref, findings)
        validateOptionalPrintableField(manifest.signature, "plugin signature", ref, findings)
        validateOptionalPrintableField(manifest.minRuntimeVersion, "plugin runtime", ref, findings)
        validateOptionalPrintableField(manifest.repository, "plugin repository", ref, findings)
        if (manifest.repository != null && !isSafeHttpUrl(manifest.repository)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                ref,
                "A plugin repository URL is invalid.",
            )
        }
        if (manifest.versionCode < 0 || manifest.nsfw < 0) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_SOURCE_DESCRIPTOR,
                ref,
                "A plugin manifest contains an invalid numeric field.",
            )
        }
        if (manifest.script.encodeToByteArray().size > limits.maxPluginScriptBytes) {
            findings += error(
                ShuYueMigrationIssueCode.PLUGIN_SCRIPT_LIMIT_EXCEEDED,
                ref,
                "A plugin manifest script exceeds the configured limit.",
            )
        }
        findings += warning(
            ShuYueMigrationIssueCode.PLUGIN_SCRIPT_QUARANTINED,
            ref,
            "Plugin script is retained only as untrusted quarantine metadata.",
        )
        val sourceIds = mutableSetOf<String>()
        manifest.sources.forEachIndexed { index, source ->
            val sourceRef = ShuYueMigrationEntityRef("pluginSource", source.id, manifest.id, index)
            if (!sourceIds.add(source.id)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_SOURCE_ID,
                    sourceRef,
                    "A source identifier occurs more than once in a plugin manifest.",
                )
            }
            validateSourceDescriptor(source, sourceRef, findings)
        }
        if (manifest.sources.size > limits.maxPluginSources) {
            findings += error(
                ShuYueMigrationIssueCode.PLUGIN_LIMIT_EXCEEDED,
                ref,
                "The source count for a plugin exceeds the configured limit.",
            )
        }
    }

    private fun validateSourceDescriptor(
        source: ShuYueV1PluginSourceDescriptor,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        validateTextIdentifier(source.id, ref, findings)
        validatePrintableField(source.name, "source name", ref, findings)
        validatePrintableField(source.lang, "source language", ref, findings)
        if (!isSafeHttpUrl(source.baseUrl)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_SOURCE_DESCRIPTOR,
                ref,
                "A source base URL is invalid.",
            )
        }
    }

    private fun validateRepositories(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (backup.pluginRepositories.size > limits.maxRepositories) {
            findings += error(
                ShuYueMigrationIssueCode.REPOSITORY_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("repositories"),
                "The repository count exceeds the configured limit.",
            )
            return
        }
        val urls = mutableSetOf<String>()
        var totalEntries = 0L
        backup.pluginRepositories.forEachIndexed { index, repository ->
            val ref = ShuYueMigrationEntityRef("repository", repository.baseUrl, index = index)
            if (!urls.add(repository.baseUrl)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_REPOSITORY_URL,
                    ref,
                    "A repository URL occurs more than once.",
                )
            }
            if (!isSafeHttpUrl(repository.baseUrl)) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                    ref,
                    "A repository URL is invalid.",
                )
            }
            validateRepositoryManifest(repository.manifest, ref, findings)
            validateTimestamp(repository.lastLoadedAt, ref, findings)
            totalEntries = safeAddTextChars(totalEntries, repository.entries.size.toLong())
            if (repository.entries.size > limits.maxRepositoryEntriesPerRepository) {
                findings += error(
                    ShuYueMigrationIssueCode.REPOSITORY_ENTRY_LIMIT_EXCEEDED,
                    ref,
                    "The repository entry count exceeds the configured limit.",
                )
            }
            val entryIds = mutableSetOf<String>()
            repository.entries.forEachIndexed { entryIndex, entry ->
                val entryRef = ShuYueMigrationEntityRef("repositoryEntry", entry.id, repository.baseUrl, entryIndex)
                if (!entryIds.add(entry.id)) {
                    findings += error(
                        ShuYueMigrationIssueCode.DUPLICATE_PLUGIN_ID,
                        entryRef,
                        "A repository plugin identifier occurs more than once.",
                    )
                }
                validateTextIdentifier(entry.id, entryRef, findings)
                validatePrintableField(entry.name, "repository plugin name", entryRef, findings)
                validatePrintableField(entry.version, "repository plugin version", entryRef, findings)
                validatePrintableField(entry.lang, "repository plugin language", entryRef, findings)
                validatePrintableField(entry.scriptUrl, "repository script URL", entryRef, findings)
                if (!isSafeHttpUrl(entry.scriptUrl)) {
                    findings += error(
                        ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                        entryRef,
                        "A repository script URL is invalid.",
                    )
                }
                if (entry.iconUrl != null && !isSafeHttpUrl(entry.iconUrl)) {
                    findings += error(
                        ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                        entryRef,
                        "A repository icon URL is invalid.",
                    )
                }
                if (entry.versionCode < 0 || entry.nsfw < 0) {
                    findings += error(
                        ShuYueMigrationIssueCode.INVALID_SOURCE_DESCRIPTOR,
                        entryRef,
                        "A repository plugin entry contains an invalid numeric field.",
                    )
                }
                val sourceIds = mutableSetOf<String>()
                entry.sources.forEachIndexed { sourceIndex, source ->
                    if (!sourceIds.add(source.id)) {
                        findings += error(
                            ShuYueMigrationIssueCode.DUPLICATE_SOURCE_ID,
                            ShuYueMigrationEntityRef("repositorySource", source.id, entry.id, sourceIndex),
                            "A source identifier occurs more than once in a repository entry.",
                        )
                    }
                    validateSourceDescriptor(
                        source,
                        ShuYueMigrationEntityRef("repositorySource", source.id, entry.id, sourceIndex),
                        findings,
                    )
                }
            }
        }
        if (totalEntries > limits.maxRepositoryEntries.toLong()) {
            findings += error(
                ShuYueMigrationIssueCode.REPOSITORY_ENTRY_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("repositoryEntries"),
                "The total repository entry count exceeds the configured limit.",
            )
        }
        if (!backup.selectedPluginRepositoryUrl.isNullOrBlank()) {
            if (!isSafeHttpUrl(backup.selectedPluginRepositoryUrl)) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                    ShuYueMigrationEntityRef("selectedRepository"),
                    "The selected repository URL is invalid.",
                )
            } else if (backup.selectedPluginRepositoryUrl !in urls) {
                findings += warning(
                    ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                    ShuYueMigrationEntityRef("selectedRepository"),
                    "The selected repository is not present in the repository list.",
                )
            }
        }
    }

    private fun validateRepositoryManifest(
        manifest: ShuYueV1PluginRepositoryManifest,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        validatePrintableField(manifest.meta.name, "repository name", ref, findings)
        validateOptionalPrintableField(manifest.meta.website, "repository website", ref, findings)
        validateOptionalPrintableField(
            manifest.meta.signingKeyFingerprint,
            "repository signing fingerprint",
            ref,
            findings,
        )
        if (manifest.meta.website != null && !isSafeHttpUrl(manifest.meta.website)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_REPOSITORY_URL,
                ref,
                "A repository website URL is invalid.",
            )
        }
    }

    private fun validateSecretsAndPreferences(
        backup: ShuYueBackupV1,
        limits: ShuYueBackupV1Limits,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (backup.pluginCredentials.size > limits.maxCredentials) {
            findings += error(
                ShuYueMigrationIssueCode.CREDENTIAL_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("credentials"),
                "The credential count exceeds the configured limit.",
            )
            return
        }
        val credentialSourceIds = mutableSetOf<String>()
        backup.pluginCredentials.forEachIndexed { index, credential ->
            val ref = ShuYueMigrationEntityRef("credential", credential.sourceId, index = index)
            validateTextIdentifier(credential.sourceId, ref, findings)
            if (!credentialSourceIds.add(credential.sourceId)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_CREDENTIAL_SOURCE_ID,
                    ref,
                    "A credential source identifier occurs more than once.",
                )
            }
            if (credential.username.length > limits.maxCredentialFieldChars ||
                credential.password.length > limits.maxCredentialFieldChars
            ) {
                findings += error(
                    ShuYueMigrationIssueCode.CREDENTIAL_LIMIT_EXCEEDED,
                    ref,
                    "A credential field exceeds the configured limit.",
                )
            }
            validateNoControl(credential.username, "credential username", ref, findings)
            validateNoControl(credential.password, "credential password", ref, findings)
            validateTimestamp(credential.updatedAt, ref, findings)
        }
        if (backup.pluginCredentials.isNotEmpty()) {
            findings += warning(
                ShuYueMigrationIssueCode.SECRET_REQUIRES_CONSENT,
                ShuYueMigrationEntityRef("credentials"),
                "Credentials are quarantined and require explicit user consent.",
            )
        }

        if (backup.pluginCookies.size > limits.maxCookies) {
            findings += error(
                ShuYueMigrationIssueCode.COOKIE_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("cookies"),
                "The cookie count exceeds the configured limit.",
            )
            return
        }
        val cookieKeys = mutableSetOf<String>()
        backup.pluginCookies.forEachIndexed { index, cookie ->
            val ref = ShuYueMigrationEntityRef("cookie", cookie.sourceId, index = index)
            validateTextIdentifier(cookie.sourceId, ref, findings)
            val cookieKey = "${cookie.sourceId}\u0000${cookie.domain}\u0000${cookie.path}\u0000${cookie.name}"
            if (!cookieKeys.add(cookieKey)) {
                findings += error(
                    ShuYueMigrationIssueCode.DUPLICATE_COOKIE,
                    ref,
                    "A cookie identity occurs more than once.",
                )
            }
            if (cookie.name.length > limits.maxCookieFieldChars ||
                cookie.value.length > limits.maxCookieFieldChars ||
                cookie.domain.length > limits.maxCookieFieldChars ||
                cookie.path.length > limits.maxCookieFieldChars
            ) {
                findings += error(
                    ShuYueMigrationIssueCode.COOKIE_LIMIT_EXCEEDED,
                    ref,
                    "A cookie field exceeds the configured limit.",
                )
            }
            if (!isSafeCookieName(cookie.name) || !isSafeCookieDomain(cookie.domain) ||
                !isSafeCookiePath(cookie.path) || cookie.value.any { it == '\r' || it == '\n' } ||
                cookie.expiresAt?.let { it < 0L } == true
            ) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_COOKIE,
                    ref,
                    "A cookie has an invalid name, domain, path, value, or expiry.",
                )
            }
        }
        if (backup.pluginCookies.isNotEmpty()) {
            findings += warning(
                ShuYueMigrationIssueCode.SECRET_REQUIRES_CONSENT,
                ShuYueMigrationEntityRef("cookies"),
                "Cookies are quarantined and require explicit user consent.",
            )
        }

        if (backup.pluginPreferences.size > limits.maxPreferences) {
            findings += error(
                ShuYueMigrationIssueCode.PREFERENCE_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("preferences"),
                "The preference count exceeds the configured limit.",
            )
        }
        backup.pluginPreferences.entries.sortedBy { it.key }.forEach { (key, value) ->
            val ref = ShuYueMigrationEntityRef("preference", key)
            val separator = key.indexOf('\u0000')
            val secondSeparator = if (separator < 0) -1 else key.indexOf('\u0000', separator + 1)
            val sourceId = if (separator > 0) key.substring(0, separator) else ""
            val preferenceKey = if (separator > 0) key.substring(separator + 1) else ""
            if (key.length > limits.maxPreferenceFieldChars ||
                value.length > limits.maxPreferenceFieldChars ||
                separator <= 0 || secondSeparator >= 0 || sourceId.isBlank() || preferenceKey.isBlank() ||
                sourceId.any(Char::isISOControl) || preferenceKey.any(Char::isISOControl) ||
                value.any(Char::isISOControl)
            ) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_PREFERENCE,
                    ShuYueMigrationEntityRef("preference", index = 0),
                    "A plugin preference key or value is invalid.",
                )
            }
        }
        if (backup.pluginImageParsingPolicies.size > limits.maxImageParsingPolicies) {
            findings += error(
                ShuYueMigrationIssueCode.IMAGE_POLICY_LIMIT_EXCEEDED,
                ShuYueMigrationEntityRef("imagePolicies"),
                "The image policy count exceeds the configured limit.",
            )
        }
        backup.pluginImageParsingPolicies.keys.sorted().forEach { key ->
            if (key.isBlank() || key.any(Char::isISOControl)) {
                findings += error(
                    ShuYueMigrationIssueCode.INVALID_PREFERENCE,
                    ShuYueMigrationEntityRef("imagePolicy", key),
                    "An image policy key is invalid.",
                )
            }
        }
    }

    private fun validateReaderSettings(
        settings: ShuYueV1ReaderSettings,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (!settings.fontSizeSp.isFinite() || settings.fontSizeSp <= 0f || settings.fontSizeSp > 200f ||
            settings.lineHeightPercent <= 0 || settings.lineHeightPercent > 1_000 ||
            settings.pageChars <= 0 || settings.pageChars > 10_000_000
        ) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_READER_SETTINGS,
                ShuYueMigrationEntityRef("readerSettings"),
                "Reader settings contain an invalid numeric value.",
            )
        }
    }

    private fun validateTextIdentifier(
        value: String,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (value.isBlank() || value.any(Char::isISOControl)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_IDENTIFIER,
                ref,
                "An identifier is blank or contains a control character.",
            )
        }
    }

    private fun validatePrintableField(
        value: String,
        field: String,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (value.isBlank() || value.any(::isUnsafeTextControl)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_IDENTIFIER,
                ref,
                "A $field is blank or contains a control character.",
            )
        }
    }

    private fun validateOptionalPrintableField(
        value: String?,
        field: String,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (value != null && value.any(::isUnsafeTextControl)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_IDENTIFIER,
                ref,
                "A $field contains a control character.",
            )
        }
    }

    private fun validateNoControl(
        value: String,
        field: String,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (value.any(Char::isISOControl)) {
            findings += error(
                ShuYueMigrationIssueCode.INVALID_COOKIE,
                ref,
                "A $field contains an unsupported control character.",
            )
        }
    }

    private fun validateTimestamp(
        value: Long,
        ref: ShuYueMigrationEntityRef,
        findings: MutableList<ShuYueMigrationIssue>,
    ) {
        if (value < 0L) {
            findings += error(
                ShuYueMigrationIssueCode.NEGATIVE_TIMESTAMP,
                ref,
                "A timestamp cannot be negative.",
            )
        }
    }

    private fun isSafeCookieName(value: String): Boolean {
        if (value.isBlank()) return false
        // RFC 6265 token separators.  Being conservative here prevents a value from becoming a
        // second Set-Cookie attribute if a later explicit-consent flow writes it to a cookie jar.
        val separators = "()<>@,;:\\\"/[]?={} \t"
        return value.all { !it.isISOControl() && it !in separators }
    }

    private fun isSafeCookieDomain(value: String): Boolean {
        if (value.isBlank() || value.any(Char::isISOControl) || value.contains('/') ||
            value.contains('\\') || value.contains(':') || value.contains('@') ||
            value.any(Char::isWhitespace)
        ) return false
        val domain = value.removePrefix(".")
        if (domain.isBlank() || domain.startsWith('.') || domain.endsWith('.')) return false
        return domain.split('.').all { label ->
            label.isNotEmpty() && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun isSafeCookiePath(value: String): Boolean =
        value.startsWith('/') && value.none { it == '\r' || it == '\n' || it == ';' }

    private fun isSafeHttpUrl(value: String): Boolean =
        if (!(value.startsWith("https://") || value.startsWith("http://"))) {
            false
        } else {
            val authorityStart = value.indexOf("://") + 3
            val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
                .let { if (it < 0) value.length else it }
            val authority = value.substring(authorityStart, authorityEnd)
            if (authority.isBlank() || authority.any { it.isWhitespace() || it.isISOControl() } ||
                authority.contains('@')
            ) {
                false
            } else if (authority.startsWith('[')) {
                authority.contains(']') && authority.substringAfter(']').let { suffix ->
                    suffix.isEmpty() || suffix.startsWith(':') && suffix.drop(1).all(Char::isDigit)
                }
            } else {
                val host = authority.substringBefore(':')
                val port = authority.substringAfter(':', "")
                host.isNotBlank() && host.split('.').all { label ->
                    label.isNotEmpty() && !label.startsWith('-') && !label.endsWith('-') &&
                        label.all { it.isLetterOrDigit() || it == '-' }
                } && (port.isEmpty() || port.all(Char::isDigit))
            }
        }

    private fun safeAddTextChars(current: Long, addition: Long): Long =
        if (addition > Long.MAX_VALUE - current) Long.MAX_VALUE else current + addition

    private fun sortIssues(values: List<ShuYueMigrationIssue>): List<ShuYueMigrationIssue> =
        values.sortedWith(
            compareBy<ShuYueMigrationIssue> { it.severity.ordinal }
                .thenBy { it.code }
                .thenBy { it.entityRef?.kind.orEmpty() }
                .thenBy { it.entityRef?.id.orEmpty() }
                .thenBy { it.entityRef?.parentId.orEmpty() }
                .thenBy { it.entityRef?.index ?: -1 },
        )

    private fun error(
        code: String,
        ref: ShuYueMigrationEntityRef?,
        message: String,
    ): ShuYueMigrationIssue = ShuYueMigrationIssue(ShuYueMigrationIssueSeverity.ERROR, code, ref, message)

    private fun warning(
        code: String,
        ref: ShuYueMigrationEntityRef?,
        message: String,
    ): ShuYueMigrationIssue = ShuYueMigrationIssue(ShuYueMigrationIssueSeverity.WARNING, code, ref, message)
}

/** Mutable-list adapter that prevents hostile inputs from allocating unbounded issue reports. */
private class CappedIssueList(maxIssues: Int) : AbstractMutableList<ShuYueMigrationIssue>() {
    val capacity: Int = maxIssues.coerceAtLeast(1)
    private val delegate = ArrayList<ShuYueMigrationIssue>(capacity)
    var dropped: Boolean = false
        private set

    override val size: Int get() = delegate.size

    override fun get(index: Int): ShuYueMigrationIssue = delegate[index]

    override fun set(index: Int, element: ShuYueMigrationIssue): ShuYueMigrationIssue =
        delegate.set(index, element)

    override fun add(index: Int, element: ShuYueMigrationIssue) {
        if (delegate.size >= capacity) {
            dropped = true
            return
        }
        delegate.add(index, element)
    }

    override fun removeAt(index: Int): ShuYueMigrationIssue = delegate.removeAt(index)
}

private fun isUnsafeTextControl(value: Char): Boolean =
    value == '\u0000' || (value.isISOControl() && value != '\n' && value != '\r' && value != '\t')

private fun hasMalformedSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
            index += 2
        } else if (current.isLowSurrogate()) {
            return true
        } else {
            index++
        }
    }
    return false
}
