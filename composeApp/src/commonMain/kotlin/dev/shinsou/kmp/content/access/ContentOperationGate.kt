package dev.shinsou.kmp.content.access

import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ContentRightsEvaluator
import dev.shinsou.kmp.rights.ProtectionAuthorization
import dev.shinsou.kmp.rights.RightsAuthority
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsHint
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope

/**
 * Complete host-owned input for one protected content operation.
 *
 * The caller may describe the resource and constraints, but only [HostContentOperationGate]
 * resolves the current grant.  Keeping the authority outside this value prevents a plugin from
 * supplying its own permissive evaluator.
 */
public data class ContentAccessRequest(
    val grantReference: RightsGrantRef?,
    val scope: RightsScope,
    val context: RightsOperationContext = RightsOperationContext(),
    val hint: RightsHint? = null,
    val protectionAuthorization: ProtectionAuthorization? = null,
) {
    init {
        scope.validate()
        grantReference?.validate()
        hint?.validate()
    }
}

/** Deliberately carries no publication, provider, or secret values in its message. */
public class ContentOperationDeniedException(
    public val operation: ContentOperation,
) : IllegalStateException("Host rights policy denied $operation")

/** Central capability boundary used by display, storage, sync, export, search, TTS, and notes. */
public interface ContentOperationGate {
    public fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision

    public fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation)

    public fun <T> execute(
        request: ContentAccessRequest,
        operation: ContentOperation,
        block: () -> T,
    ): T

    public suspend fun <T> executeSuspending(
        request: ContentAccessRequest,
        operation: ContentOperation,
        block: suspend () -> T,
    ): T
}

/**
 * Production evaluator.  Every call resolves the grant again, so revocation and provider policy
 * changes take effect before the protected side effect starts.
 */
public class HostContentOperationGate(
    private val authority: RightsAuthority,
    private val nowEpochMillis: () -> Long,
) : ContentOperationGate {
    override fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision =
        ContentRightsEvaluator.decide(
            reference = request.grantReference,
            scope = request.scope,
            operation = operation,
            nowEpochMillis = nowEpochMillis(),
            authority = authority,
            authorization = request.protectionAuthorization,
            context = request.context,
            hint = request.hint,
        )

    override fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation) {
        if (decide(request, operation) != RightsDecision.ALLOW) {
            throw ContentOperationDeniedException(operation)
        }
    }

    override fun <T> execute(
        request: ContentAccessRequest,
        operation: ContentOperation,
        block: () -> T,
    ): T {
        requireAllowed(request, operation)
        return block()
    }

    override suspend fun <T> executeSuspending(
        request: ContentAccessRequest,
        operation: ContentOperation,
        block: suspend () -> T,
    ): T {
        requireAllowed(request, operation)
        return block()
    }
}

/**
 * Named production entry points for non-specialized protected side effects. Search, TTS, and
 * annotation use their richer services; display/storage/sync/export/copy/print use this facade so
 * call sites cannot accidentally pass an arbitrary operation or omit the operation context.
 */
public class RightsEnforcedContentOperations(
    private val gate: ContentOperationGate,
) {
    public fun <T> display(
        request: ContentAccessRequest,
        textCharacters: Long? = null,
        block: () -> T,
    ): T = gate.execute(request.withTextCharacters(textCharacters), ContentOperation.DISPLAY, block)

    public suspend fun <T> displaySuspending(
        request: ContentAccessRequest,
        textCharacters: Long? = null,
        block: suspend () -> T,
    ): T = gate.executeSuspending(request.withTextCharacters(textCharacters), ContentOperation.DISPLAY, block)

    public fun <T> offlineStore(
        request: ContentAccessRequest,
        byteCount: Long,
        block: () -> T,
    ): T = gate.execute(request.withOfflineBytes(byteCount), ContentOperation.OFFLINE_STORE, block)

    public suspend fun <T> offlineStoreSuspending(
        request: ContentAccessRequest,
        byteCount: Long,
        block: suspend () -> T,
    ): T = gate.executeSuspending(request.withOfflineBytes(byteCount), ContentOperation.OFFLINE_STORE, block)

    public suspend fun <T> syncBlob(
        request: ContentAccessRequest,
        block: suspend () -> T,
    ): T = gate.executeSuspending(request, ContentOperation.SYNC_BLOB, block)

    public suspend fun <T> export(
        request: ContentAccessRequest,
        textCharacters: Long? = null,
        block: suspend () -> T,
    ): T = gate.executeSuspending(request.withTextCharacters(textCharacters), ContentOperation.EXPORT, block)

    public fun <T> copy(
        request: ContentAccessRequest,
        textCharacters: Long,
        block: () -> T,
    ): T = gate.execute(request.withTextCharacters(textCharacters), ContentOperation.COPY, block)

    public suspend fun <T> print(
        request: ContentAccessRequest,
        textCharacters: Long? = null,
        block: suspend () -> T,
    ): T = gate.executeSuspending(request.withTextCharacters(textCharacters), ContentOperation.PRINT, block)
}

private fun ContentAccessRequest.withTextCharacters(value: Long?): ContentAccessRequest = if (value == null) {
    this
} else {
    copy(
        context = RightsOperationContext(
            offlineBytes = context.offlineBytes,
            textCharacters = value,
            watermarkApplied = context.watermarkApplied,
        ),
    )
}

private fun ContentAccessRequest.withOfflineBytes(value: Long): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = value,
        textCharacters = context.textCharacters,
        watermarkApplied = context.watermarkApplied,
    ),
)
