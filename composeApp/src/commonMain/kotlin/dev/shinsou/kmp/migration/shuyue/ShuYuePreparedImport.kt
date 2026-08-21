package dev.shinsou.kmp.migration.shuyue

import dev.shinsou.kmp.content.ContentMigrationLedgerMutation
import dev.shinsou.kmp.plugin.Sha256

/** User choices which are cryptographically bound to the migration ledger result. */
public class ShuYueImportSelection(
    /** Null means every staged book; an empty set explicitly imports no books. */
    selectedBookIds: Set<String>? = null,
    public val includeProgress: Boolean = true,
    public val includeReaderSettings: Boolean = true,
    /** Explicit opt-in; selecting books alone never authorizes encrypted body upload. */
    public val includeContentBodySync: Boolean = false,
    /** Null means every staged script remains in quarantine; execution is never implied. */
    quarantinedPluginDigests: Set<String>? = null,
) {
    public val selectedBookIds: Set<String>? = selectedBookIds?.immutableSetSnapshot()
    public val quarantinedPluginDigests: Set<String>? = quarantinedPluginDigests?.immutableSetSnapshot()

    init {
        selectedBookIds?.let { ids ->
            require(ids.size <= MAX_SELECTION_ITEMS) { "Too many selected ShuYue books" }
            ids.forEach { requireSafeSelectionValue(it, "Selected ShuYue book id") }
        }
        quarantinedPluginDigests?.let { digests ->
            require(digests.size <= MAX_SELECTION_ITEMS) { "Too many selected ShuYue plugins" }
            digests.forEach { require(SHA256_HEX.matches(it)) { "Selected plugin digest is invalid" } }
        }
    }
}

/** A safe preparation result; raw DTOs, chapter bodies, scripts, and secrets never escape. */
public data class ShuYueImportPreparationResult(
    val inspection: ShuYueMigrationInspectionResult,
    val preparedImport: ShuYuePreparedImport? = null,
) {
    public val accepted: Boolean get() = preparedImport != null && inspection.canStage

    init {
        require((preparedImport != null) == (inspection.accepted && inspection.canStage)) {
            "Prepared ShuYue import does not match its inspection result"
        }
    }

    override fun toString(): String =
        "ShuYueImportPreparationResult(accepted=$accepted, inspection=$inspection, prepared=<redacted>)"
}

/**
 * Opaque, in-memory capability holding the exact validated snapshot.  It is intentionally not
 * serializable and its constructor is internal so JSON cannot manufacture an import session.
 */
public class ShuYuePreparedImport internal constructor(
    internal val staged: ShuYueStagedMigration,
) {
    private val consentNonce: Any = Any()

    public val preview: ShuYueMigrationPreview get() = staged.session.preview
    public val sourceDigestSha256: String get() = staged.ledgerMutation.sourceDigestSha256
    public val resultFingerprintSha256: String get() = staged.ledgerMutation.resultFingerprintSha256

    public fun availableCredentialSourceIds(): Set<String> =
        staged.session.secretMaterial.credentials.map { it.sourceId }.immutableSetSnapshot()

    public fun availableCookieSourceIds(): Set<String> =
        staged.session.secretMaterial.cookies.map { it.sourceId }.immutableSetSnapshot()

    /**
     * Must be called only from an explicit confirmation action.  The returned capability is bound
     * to this exact prepared snapshot and cannot be replayed against another backup.
     */
    public fun confirmSecretImport(
        credentialSourceIds: Set<String>,
        cookieSourceIds: Set<String>,
        confirmedAtEpochMillis: Long,
    ): ShuYueSecretImportConsent {
        require(confirmedAtEpochMillis >= 0) { "Secret-import confirmation time is invalid" }
        val availableCredentials = availableCredentialSourceIds()
        val availableCookies = availableCookieSourceIds()
        val credentials = credentialSourceIds.immutableSetSnapshot()
        val cookies = cookieSourceIds.immutableSetSnapshot()
        require(credentials.all { it in availableCredentials }) { "Unknown ShuYue credential selection" }
        require(cookies.all { it in availableCookies }) { "Unknown ShuYue cookie selection" }
        return ShuYueSecretImportConsent(
            sourceDigestSha256 = sourceDigestSha256,
            credentialSourceIds = credentials,
            cookieSourceIds = cookies,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
            preparedNonce = consentNonce,
        )
    }

    internal fun verifyConsent(consent: ShuYueSecretImportConsent) {
        require(consent.preparedNonce === consentNonce && consent.sourceDigestSha256 == sourceDigestSha256) {
            "Secret-import consent belongs to another prepared backup"
        }
    }

    override fun toString(): String =
        "ShuYuePreparedImport(sourceDigest=${sourceDigestSha256.take(8)}…, content=<redacted>)"
}

