package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionPublicationPageV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.testing.TestOnlyApi

/**
 * The tests of higher modules (`:ui`, the app) reach these internal declarations of :feature-plugin
 * only through here; see [TestOnlyApi]. Product code never mints a challenge capability or a unit
 * selection: [PluginWebChallengeCoordinator] and the extension content consumer do.
 */
@TestOnlyApi
public object PluginTestAccess {
    /** A fresh capability, as [PluginWebChallengeCoordinator] issues one. */
    public fun webChallengeCapability(): SourceWebChallengeCapability = SourceWebChallengeCapability()

    public fun unitSelection(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publicationTitle: String,
        unit: RemoteUnitV2,
        ownerToken: Any,
    ): ExtensionUnitSelectionV2 = ExtensionUnitSelectionV2(sourceKey, remotePublicationId, publicationTitle, unit, ownerToken)

    /** One page of [publication]'s units; [position] is the page number and whether more follow. */
    public fun publicationPage(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
        units: List<ExtensionUnitSelectionV2>,
        position: Pair<Int, Boolean>,
        offline: Boolean = false,
    ): ExtensionPublicationPageV2 =
        ExtensionPublicationPageV2(sourceKey, publication, units, position.first, position.second, offline)

    /** The numeric runtime id of a source of a publisher-scoped install ([PluginInstallKeys.projectedSourceId]). */
    public fun projectedSourceId(installKey: String, sourceId: String): Long =
        PluginInstallKeys.projectedSourceId(installKey, sourceId)
}
