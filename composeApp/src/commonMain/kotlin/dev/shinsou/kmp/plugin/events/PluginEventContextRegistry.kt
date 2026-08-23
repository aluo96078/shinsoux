package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Host-issued, process-local context references for ACTIVE_CONTEXT refreshes.
 *
 * A reference is deliberately just an opaque handle.  The publication/unit being displayed is
 * never encoded in it or returned by the registry.  Admission is still bound to the complete
 * runtime key (runtime instance, generation and artifact digest) plus the exact source key.
 */
public class PluginEventContextRegistry(
    private val clock: PluginEventClock = SystemPluginEventClock,
    private val ttlMillis: Long = DEFAULT_PLUGIN_EVENT_CONTEXT_TTL_MILLIS,
    private val handleFactory: (() -> String)? = null,
) : PluginEventContextReferenceValidator, AutoCloseable {
    public data class VisibleContext(
        val publicationId: String,
        val unitId: String? = null,
    ) {
        init {
            require(publicationId.isNotBlank())
            require(publicationId.length <= 512)
            require(unitId == null || unitId.isNotBlank() && unitId.length <= 512)
        }
    }

    private data class Entry(
        val handle: String,
        val scope: BoundPluginScope,
        val expiresAtMillis: Long,
        val visibleContext: VisibleContext,
    )

    private val lock = SynchronousLock()
    private val entries = linkedMapOf<String, Entry>()
    private val activeByRuntime = linkedMapOf<String, String>()
    private var closed: Boolean = false

    init {
        require(ttlMillis > 0) { "Context handle TTL must be positive" }
    }

    /** Issues a fresh handle and replaces any previous active handle for this exact runtime. */
    @Deprecated("Production callers must bind a visible publication/unit")
    public fun issue(scope: BoundPluginScope): String =
        issue(scope, VisibleContext("test-only"))

    public fun issue(scope: BoundPluginScope, visibleContext: VisibleContext): String = lock.withLock {
        check(!closed) { "Context registry is closed" }
        pruneLocked(clock.nowMillis())
        // Runtime generations are part of runtimeKey, so replacing only that key would leave a
        // handle from an older generation usable.  Revoke prior generations for the same exact
        // runtime binding while keeping a different artifact/source with a recycled instance id
        // isolated.
        revokePriorGenerationsLocked(scope)
        val handle = newHandleLocked()
        val now = clock.nowMillis()
        entries[handle] = Entry(
            handle = handle,
            scope = scope,
            expiresAtMillis = now + ttlMillis,
            visibleContext = visibleContext,
        )
        activeByRuntime[scope.runtimeKey] = handle
        handle
    }

    /** Explicitly revokes a host-issued handle; repeated revocation is harmless. */
    public fun revoke(handle: String): Unit = lock.withLock {
        revokeLocked(handle)
    }

    /** Removes every handle for one exact runtime generation. */
    public fun clearRuntime(scope: BoundPluginScope): Unit = lock.withLock {
        revokeRuntimeLocked(scope.runtimeKey)
    }

    /** Removes handles for all generations of an invalidated artifact. */
    public fun invalidateArtifact(identity: PluginArtifactIdentity): Unit = lock.withLock {
        entries.values.filter { it.scope.artifactIdentity == identity }
            .map(Entry::handle)
            .forEach(::revokeLocked)
    }

    /** Returns the currently active, unexpired handle for an exact runtime scope. */
    public fun current(scope: BoundPluginScope): String? = lock.withLock {
        pruneLocked(clock.nowMillis())
        val handle = activeByRuntime[scope.runtimeKey] ?: return@withLock null
        val entry = entries[handle] ?: return@withLock null
        if (!sameScope(entry.scope, scope)) {
            revokeLocked(handle)
            return@withLock null
        }
        handle
    }

    public fun resolve(scope: BoundPluginScope, handle: String): VisibleContext? = lock.withLock {
        pruneLocked(clock.nowMillis())
        val entry = entries[handle] ?: return@withLock null
        entry.visibleContext.takeIf {
            sameScope(entry.scope, scope) && activeByRuntime[scope.runtimeKey] == handle
        }
    }

    /** Host helper used around one visible V2 detail/unit/content invocation. */
    public suspend fun <T> withInvocation(
        scope: BoundPluginScope,
        visibleContext: VisibleContext,
        block: suspend (String) -> T,
    ): T {
        val handle = issue(scope, visibleContext)
        return try {
            block(handle)
        } finally {
            revoke(handle)
        }
    }

    @Deprecated("Production callers must bind a visible publication/unit")
    public suspend fun <T> withInvocation(scope: BoundPluginScope, block: suspend (String) -> T): T =
        withInvocation(scope, VisibleContext("test-only"), block)

    override fun accepts(scope: BoundPluginScope, contextRef: String): Boolean = lock.withLock {
        pruneLocked(clock.nowMillis())
        val entry = entries[contextRef] ?: return@withLock false
        sameScope(entry.scope, scope) && activeByRuntime[scope.runtimeKey] == contextRef
    }

    override fun close(): Unit = lock.withLock {
        closed = true
        entries.clear()
        activeByRuntime.clear()
    }

    private fun sameScope(expected: BoundPluginScope, actual: BoundPluginScope): Boolean =
        expected.runtimeKey == actual.runtimeKey &&
            expected.artifactIdentity == actual.artifactIdentity &&
            expected.sourceKey == actual.sourceKey &&
            expected.runtimeInstanceId == actual.runtimeInstanceId &&
            expected.runtimeGeneration == actual.runtimeGeneration

    private fun newHandleLocked(): String {
        val supplied = handleFactory?.invoke()
        if (supplied != null) {
            require(isSafeAsciiIdentifier(supplied, MAX_CONTEXT_HANDLE_BYTES)) {
                "Context handle factory returned an invalid handle"
            }
            require(supplied !in entries) { "Context handle factory returned a duplicate handle" }
            return supplied
        }
        return randomContextHandle()
    }

    private fun revokeRuntimeLocked(runtimeKey: String) {
        entries.values.filter { it.scope.runtimeKey == runtimeKey }
            .map(Entry::handle)
            .forEach(::revokeLocked)
        activeByRuntime.remove(runtimeKey)
    }

    private fun revokePriorGenerationsLocked(scope: BoundPluginScope) {
        entries.values.filter { entry ->
            entry.scope.runtimeInstanceId == scope.runtimeInstanceId &&
                entry.scope.artifactIdentity == scope.artifactIdentity &&
                entry.scope.sourceKey == scope.sourceKey &&
                entry.scope.runtimeGeneration <= scope.runtimeGeneration
        }.map(Entry::handle).forEach(::revokeLocked)
    }

    private fun revokeLocked(handle: String) {
        val entry = entries.remove(handle) ?: return
        if (activeByRuntime[entry.scope.runtimeKey] == handle) {
            activeByRuntime.remove(entry.scope.runtimeKey)
        }
    }

    private fun pruneLocked(now: Long) {
        entries.values.filter { now >= it.expiresAtMillis }
            .map(Entry::handle)
            .forEach(::revokeLocked)
    }

    public companion object {
        public const val DEFAULT_PLUGIN_EVENT_CONTEXT_TTL_MILLIS: Long = 5_000
        private const val MAX_CONTEXT_HANDLE_BYTES: Int = 64

        @OptIn(ExperimentalUuidApi::class)
        private fun randomContextHandle(): String = "ctx-${Uuid.random()}"
    }
}