/** Non-serializable proof of one explicit secret selection. */
public class ShuYueSecretImportConsent internal constructor(
    public val sourceDigestSha256: String,
    credentialSourceIds: Set<String>,
    cookieSourceIds: Set<String>,
    public val confirmedAtEpochMillis: Long,
    internal val preparedNonce: Any,
) {
    public val credentialSourceIds: Set<String> = credentialSourceIds.immutableSetSnapshot()
    public val cookieSourceIds: Set<String> = cookieSourceIds.immutableSetSnapshot()

    init {
        require(SHA256_HEX.matches(sourceDigestSha256)) { "Secret consent digest is invalid" }
        require(confirmedAtEpochMillis >= 0) { "Secret consent timestamp is invalid" }
        require(this.credentialSourceIds.size <= MAX_SELECTION_ITEMS &&
            this.cookieSourceIds.size <= MAX_SELECTION_ITEMS) { "Secret consent selection is too large" }
        this.credentialSourceIds.forEach { requireSafeSelectionValue(it, "Credential source id") }
        this.cookieSourceIds.forEach { requireSafeSelectionValue(it, "Cookie source id") }
    }

    override fun toString(): String =
        "ShuYueSecretImportConsent(credentials=${credentialSourceIds.size}, " +
            "cookies=${cookieSourceIds.size}, values=<redacted>)"
}

/** Values passed only to a protected-at-rest secret-store transaction. */
public data class ShuYueSecretCredential(
    val sourceId: String,
    val username: String,
    val password: String,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireSafeSelectionValue(sourceId, "Credential source id")
        require(isWellFormedSecret(username) && isWellFormedSecret(password)) {
            "Migrated credential contains invalid text"
        }
        require(updatedAtEpochMillis >= 0) { "Credential update time is invalid" }
    }

    override fun toString(): String = "ShuYueSecretCredential(sourceId=<redacted>, values=<redacted>)"
}

public data class ShuYueSecretCookie(
    val sourceId: String,
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAtEpochMillis: Long?,
) {
    init {
        requireSafeSelectionValue(sourceId, "Cookie source id")
        require(name.isNotEmpty() && name.length <= MAX_SECRET_FIELD_CHARS &&
            name.none { it.isISOControl() || it.isWhitespace() || it in COOKIE_NAME_SEPARATORS }) {
            "Migrated cookie name is invalid"
        }
        require(isWellFormedCookieValue(value)) { "Migrated cookie value is invalid" }
        requireSafeCookieDomain(domain)
        requireSafeCookiePath(path)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0) {
            "Cookie expiry is invalid"
        }
    }

    override fun toString(): String = "ShuYueSecretCookie(sourceId=<redacted>, values=<redacted>)"
}

public class ShuYueSecretWriteBatch internal constructor(
    credentials: List<ShuYueSecretCredential>,
    cookies: List<ShuYueSecretCookie>,
) {
    public val credentials: List<ShuYueSecretCredential> = credentials.immutableListSnapshot()
    public val cookies: List<ShuYueSecretCookie> = cookies.immutableListSnapshot()

    init {
        require(this.credentials.size <= MAX_SELECTION_ITEMS && this.cookies.size <= MAX_SELECTION_ITEMS) {
            "Secret write batch is too large"
        }
        require(this.credentials.map { it.sourceId }.distinct().size == this.credentials.size) {
            "Secret write batch contains duplicate credentials"
        }
        require(this.cookies.map { listOf(it.sourceId, it.domain.lowercase(), it.path, it.name) }
            .distinct().size == this.cookies.size) { "Secret write batch contains duplicate cookies" }
    }

    override fun toString(): String =
        "ShuYueSecretWriteBatch(credentials=${credentials.size}, cookies=${cookies.size}, values=<redacted>)"
}

