package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PluginSystemEventDispatcherTest {
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun acceptedReceiptIsImmediateAndEquivalentPendingRequestDeduplicates() = runTest {
        val clock = FakeClock()
        val fixture = fixture(
            clock = clock,
            permission = PluginHostPermission.REQUEST_LOGIN_UI,
            sourceCapability = "LOGIN",
            lane = PluginSystemEventLane.MODAL,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        var calls = 0
        fixture.registry.register(
            TypedPluginSystemEventHandler<LoginRequestV1>(
                name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
                kind = PluginSystemEventKind.COMMAND,
                payloadVersion = 1,
                lane = PluginSystemEventLane.MODAL,
                requiredPermission = PluginHostPermission.REQUEST_LOGIN_UI,
                requiredSourceCapability = "LOGIN",
                decode = { fixture.codec.decodePayload(it, LoginRequestV1.serializer()) },
                execute = { _, _ -> calls++; PluginEventOutcome.Succeeded },
            ),
        )
        val bytes = fixture.codec.encodePayload(
            kind = PluginSystemEventKind.COMMAND,
            name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
            id = "login-1",
            payload = LoginRequestV1(reasonCode = "AUTH_REQUIRED"),
            serializer = LoginRequestV1.serializer(),
        )
        val accepted = fixture.gateway.submit(fixture.scope, bytes)
        assertEquals(PluginEventDisposition.ACCEPTED, accepted.disposition)
        assertTrue(accepted.operationRef != null)
        assertEquals(0, calls)
        val duplicate = fixture.gateway.submit(fixture.scope, bytes)
        assertEquals(PluginEventDisposition.DEDUPLICATED, duplicate.disposition)
        runCurrent()
        assertEquals(1, calls)
        fixture.gateway.close()
    }

    @Test
    fun admittedLoginStillRunsWhenInteractionContextEndsBeforeWorkerStarts() = runTest {
        val fixture = fixture(
            permission = PluginHostPermission.REQUEST_LOGIN_UI,
            sourceCapability = "LOGIN",
            lane = PluginSystemEventLane.MODAL,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        var calls = 0
        fixture.registry.register(
            TypedPluginSystemEventHandler<LoginRequestV1>(
                name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
                kind = PluginSystemEventKind.COMMAND,
                payloadVersion = 1,
                lane = PluginSystemEventLane.MODAL,
                requiredPermission = PluginHostPermission.REQUEST_LOGIN_UI,
                requiredSourceCapability = "LOGIN",
                decode = { fixture.codec.decodePayload(it, LoginRequestV1.serializer()) },
                execute = { _, _ -> calls++; PluginEventOutcome.Succeeded },
            ),
        )
        val bytes = fixture.codec.encodePayload(
            kind = PluginSystemEventKind.COMMAND,
            name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
            id = "login-delayed",
            payload = LoginRequestV1(reasonCode = "AUTH_REQUIRED"),
            serializer = LoginRequestV1.serializer(),
        )

        assertEquals(PluginEventDisposition.ACCEPTED, fixture.gateway.submit(fixture.scope, bytes).disposition)
        // Model the UI callback returning before the asynchronous modal worker gets scheduled.
        fixture.authorizer.setUserInteractionContext(fixture.scope, false)
        assertFalse(
            fixture.authorizer.authorize(
                fixture.scope,
                PluginHostPermission.REQUEST_LOGIN_UI,
                "LOGIN",
            ).allowed,
        )

        runCurrent()
        assertEquals(1, calls)
        fixture.gateway.close()
    }

    @Test
    fun unknownEventAndWrongDigestFailClosedWithoutHandlerCall() = runTest {
        val fixture = fixture(
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(fixture)
        val unknown = fixture.codec.encode(
            PluginSystemEventEnvelope(
                protocol = PluginSystemEventProtocol.NAME,
                version = 1,
                kind = PluginSystemEventKind.EVENT,
                name = "future.host.command",
                id = "future-1",
                payloadVersion = 1,
                payload = kotlinx.serialization.json.buildJsonObject { },
            ),
        )
        assertEquals(PluginEventDisposition.UNSUPPORTED, fixture.gateway.submit(fixture.scope, unknown).disposition)

        val changedArtifact = PluginArtifactIdentity("pkg", "1.0.0", 1, digest.dropLast(1) + "e")
        val changedScope = BoundPluginScopeFactory(FakeClock()).bind(
            changedArtifact,
            fixture.source,
            "runtime-changed",
            1,
        )
        val message = diagnosticBytes(fixture.codec, "message-1", "network.failure")
        assertEquals(PluginEventDisposition.DENIED, fixture.gateway.submit(changedScope, message).disposition)
        fixture.gateway.close()
    }

    @Test
    fun tokenBucketCannotBeBypassedByChangingMessageIds() = runTest {
        val limits = PluginSystemEventLimits(tokenBurst = 2, tokenPerMinute = 1)
        val fixture = fixture(
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = limits,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(fixture)
        repeat(2) { index ->
            assertEquals(
                PluginEventDisposition.ACCEPTED,
                fixture.gateway.submit(
                    fixture.scope,
                    diagnosticBytes(fixture.codec, "message-$index", "code-$index"),
                ).disposition,
            )
        }
        assertEquals(
            PluginEventDisposition.THROTTLED,
            fixture.gateway.submit(fixture.scope, diagnosticBytes(fixture.codec, "message-3", "code-3")).disposition,
        )
        fixture.gateway.close()
    }

    @Test
    fun refreshConflatesDirtyRerunAndDiagnosticsReportAggregationCount() = runTest {
        val clock = FakeClock()
        val reports = mutableListOf<PluginEventExecutionReport>()
        val fixture = fixture(
            clock = clock,
            permission = PluginHostPermission.REQUEST_SOURCE_REFRESH,
            lane = PluginSystemEventLane.REFRESH,
            observer = PluginEventObserver { reports += it },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        var refreshCalls = 0
        fixture.registry.register(
            TypedPluginSystemEventHandler<SourceRefreshRequestV1>(
                name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                kind = PluginSystemEventKind.COMMAND,
                payloadVersion = 1,
                lane = PluginSystemEventLane.REFRESH,
                requiredPermission = PluginHostPermission.REQUEST_SOURCE_REFRESH,
                decode = { fixture.codec.decodePayload(it, SourceRefreshRequestV1.serializer()) },
                execute = { _, _ -> refreshCalls++; PluginEventOutcome.Succeeded },
            ),
        )
        val refreshBytes = fixture.codec.encodePayload(
            kind = PluginSystemEventKind.COMMAND,
            name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
            id = "refresh-1",
            payload = SourceRefreshRequestV1(),
            serializer = SourceRefreshRequestV1.serializer(),
        )
        assertEquals(PluginEventDisposition.ACCEPTED, fixture.gateway.submit(fixture.scope, refreshBytes).disposition)
        assertEquals(PluginEventDisposition.DEDUPLICATED, fixture.gateway.submit(fixture.scope, refreshBytes).disposition)
        advanceUntilIdle()
        assertEquals(2, refreshCalls)

        fixture.gateway.close()
    }

    @Test
    fun diagnosticsAggregateDuplicateOccurrencesWithinWindow() = runTest {
        val reports = mutableListOf<PluginEventExecutionReport>()
        val fixture = fixture(
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            observer = PluginEventObserver { reports += it },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(fixture)
        val first = diagnosticBytes(fixture.codec, "diagnostic-1", "network.failure")
        assertEquals(PluginEventDisposition.ACCEPTED, fixture.gateway.submit(fixture.scope, first).disposition)
        assertEquals(
            PluginEventDisposition.DEDUPLICATED,
            fixture.gateway.submit(fixture.scope, diagnosticBytes(fixture.codec, "diagnostic-2", "network.failure")).disposition,
        )
        advanceUntilIdle()
        assertEquals(1, reports.size)
        assertEquals(2, reports.single().occurrenceCount)
        fixture.gateway.close()
    }

    @Test
    fun diagnosticAggregationExpiresAndDoesNotCarryIntoTheNextWindow() = runTest {
        val clock = FakeClock()
        val reports = mutableListOf<PluginEventExecutionReport>()
        val fixture = fixture(
            clock = clock,
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = PluginSystemEventLimits(diagnosticAggregationMillis = 10),
            observer = PluginEventObserver { reports += it },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(fixture)

        assertEquals(
            PluginEventDisposition.ACCEPTED,
            fixture.gateway.submit(fixture.scope, diagnosticBytes(fixture.codec, "first", "network.failure")).disposition,
        )
        advanceUntilIdle()
        assertEquals(1, reports.single().occurrenceCount)

        clock.now = 11
        assertEquals(
            PluginEventDisposition.ACCEPTED,
            fixture.gateway.submit(fixture.scope, diagnosticBytes(fixture.codec, "second", "network.failure")).disposition,
        )
        advanceUntilIdle()
        assertEquals(2, reports.size)
        assertEquals(1, reports[1].occurrenceCount)
        fixture.gateway.close()
    }

    @Test
    fun runtimeCloseCancelsPendingAndNewGenerationExpiresOldScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeClock()
        val fixture = fixture(
            clock = clock,
            permission = PluginHostPermission.REQUEST_LOGIN_UI,
            sourceCapability = "LOGIN",
            lane = PluginSystemEventLane.MODAL,
            dispatcher = dispatcher,
        )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.registry.register(
            TypedPluginSystemEventHandler<LoginRequestV1>(
                name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
                kind = PluginSystemEventKind.COMMAND,
                payloadVersion = 1,
                lane = PluginSystemEventLane.MODAL,
                requiredPermission = PluginHostPermission.REQUEST_LOGIN_UI,
                requiredSourceCapability = "LOGIN",
                decode = { fixture.codec.decodePayload(it, LoginRequestV1.serializer()) },
                execute = { _, _ ->
                    started.complete(Unit)
                    release.await()
                    PluginEventOutcome.Succeeded
                },
            ),
        )
        val bytes = fixture.codec.encodePayload(
            kind = PluginSystemEventKind.COMMAND,
            name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
            id = "login-1",
            payload = LoginRequestV1(),
            serializer = LoginRequestV1.serializer(),
        )
        assertEquals(PluginEventDisposition.ACCEPTED, fixture.gateway.submit(fixture.scope, bytes).disposition)
        runCurrent()
        assertTrue(started.isCompleted)
        fixture.gateway.closeRuntime(fixture.scope)
        assertEquals(0, fixture.gateway.pendingCount)
        assertEquals(PluginEventDisposition.RUNTIME_CLOSED, fixture.gateway.submit(fixture.scope, bytes).disposition)
        release.complete(Unit)
        advanceUntilIdle()
        fixture.gateway.close()
    }

    @Test
    fun ttlExpiresBeforeHandlerAndNewGenerationInvalidatesOldAdmissions() = runTest {
        val clock = FakeClock()
        val fixture = fixture(
            clock = clock,
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = PluginSystemEventLimits(ttlMillis = 10),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val reports = mutableListOf<PluginEventExecutionReport>()
        fixture.gateway.close()

        val f = fixture(
            clock = clock,
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = PluginSystemEventLimits(ttlMillis = 10),
            observer = PluginEventObserver { reports += it },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        var calls = 0
        f.registry.register(
            TypedPluginSystemEventHandler<DiagnosticMessageV1>(
                name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
                kind = PluginSystemEventKind.EVENT,
                payloadVersion = 1,
                lane = PluginSystemEventLane.TRANSIENT,
                requiredPermission = PluginHostPermission.REPORT_DIAGNOSTIC,
                decode = { f.codec.decodePayload(it, DiagnosticMessageV1.serializer()) },
                execute = { _, _ -> calls++; PluginEventOutcome.Succeeded },
            ),
        )
        val bytes = diagnosticBytes(f.codec, "diagnostic-1", "network.failure")
        assertEquals(PluginEventDisposition.ACCEPTED, f.gateway.submit(f.scope, bytes).disposition)
        clock.now = 11
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(PluginEventExecutionStatus.EXPIRED, reports.single().status)

        val nextArtifact = PluginArtifactIdentity("pkg", "2.0.0", 2, digest.dropLast(1) + "e")
        val nextScope = BoundPluginScopeFactory(clock).bind(nextArtifact, f.source, "runtime", 2)
        f.authorizer.grant(
            PluginEventGrantKey(nextArtifact, f.source),
            setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
        )
        f.authorizer.setRuntimeStatus(nextScope, PluginEventRuntimeStatus())
        assertEquals(PluginEventDisposition.ACCEPTED, f.gateway.submit(nextScope, diagnosticBytes(f.codec, "diagnostic-2", "next")).disposition)
        assertEquals(PluginEventDisposition.RUNTIME_CLOSED, f.gateway.submit(f.scope, bytes).disposition)
        f.gateway.close()
    }

    @Test
    fun closingLatestGenerationDoesNotReopenAnOlderScope() = runTest {
        val clock = FakeClock()
        val f = fixture(
            clock = clock,
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(f)
        val newer = BoundPluginScopeFactory(clock).bind(
            f.scope.artifactIdentity,
            f.source,
            f.scope.runtimeInstanceId,
            2,
        )
        f.authorizer.setRuntimeStatus(newer, PluginEventRuntimeStatus())

        assertEquals(
            PluginEventDisposition.ACCEPTED,
            f.gateway.submit(newer, diagnosticBytes(f.codec, "newer", "newer.code")).disposition,
        )
        advanceUntilIdle()
        f.gateway.closeRuntime(newer)

        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(f.scope, diagnosticBytes(f.codec, "old-replay", "old.code")).disposition,
        )
        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(newer, diagnosticBytes(f.codec, "closed-replay", "closed.code")).disposition,
        )
        f.gateway.close()
    }

    @Test
    fun repeatedReloadsShareOneLogicalIdentityAndRejectEveryStaleGeneration() = runTest {
        val clock = FakeClock()
        val f = fixture(
            clock = clock,
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = PluginSystemEventLimits(maxTrackedRuntimes = 1),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(f)
        val factory = BoundPluginScopeFactory(clock)
        val logicalRuntimeId = stablePluginRuntimeInstanceId(f.scope.artifactIdentity, f.source)
        val stale = factory.bind(f.scope.artifactIdentity, f.source, logicalRuntimeId, 1)
        var current = stale

        // Exceed the production identity-table bound while retaining just one logical identity.
        repeat(PluginSystemEventLimits().maxTrackedRuntimes + 2) { reload ->
            val next = factory.bind(
                f.scope.artifactIdentity,
                f.source,
                logicalRuntimeId,
                reload.toLong() + 2,
            )
            f.authorizer.setRuntimeStatus(next, PluginEventRuntimeStatus())
            assertEquals(
                PluginEventDisposition.ACCEPTED,
                f.gateway.submit(
                    next,
                    diagnosticBytes(f.codec, "reload-${reload + 2}", "reload.current"),
                ).disposition,
            )
            advanceUntilIdle()
            current = next
        }

        assertEquals(
            PluginEventDisposition.ACCEPTED,
            f.gateway.submit(current, diagnosticBytes(f.codec, "current-final", "reload.final")).disposition,
        )
        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(stale, diagnosticBytes(f.codec, "stale-final", "reload.stale")).disposition,
        )
        f.gateway.close()
    }

    @Test
    fun runtimeIdentityChurnStopsAtBoundWithoutEvictingClosedTombstones() = runTest {
        val limits = PluginSystemEventLimits(maxTrackedRuntimes = 2)
        val f = fixture(
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = limits,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(f)
        val second = BoundPluginScopeFactory().bind(f.scope.artifactIdentity, f.source, "runtime-2", 1)
        val overflow = BoundPluginScopeFactory().bind(f.scope.artifactIdentity, f.source, "runtime-3", 1)
        f.authorizer.setRuntimeStatus(second, PluginEventRuntimeStatus())
        f.authorizer.setRuntimeStatus(overflow, PluginEventRuntimeStatus())

        f.gateway.closeRuntime(f.scope)
        assertEquals(
            PluginEventDisposition.ACCEPTED,
            f.gateway.submit(second, diagnosticBytes(f.codec, "second", "second.code")).disposition,
        )
        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(overflow, diagnosticBytes(f.codec, "overflow", "overflow.code")).disposition,
        )
        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(f.scope, diagnosticBytes(f.codec, "closed", "closed.code")).disposition,
        )
        f.gateway.close()
    }

    @Test
    fun rateLimitBucketCapacityRejectsNewKeysAndReclaimsFullyRefilledBuckets() = runTest {
        val clock = FakeClock()
        val limits = PluginSystemEventLimits(
            tokenBurst = 1,
            tokenPerMinute = 1,
            maxRateLimitBuckets = 2,
        )
        val f = fixture(
            clock = clock,
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = limits,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(f)
        val second = BoundPluginScopeFactory(clock).bind(f.scope.artifactIdentity, f.source, "runtime-2", 1)
        f.authorizer.setRuntimeStatus(second, PluginEventRuntimeStatus())

        assertEquals(
            PluginEventDisposition.ACCEPTED,
            f.gateway.submit(f.scope, diagnosticBytes(f.codec, "first", "first.code")).disposition,
        )
        assertEquals(
            PluginEventDisposition.BUSY,
            f.gateway.submit(second, diagnosticBytes(f.codec, "second", "second.code")).disposition,
        )

        clock.now = 60_000
        assertEquals(
            PluginEventDisposition.ACCEPTED,
            f.gateway.submit(second, diagnosticBytes(f.codec, "after-refill", "second.code")).disposition,
        )
        f.gateway.close()
    }

    @Test
    fun sameRuntimeGenerationCannotBeReboundToAnotherArtifact() = runTest {
        val f = fixture(
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(f)
        assertEquals(
            PluginEventDisposition.ACCEPTED,
            f.gateway.submit(f.scope, diagnosticBytes(f.codec, "original", "original.code")).disposition,
        )

        val changedArtifact = f.scope.artifactIdentity.copy(
            version = "2.0.0",
            versionCode = 2,
            sha256 = digest.dropLast(1) + "e",
        )
        val rebound = BoundPluginScopeFactory().bind(
            changedArtifact,
            f.source,
            f.scope.runtimeInstanceId,
            f.scope.runtimeGeneration,
        )
        f.authorizer.grant(
            PluginEventGrantKey(changedArtifact, f.source),
            setOf(PluginHostPermission.REPORT_DIAGNOSTIC),
        )
        f.authorizer.setRuntimeStatus(rebound, PluginEventRuntimeStatus())

        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(rebound, diagnosticBytes(f.codec, "rebound", "rebound.code")).disposition,
        )
        f.gateway.close()
    }

    @Test
    fun artifactTombstoneOverflowFailsTheGatewayClosed() = runTest {
        val f = fixture(
            permission = PluginHostPermission.REPORT_DIAGNOSTIC,
            lane = PluginSystemEventLane.TRANSIENT,
            limits = PluginSystemEventLimits(maxInvalidArtifacts = 1),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        registerDiagnosticHandler(f)
        val firstInvalid = f.scope.artifactIdentity.copy(
            version = "2.0.0",
            versionCode = 2,
            sha256 = digest.dropLast(1) + "e",
        )
        val secondInvalid = f.scope.artifactIdentity.copy(
            version = "3.0.0",
            versionCode = 3,
            sha256 = digest.dropLast(1) + "d",
        )

        f.gateway.invalidateArtifact(firstInvalid)
        f.gateway.invalidateArtifact(secondInvalid)

        assertEquals(
            PluginEventDisposition.RUNTIME_CLOSED,
            f.gateway.submit(f.scope, diagnosticBytes(f.codec, "after-overflow", "closed.code")).disposition,
        )
        f.gateway.close()
    }

    private fun diagnosticBytes(codec: PluginSystemEventCodec, id: String, code: String): ByteArray =
        codec.encodePayload(
            kind = PluginSystemEventKind.EVENT,
            name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
            id = id,
            payload = DiagnosticMessageV1(
                code = code,
                severity = PluginDiagnosticSeverity.WARNING,
                fallbackMessage = "A plugin reported a bounded warning",
            ),
            serializer = DiagnosticMessageV1.serializer(),
        )

    private fun registerDiagnosticHandler(fixture: Fixture) {
        fixture.registry.register(
            TypedPluginSystemEventHandler<DiagnosticMessageV1>(
                name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
                kind = PluginSystemEventKind.EVENT,
                payloadVersion = 1,
                lane = PluginSystemEventLane.TRANSIENT,
                requiredPermission = PluginHostPermission.REPORT_DIAGNOSTIC,
                decode = { fixture.codec.decodePayload(it, DiagnosticMessageV1.serializer()) },
                execute = { _, _ -> PluginEventOutcome.Succeeded },
            ),
        )
    }

    private fun fixture(
        clock: FakeClock = FakeClock(),
        permission: PluginHostPermission,
        sourceCapability: String? = null,
        lane: PluginSystemEventLane,
        limits: PluginSystemEventLimits = PluginSystemEventLimits(),
        observer: PluginEventObserver = PluginEventObserver { },
        dispatcher: TestDispatcher,
    ): Fixture {
        val source = SourceKey(packageId = "pkg", sourceId = "source")
        val artifact = PluginArtifactIdentity("pkg", "1.0.0", 1, digest)
        val scope = BoundPluginScopeFactory(clock).bind(artifact, source, "runtime", 1)
        val authorizer = MutablePluginSystemEventAuthorizer()
        authorizer.grant(PluginEventGrantKey(artifact, source), setOf(permission))
        authorizer.setRuntimeStatus(
            scope,
            PluginEventRuntimeStatus(
                lifecycle = PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED,
                hasUserInteractionContext = true,
                sourceCapabilities = sourceCapability?.let(::setOf).orEmpty(),
            ),
        )
        val codec = PluginSystemEventCodec(limits)
        val registry = PluginSystemEventHandlerRegistry()
        val gateway = PluginSystemEventGateway(
            registry = registry,
            authorizer = authorizer,
            codec = codec,
            clock = clock,
            observer = observer,
            dispatcherScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        return Fixture(source, scope, authorizer, codec, registry, gateway)
    }

    private data class Fixture(
        val source: SourceKey,
        val scope: BoundPluginScope,
        val authorizer: MutablePluginSystemEventAuthorizer,
        val codec: PluginSystemEventCodec,
        val registry: PluginSystemEventHandlerRegistry,
        val gateway: PluginSystemEventGateway,
    )

    private class FakeClock(var now: Long = 0) : PluginEventClock {
        override fun nowMillis(): Long = now
    }
}
