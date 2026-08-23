package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSystemEventHandlersTest {
    @Test
    fun v1HandlersUseExactBoundSourceContextAndEventIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = SourceKey(2, "reviewed.package", "opaque/source")
        val artifact = PluginArtifactIdentity(
            packageId = source.packageId,
            version = "2.0.0",
            versionCode = 7,
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        val scope = BoundPluginScopeFactory().bind(
            artifactIdentity = artifact,
            sourceKey = source,
            runtimeInstanceId = "runtime-1",
            runtimeGeneration = 1,
            invocationContext = "approved-context",
        )
        val authorizer = MutablePluginSystemEventAuthorizer()
        authorizer.grant(
            PluginEventGrantKey(artifact, source),
            setOf(
                PluginHostPermission.REQUEST_LOGIN_UI,
                PluginHostPermission.REQUEST_SOURCE_REFRESH,
                PluginHostPermission.REQUEST_LOGOUT,
                PluginHostPermission.REPORT_DIAGNOSTIC,
            ),
        )
        authorizer.setRuntimeStatus(
            scope,
            PluginEventRuntimeStatus(
                hasUserInteractionContext = true,
                sourceCapabilities = setOf("LOGIN"),
            ),
        )

        val loginCalls = mutableListOf<Pair<SourceKey, String>>()
        val refreshCalls = mutableListOf<Triple<SourceKey, String?, SourceRefreshScope>>()
        val logoutCalls = mutableListOf<Pair<SourceKey, String>>()
        val diagnosticCalls = mutableListOf<Triple<SourceKey, String, Int>>()
        val ports = PluginSystemEventHostPorts(
            login = PluginLoginIntentPort { target, eventId, _ ->
                loginCalls += target.sourceKey to eventId
                PluginEventOutcome.Succeeded
            },
            refresh = PluginSourceRefreshPort { target, contextRef, payload ->
                refreshCalls += Triple(target.sourceKey, contextRef, payload.scope)
                PluginEventOutcome.Succeeded
            },
            logout = PluginLogoutPort { target, eventId, _ ->
                logoutCalls += target.sourceKey to eventId
                PluginEventOutcome.Succeeded
            },
            diagnostic = PluginDiagnosticPort { target, eventId, occurrenceCount, _ ->
                diagnosticCalls += Triple(target.sourceKey, eventId, occurrenceCount)
                PluginEventOutcome.Succeeded
            },
        )
        val registry = PluginSystemEventHandlerRegistry().also { it.registerV1HostHandlers(ports) }
        assertEquals(PluginSystemEventNames.V1, registry.capabilityIds())
        assertTrue(registry.supports(PluginSystemEventKind.COMMAND, PluginSystemEventNames.AUTH_LOGIN_REQUEST))
        assertTrue(registry.supports(PluginSystemEventKind.COMMAND, PluginSystemEventNames.SOURCE_REFRESH_REQUEST))
        assertTrue(registry.supports(PluginSystemEventKind.COMMAND, PluginSystemEventNames.AUTH_LOGOUT_REQUEST))
        assertTrue(registry.supports(PluginSystemEventKind.EVENT, PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT))

        val codec = PluginSystemEventCodec()
        val gateway = PluginSystemEventGateway(
            registry = registry,
            authorizer = authorizer,
            codec = codec,
            dispatcherScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        try {
            val loginReceipt = gateway.submit(
                scope,
                codec.encodePayload(
                    kind = PluginSystemEventKind.COMMAND,
                    name = PluginSystemEventNames.AUTH_LOGIN_REQUEST,
                    id = "login-event",
                    payload = LoginRequestV1(reasonCode = "AUTH_REQUIRED"),
                    serializer = LoginRequestV1.serializer(),
                ),
            )
            assertEquals(PluginEventDisposition.ACCEPTED, loginReceipt.disposition)
            assertTrue(loginReceipt.operationRef != null && loginReceipt.operationRef != "login-event")
            assertEquals(
                PluginEventDisposition.ACCEPTED,
                gateway.submit(
                    scope,
                    codec.encodePayload(
                        kind = PluginSystemEventKind.COMMAND,
                        name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                        id = "refresh-event",
                        contextRef = "approved-context",
                        payload = SourceRefreshRequestV1(SourceRefreshScope.ACTIVE_CONTEXT, "USER_ACTION"),
                        serializer = SourceRefreshRequestV1.serializer(),
                    ),
                ).disposition,
            )
            assertEquals(
                PluginEventDisposition.DENIED,
                gateway.submit(
                    scope,
                    codec.encodePayload(
                        kind = PluginSystemEventKind.COMMAND,
                        name = PluginSystemEventNames.SOURCE_REFRESH_REQUEST,
                        id = "refresh-widened",
                        contextRef = "other-context",
                        payload = SourceRefreshRequestV1(SourceRefreshScope.ACTIVE_CONTEXT, "USER_ACTION"),
                        serializer = SourceRefreshRequestV1.serializer(),
                    ),
                ).disposition,
            )
            val logoutReceipt = gateway.submit(
                scope,
                codec.encodePayload(
                    kind = PluginSystemEventKind.COMMAND,
                    name = PluginSystemEventNames.AUTH_LOGOUT_REQUEST,
                    id = "logout-event",
                    payload = LogoutRequestV1(reasonCode = "USER_ACTION"),
                    serializer = LogoutRequestV1.serializer(),
                ),
            )
            assertEquals(PluginEventDisposition.ACCEPTED, logoutReceipt.disposition)
            assertTrue(logoutReceipt.operationRef != null && logoutReceipt.operationRef != "logout-event")
            var diagnosticOperationRef: String? = null
            repeat(2) { index ->
                val receipt = gateway.submit(
                    scope,
                    codec.encodePayload(
                        kind = PluginSystemEventKind.EVENT,
                        name = PluginSystemEventNames.DIAGNOSTIC_MESSAGE_REPORT,
                        id = "diagnostic-$index",
                        payload = DiagnosticMessageV1(
                            code = "network.failure",
                            severity = PluginDiagnosticSeverity.WARNING,
                            fallbackMessage = "bounded diagnostic",
                        ),
                        serializer = DiagnosticMessageV1.serializer(),
                    ),
                )
                assertEquals(
                    if (index == 0) PluginEventDisposition.ACCEPTED else PluginEventDisposition.DEDUPLICATED,
                    receipt.disposition,
                )
                if (index == 0) {
                    assertTrue(receipt.operationRef != null && receipt.operationRef != "diagnostic-0")
                    diagnosticOperationRef = receipt.operationRef
                }
            }
            advanceUntilIdle()

            assertEquals(listOf(source to requireNotNull(loginReceipt.operationRef)), loginCalls)
            assertEquals(listOf(source to requireNotNull(logoutReceipt.operationRef)), logoutCalls)
            assertEquals(listOf(Triple(source, requireNotNull(diagnosticOperationRef), 2)), diagnosticCalls)
        } finally {
            gateway.close()
        }

        assertEquals(
            listOf<Triple<SourceKey, String?, SourceRefreshScope>>(
                Triple(source, "approved-context", SourceRefreshScope.ACTIVE_CONTEXT),
            ),
            refreshCalls,
        )
    }
}