/** Implementations must roll back the entire batch when one protected-store write fails. */
public interface ShuYueMigrationSecretStore {
    public val protectedAtRest: Boolean
    public suspend fun replaceAtomically(batch: ShuYueSecretWriteBatch)
}

public data class ShuYueSecretImportResult(
    val credentialCount: Int,
    val cookieCount: Int,
)

/** Decode, validate, deep-stage, and fingerprint one immutable byte snapshot. */
public object ShuYueImportPreparer {
    public fun prepare(
        encoded: ByteArray,
        limits: ShuYueBackupV1Limits = ShuYueBackupV1Limits.Default,
    ): ShuYueImportPreparationResult {
        val inspection = ShuYueBackupV1Inspector.inspect(encoded, limits)
        if (!inspection.accepted || !inspection.canStage) {
            return ShuYueImportPreparationResult(inspection)
        }
        val staged = requireNotNull(ShuYueBackupV1Stager.stageWithLedger(encoded, limits)) {
            "Accepted ShuYue backup could not be staged"
        }
        return ShuYueImportPreparationResult(
            inspection = inspection,
            preparedImport = ShuYuePreparedImport(staged),
        )
    }
}

internal data class ResolvedShuYueImportSelection(
    val books: List<ShuYueStagedBook>,
    val chapters: List<ShuYueStagedChapter>,
    val progress: List<ShuYueStagedReadingProgress>,
    val pluginInstallations: List<ShuYueStagedPluginInstallationDescription>,
    val includeReaderSettings: Boolean,
    val includeContentBodySync: Boolean,
    val ledgerMutation: ContentMigrationLedgerMutation,
)

internal fun ShuYuePreparedImport.resolveSelection(
    selection: ShuYueImportSelection,
): ResolvedShuYueImportSelection {
    val session = staged.session
    val availableBooks = session.books.associateBy { it.id }
    val selectedIds = selection.selectedBookIds?.toSet() ?: availableBooks.keys
    require(selectedIds.all { it in availableBooks }) { "Selection contains an unknown ShuYue book" }
    val availableDigests = session.pluginInstallations.mapTo(linkedSetOf()) { it.sha256 }
    val selectedDigests = selection.quarantinedPluginDigests?.toSet() ?: availableDigests
    require(selectedDigests.all { it in availableDigests }) {
        "Selection contains an unknown ShuYue plugin digest"
    }
    val books = session.books.filter { it.id in selectedIds }
    val chapters = session.chapters.filter { it.bookId in selectedIds }
    val progress = if (selection.includeProgress) {
        session.progress.filter { it.bookId in selectedIds }
    } else {
        emptyList()
    }
    val plugins = session.pluginInstallations.filter { it.sha256 in selectedDigests }
    val selectionFingerprint = selectionFingerprint(
        baseResultFingerprint = resultFingerprintSha256,
        selectedBookIds = selectedIds,
        includeProgress = selection.includeProgress,
        includeReaderSettings = selection.includeReaderSettings,
        includeContentBodySync = selection.includeContentBodySync,
        selectedPluginDigests = selectedDigests,
    )
    return ResolvedShuYueImportSelection(
        books = books,
        chapters = chapters,
        progress = progress,
        pluginInstallations = plugins,
        includeReaderSettings = selection.includeReaderSettings,
        includeContentBodySync = selection.includeContentBodySync,
        ledgerMutation = staged.ledgerMutation.copy(resultFingerprintSha256 = selectionFingerprint),
    )
}

