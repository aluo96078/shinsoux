package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.ExactPluginSourceTarget
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.plugin.events.BoundedPluginDiagnosticLog
import dev.shinsou.kmp.plugin.events.ExactSourceRefreshInvalidations
import dev.shinsou.kmp.plugin.events.PluginDiagnosticPort
import dev.shinsou.kmp.plugin.events.PluginEventExecutionReport
import dev.shinsou.kmp.plugin.events.PluginEventObserver
import dev.shinsou.kmp.plugin.events.PluginEventOutcome
import dev.shinsou.kmp.plugin.events.PluginLoginIntentPort
import dev.shinsou.kmp.plugin.events.PluginLogoutPort
import dev.shinsou.kmp.plugin.events.PluginSourceRefreshPort
import dev.shinsou.kmp.plugin.events.PluginSystemEventHostPorts

/**
 * Clears the exact-source login, logout and refresh state a closed runtime or an invalidated
 * artifact leaves behind (part of [PluginSubsystem]).
 */
internal class PluginLifecycleObserver(
    private val sourceInvalidations: ExactSourceRefreshInvalidations,
    private val loginRequests: PluginLoginRequestCoordinator,
    private val logoutRequests: PluginLogoutRequestCoordinator,
) : PluginEventObserver {
    override fun onCompleted(report: PluginEventExecutionReport) = Unit

    override fun onRuntimeClosed(scope: BoundPluginScope) {
        val target = ExactPluginSourceTarget(
            scope.artifactIdentity,
            scope.sourceKey,
        )
        sourceInvalidations.clear(target)
        loginRequests.clearTarget(target)
        logoutRequests.clearSource(target)
    }

    override fun onArtifactInvalidated(identity: PluginArtifactIdentity) {
        loginRequests.clearArtifact(identity)
        logoutRequests.clearArtifact(identity)
    }
}

/**
 * The host side of the v1 system events (login intent, exact-source refresh, logout, diagnostic).
 * [manager] is read when an event arrives: the manager is built after the event gateway it uses.
 */
internal fun pluginSystemEventHostPorts(
    manager: () -> PluginManager,
    loginRequests: PluginLoginRequestCoordinator,
    logoutRequests: PluginLogoutRequestCoordinator,
    sourceInvalidations: ExactSourceRefreshInvalidations,
    diagnosticLog: BoundedPluginDiagnosticLog,
): PluginSystemEventHostPorts = PluginSystemEventHostPorts(
    login = PluginLoginIntentPort { target, eventId, payload ->
        if (logoutRequests.hasTarget(target)) {
            return@PluginLoginIntentPort PluginEventOutcome.Suppressed
        }
        val sourceKey = target.sourceKey
        val legacy = manager().exactLegacySource(target)
        val exactV2 = manager().exactExtensionSource(target)
        val sourceId = sourceKey.legacyLongId
            ?: return@PluginLoginIntentPort PluginEventOutcome.Suppressed
        val sourceName = legacy?.name ?: exactV2?.descriptor?.displayName
            ?: return@PluginLoginIntentPort PluginEventOutcome.Suppressed
        if (loginRequests.requestEvent(eventId, target, sourceId, sourceName, payload.fallbackMessage)) {
            PluginEventOutcome.Succeeded
        } else {
            PluginEventOutcome.Suppressed
        }
    },
    refresh = PluginSourceRefreshPort { target, _, _ ->
        // This deliberately checks only the exact live source. It never calls the
        // repository/global refresh coordinator.
        val exactV2 = manager().exactExtensionSource(target)
        val legacy = manager().exactLegacySource(target)
        if (exactV2 != null || legacy != null) {
            sourceInvalidations.invalidate(target)
            PluginEventOutcome.Succeeded
        } else {
            PluginEventOutcome.Suppressed
        }
    },
    logout = PluginLogoutPort { target, eventId, payload ->
        // Logout wins the exact-source modal conflict and expires only that login.
        loginRequests.clearTarget(target)
        val exactName = manager().exactExtensionSource(target)?.descriptor?.displayName
        val legacyName = manager().exactLegacySource(target)?.name
        val sourceName = exactName ?: legacyName
            ?: return@PluginLogoutPort PluginEventOutcome.Suppressed
        val requested = logoutRequests.request(
            PluginLogoutConfirmation(
                eventId = eventId,
                target = target,
                sourceName = sourceName,
                message = payload.fallbackMessage,
            ),
        )
        if (requested) PluginEventOutcome.Succeeded else PluginEventOutcome.Suppressed
    },
    diagnostic = PluginDiagnosticPort { target, eventId, count, payload ->
        // REPORT_DIAGNOSTIC is log-only. User projection additionally requires the
        // separate REPORT_USER_MESSAGE exact grant and a host presenter.
        diagnosticLog.report(target, eventId, payload, count)
    },
)
