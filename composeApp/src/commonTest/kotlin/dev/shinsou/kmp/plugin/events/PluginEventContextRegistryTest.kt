package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PluginEventContextRegistryTest {
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun handleIsHostIssuedOpaqueExactAndExpires() {
        val clock = FakeClock()
        var ordinal = 0
        val registry = PluginEventContextRegistry(
            clock = clock,
            ttlMillis = 10,
            handleFactory = { "ctx-test-${++ordinal}" },
        )
        val source = SourceKey(packageId = "pkg", sourceId = "opaque")
        val scope = scope(source, "runtime-a", 1)
        val otherRuntime = scope(source, "runtime-b", 1)
        val visible = PluginEventContextRegistry.VisibleContext("publication", "unit")
        val handle = registry.issue(scope, visible)

        assertTrue(handle.startsWith("ctx-test-"))
        assertTrue(registry.accepts(scope, handle))
        assertFalse(registry.accepts(otherRuntime, handle))
        assertFalse(registry.accepts(scope, "ctx-test-999"))
        assertEquals(handle, registry.current(scope))
        assertEquals(visible, registry.resolve(scope, handle))
        assertEquals(null, registry.resolve(otherRuntime, handle))

        clock.now = 10
        assertFalse(registry.accepts(scope, handle))
        assertEquals(null, registry.current(scope))
        assertEquals(null, registry.resolve(scope, handle))
    }

    @Test
    fun replacementAndRuntimeGenerationInvalidatePreviousHandle() {
        var ordinal = 0
        val registry = PluginEventContextRegistry(handleFactory = { "ctx-test-${++ordinal}" })
        val source = SourceKey(packageId = "pkg", sourceId = "opaque")
        val firstScope = scope(source, "runtime", 1)
        val replacementScope = scope(source, "runtime", 2)
        val first = registry.issue(firstScope)
        val replacement = registry.issue(replacementScope)

        assertNotEquals(first, replacement)
        assertFalse(registry.accepts(firstScope, first))
        assertTrue(registry.accepts(replacementScope, replacement))
        assertFalse(registry.accepts(firstScope, replacement))

        registry.clearRuntime(replacementScope)
        assertFalse(registry.accepts(replacementScope, replacement))
    }

    @Test
    fun gatewayUsesRegistryAndRejectsGuessedOrCrossRuntimeHandle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeClock()
        var ordinal = 0
        val registry = PluginEventContextRegistry(
            clock = clock,
            handleFactory = { "ctx-test-${++ordinal}" },
        )
        val source = SourceKey(packageId = "pkg", sourceId = "opaque")
        val scope = scope(source, "runtime", 1)
        val otherScope = scope(source, "other-runtime", 1)
        val artifact = scope.artifactIdentity
        val authorizer = MutablePluginSystemEventAuthorizer()
        authorizer.grant(
            PluginEventGrantKey(artifact, source),
            setOf(PluginHostPermission.REQUEST_SOURCE_REFRESH),
        )
        authorizer.setRuntimeStatus(scope, PluginEventRuntimeStatus(sourceCapabilities = setOf("CATALOGUE")))
        authorizer.setRuntimeStatus(otherScope, PluginEventRuntimeStatus(sourceCapabilities = setOf("CATALOGUE")))
        val codec = PluginSystemEventCodec()
        val registryOfHandlers = PluginSystemEventHandlerRegistry().also { handlers ->
            handlers.register(
                TypedPluginSystemEventHandler<SourceRefreshRequestV1>(
                    name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                    kind = PluginSystemEventKind.COMMAND,
                    payloadVersion = 1,
                    lane = PluginSystemEventLane.REFRESH,
                    requiredPermission = PluginHostPermission.REQUEST_SOURCE_REFRESH,
                    decode = { codec.decodePayload(it, SourceRefreshRequestV1.serializer()) },
                    execute = { _, _ -> PluginEventOutcome.Succeeded },
                ),
            )
        }
        val gateway = PluginSystemEventGateway(
            registry = registryOfHandlers,
            authorizer = authorizer,
            codec = codec,
            clock = clock,
            dispatcherScope = CoroutineScope(SupervisorJob() + dispatcher),
            contextRegistry = registry,
        )
        try {
            val handle = registry.issue(scope)
            fun bytes(id: String, contextRef: String) = codec.encodePayload(
                kind = PluginSystemEventKind.COMMAND,
                name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                id = id,
                contextRef = contextRef,
                payload = SourceRefreshRequestV1(SourceRefreshScope.ACTIVE_CONTEXT),
                serializer = SourceRefreshRequestV1.serializer(),
            )
            assertEquals(
                PluginEventDisposition.ACCEPTED,
                gateway.submit(scope, bytes("valid", handle)).disposition,
            )
            assertEquals(
                PluginEventDisposition.DENIED,
                gateway.submit(scope, bytes("guessed", "ctx-test-999")).disposition,
            )
            assertEquals(
                PluginEventDisposition.DENIED,
                gateway.submit(otherScope, bytes("cross-runtime", handle)).disposition,
            )
        } finally {
            gateway.close()
        }
    }

    private fun scope(source: SourceKey, runtimeId: String, generation: Long): BoundPluginScope =
        BoundPluginScopeFactory().bind(
            artifactIdentity = PluginArtifactIdentity(source.packageId, "1.0.0", 1, digest),
            sourceKey = source,
            runtimeInstanceId = runtimeId,
            runtimeGeneration = generation,
        )

    private class FakeClock(var now: Long = 0) : PluginEventClock {
        override fun nowMillis(): Long = now
    }
}
