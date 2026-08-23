package dev.shinsou.kmp.plugin.events

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
public data class PluginEventGrantReview(
    val artifact: PluginArtifactIdentity,
    val sourceKeys: List<SourceKey>,
    val requestedPermissions: Set<PluginHostPermission>,
)

/** Durable exact-digest admission store. Repository requests never become grants without approve(). */
public class KeyValuePluginEventGrantAdmission(
    private val store: PluginKeyValueStore,
    private val authorizer: MutablePluginSystemEventAuthorizer,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    public suspend fun stage(review: PluginEventGrantReview) {
        require(review.sourceKeys.isNotEmpty())
        require(review.sourceKeys.all { it.packageId == review.artifact.packageId })
        require(PluginHostPermission.REPORT_USER_MESSAGE !in review.requestedPermissions) {
            "User-visible plugin messages are unsupported without a safe production presenter"
        }
        store.putString(pendingKey(review.artifact), json.encodeToString(PluginEventGrantReview.serializer(), review))
    }

    public suspend fun pending(artifact: PluginArtifactIdentity): PluginEventGrantReview? =
        store.getString(pendingKey(artifact))?.let {
            json.decodeFromString(PluginEventGrantReview.serializer(), it)
        }

    public suspend fun isGranted(review: PluginEventGrantReview): Boolean {
        val encoded = store.getString(grantKey(review.artifact)) ?: return false
        val grants = json.decodeFromString(ListSerializer(PluginEventGrant.serializer()), encoded)
        return grants.size == review.sourceKeys.size &&
            grants.all { it.key.artifact == review.artifact && it.permissions == review.requestedPermissions } &&
            grants.mapNotNull { it.key.sourceKey }.toSet() == review.sourceKeys.toSet()
    }

    public suspend fun approve(
        artifact: PluginArtifactIdentity,
        permissions: Set<PluginHostPermission>,
    ) {
        val review = requireNotNull(pending(artifact)) { "No exact plugin event grant review is pending" }
        require(review.artifact == artifact) { "Pending review artifact identity does not match approval" }
        require(permissions == review.requestedPermissions) {
            "Approval must match the verified artifact's requested permission set"
        }
        val grants = review.sourceKeys.map { sourceKey ->
            PluginEventGrant(PluginEventGrantKey(artifact, sourceKey), permissions)
        }
        store.putString(grantKey(artifact), json.encodeToString(ListSerializer(PluginEventGrant.serializer()), grants))
        store.remove(pendingKey(artifact))
        grants.forEach { authorizer.grant(it.key, it.permissions) }
    }

    public suspend fun hydrate(review: PluginEventGrantReview) {
        val encoded = store.getString(grantKey(review.artifact)) ?: return
        val grants = json.decodeFromString(ListSerializer(PluginEventGrant.serializer()), encoded)
        val storedSourceKeys = grants.mapNotNull { it.key.sourceKey }.toSet()
        if (grants.any { it.key.artifact != review.artifact || it.permissions != review.requestedPermissions } ||
            storedSourceKeys != review.sourceKeys.toSet() || grants.size != review.sourceKeys.size
        ) {
            revoke(review.artifact)
            stage(review)
            return
        }
        grants.forEach { authorizer.grant(it.key, it.permissions) }
    }

    public suspend fun revoke(artifact: PluginArtifactIdentity) {
        store.getString(grantKey(artifact))?.let { encoded ->
            json.decodeFromString(ListSerializer(PluginEventGrant.serializer()), encoded)
                .forEach { authorizer.revoke(it.key) }
        }
        store.remove(grantKey(artifact))
        store.remove(pendingKey(artifact))
    }

    private fun pendingKey(identity: PluginArtifactIdentity): String = "plugin.events.pending.${identity.storageKey()}"
    private fun grantKey(identity: PluginArtifactIdentity): String = "plugin.events.grant.${identity.storageKey()}"
    private fun PluginArtifactIdentity.storageKey(): String =
        "${packageId.length}:$packageId|${version.length}:$version|$versionCode|$sha256"
}
