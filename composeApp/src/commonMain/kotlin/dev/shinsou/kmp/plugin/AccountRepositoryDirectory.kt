package dev.shinsou.kmp.plugin

import kotlinx.coroutines.flow.StateFlow

/** A repository the sync account knows about: a suggestion for devices that do not have it yet. */
public data class AccountRepository(
    val url: String,
    val name: String,
    /** Fingerprint of the key the repository was signed with when it was added; empty when unsigned. */
    val signingKeyFingerprint: String = "",
)

/**
 * The account's list of repositories, as the browse layer needs it. It is an interface so the
 * plugin package does not depend on the sync implementation: sync records are suggestions only,
 * and what a device actually uses stays decided by its own user.
 */
public interface AccountRepositoryDirectory {
    /** Repositories the account suggests (live records), empty when signed out. */
    public val repositories: StateFlow<List<AccountRepository>>

    /** Removes [url] from the account so other devices stop receiving it as a suggestion. */
    public suspend fun forget(url: String)
}

/**
 * A package the sync account has used on some device. It is a hint only: nothing about it is
 * trusted, and another device acts on it only through a repository its own user already trusts.
 */
public data class AccountPackage(
    /** The package's install key (manifest id), the same on every device that trusts the publisher. */
    val packageId: String,
    /** The repository the package was installed from, normalized; empty when the device did not know. */
    val repositoryUrl: String,
    val name: String,
    val version: String,
    val versionCode: Long? = null,
    /** Signing key of the repository when installed; informational, never used to trust anything. */
    val signingKeyFingerprint: String = "",
)

/** The account's list of used packages, as the browse layer needs it (see [AccountRepositoryDirectory]). */
public interface AccountPackageDirectory {
    /** Packages the account has seen (live records), empty when signed out or the switch is off. */
    public val packages: StateFlow<List<AccountPackage>>
}