internal fun ShuYuePreparedImport.secretWriteBatch(
    consent: ShuYueSecretImportConsent,
): ShuYueSecretWriteBatch {
    verifyConsent(consent)
    val credentials = staged.session.secretMaterial.credentials
        .filter { it.sourceId in consent.credentialSourceIds }
        .map {
            ShuYueSecretCredential(
                sourceId = it.sourceId,
                username = it.username,
                password = it.password,
                updatedAtEpochMillis = it.updatedAt,
            )
        }
    val cookies = staged.session.secretMaterial.cookies
        .filter { it.sourceId in consent.cookieSourceIds }
        .map {
            ShuYueSecretCookie(
                sourceId = it.sourceId,
                name = it.name,
                value = it.value,
                domain = it.domain,
                path = it.path,
                expiresAtEpochMillis = it.expiresAt,
            )
        }
    return ShuYueSecretWriteBatch(credentials, cookies)
}

private fun selectionFingerprint(
    baseResultFingerprint: String,
    selectedBookIds: Set<String>,
    includeProgress: Boolean,
    includeReaderSettings: Boolean,
    includeContentBodySync: Boolean,
    selectedPluginDigests: Set<String>,
): String {
    val canonical = buildString {
        appendLengthPrefixed("shuyue-import-selection-v2")
        appendLengthPrefixed(baseResultFingerprint)
        append(if (includeProgress) '1' else '0')
        append(if (includeReaderSettings) '1' else '0')
        append(if (includeContentBodySync) '1' else '0')
        selectedBookIds.sorted().forEach { appendLengthPrefixed(it) }
        append('|')
        selectedPluginDigests.sorted().forEach { appendLengthPrefixed(it) }
    }
    return Sha256.hex(canonical.encodeToByteArray())
}

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
    append('|')
}

private fun requireSafeSelectionValue(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_SECRET_FIELD_CHARS &&
        value.none(Char::isISOControl)) { "$label is invalid" }
}

private fun isWellFormedSecret(value: String): Boolean {
    if (value.length > MAX_SECRET_FIELD_CHARS) return false
    for (index in value.indices) {
        val character = value[index]
        if (character == '\u0000' || (character.isISOControl() && character !in "\n\r\t")) return false
        if (character.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) {
            if (index == 0 || !value[index - 1].isHighSurrogate()) return false
        }
    }
    return true
}

private fun isWellFormedCookieValue(value: String): Boolean =
    isWellFormedSecret(value) && value.none { it.isISOControl() || it == ';' }

private fun requireSafeCookieDomain(value: String) {
    require(value.length in 1..253 && value.none { it.isISOControl() || it.isWhitespace() } &&
        COOKIE_DOMAIN_PATTERN.matches(value)) { "Migrated cookie domain is invalid" }
}

private fun requireSafeCookiePath(value: String) {
    require(value.startsWith('/') && value.length <= 2_048 &&
        value.none { it.isISOControl() || it.isWhitespace() || it == ';' || it == '\\' }) {
        "Migrated cookie path is invalid"
    }
}

private class ImmutableListSnapshot<E>(elements: Iterable<E>) : AbstractList<E>() {
    private val values: List<E> = elements.toList()
    override val size: Int get() = values.size
    override fun get(index: Int): E = values[index]
}

private class ImmutableSetSnapshot<E>(elements: Iterable<E>) : AbstractSet<E>() {
    private val values: List<E> = elements.distinct()
    override val size: Int get() = values.size
    override fun contains(element: E): Boolean = element in values
    override fun iterator(): Iterator<E> = object : Iterator<E> {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun next(): E {
            if (!hasNext()) throw NoSuchElementException()
            return values[index++]
        }
    }
}

private fun <E> Iterable<E>.immutableListSnapshot(): List<E> = ImmutableListSnapshot(this)
private fun <E> Iterable<E>.immutableSetSnapshot(): Set<E> = ImmutableSetSnapshot(this)

private const val MAX_SELECTION_ITEMS: Int = 100_000
private const val MAX_SECRET_FIELD_CHARS: Int = 64 * 1024
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val COOKIE_DOMAIN_PATTERN = Regex("\\.?[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
private val COOKIE_NAME_SEPARATORS = setOf('(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}')
