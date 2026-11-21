package dev.shinsou.kmp.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An app version as the releases are tagged: `1.0.2`, or `1.0.2-beta.3` for a beta. A version
 * without a beta number is newer than every beta of it (`1.0.2` > `1.0.2-beta.9`).
 */
public data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int? = null) : Comparable<AppVersion> {
    val isBeta: Boolean get() = beta != null

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this, other, AppVersion::major, AppVersion::minor, AppVersion::patch,
        // a release is newer than its betas
        { it.beta ?: Int.MAX_VALUE },
    )

    override fun toString(): String = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    public companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?""")

        /** The version of a tag or version name; null for anything else (e.g. `manga-models-v1`). */
        public fun parse(text: String): AppVersion? = PATTERN.matchEntire(text.trim())?.destructured?.let { (a, b, c, beta) ->
            AppVersion(a.toInt(), b.toInt(), c.toInt(), beta.toIntOrNull())
        }
    }
}

/** A published release of the app. */
public data class AppRelease(
    val version: AppVersion,
    val tag: String,
    val name: String,
    /** The release's page, where every platform's download is. */
    val url: String,
    val notes: String,
    val publishedAt: String?,
)

/** What a check may offer, by the user's settings. */
public enum class UpdateChannel {
    /** Releases only. */
    STABLE,

    /** Releases and betas: the newest of them, which is a release when a release came last. */
    BETA,
}

/**
 * The release to offer: the newest on [channel] that is newer than [current]; null when there
 * is none. Betas count only on [UpdateChannel.BETA]; a release outranks its own betas, so a
 * beta user is offered the release once it is out.
 */
public fun selectUpdate(current: AppVersion, releases: List<AppRelease>, channel: UpdateChannel): AppRelease? =
    releases
        .filter { channel == UpdateChannel.BETA || !it.version.isBeta }
        .maxByOrNull { it.version }
        ?.takeIf { it.version > current }

/**
 * What the app's update prompt uses of [AppUpdateChecker] (0007 B4): this version, whether the
 * launch check is still to run, and the release to offer.
 */
public interface AppUpdateSource {
    public val currentVersion: String

    /** True once per source, so once per launch. */
    public fun claimLaunchCheck(): Boolean

    /** The release to offer on [channel], or null when this version is the newest (or unknown). */
    public suspend fun check(channel: UpdateChannel): AppRelease?
}

/**
 * Asks GitHub for the app's releases. Only GitHub's public API is contacted, without
 * any account or identifier; drafts and releases that are not the app (model packs) are skipped.
 */
public class AppUpdateChecker(
    private val http: HttpClient,
    override val currentVersion: String,
    private val repository: String = REPOSITORY,
    private val apiBase: String = "https://api.github.com",
) : AppUpdateSource {
    private val json = Json { ignoreUnknownKeys = true }
    private var launchCheckClaimed = false

    /**
     * True once per checker, so once per launch: the checker lives as long as the app's process,
     * and a screen built again (a rotated Android activity) does not check again.
     */
    override fun claimLaunchCheck(): Boolean = !launchCheckClaimed.also { launchCheckClaimed = true }

    /** The release to offer on [channel], or null when this version is the newest (or unknown). */
    override suspend fun check(channel: UpdateChannel): AppRelease? {
        val current = AppVersion.parse(currentVersion) ?: return null
        return selectUpdate(current, releases(), channel)
    }

    public suspend fun releases(): List<AppRelease> {
        val response = http.get("$apiBase/repos/$repository/releases?per_page=50") {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) throw AppUpdateException("GitHub answered ${response.status.value}")
        return json.decodeFromString<List<GitHubRelease>>(response.bodyAsText())
            .filter { !it.draft }
            .mapNotNull { release ->
                val version = AppVersion.parse(release.tag) ?: return@mapNotNull null
                // a tag says beta exactly when GitHub does; a mismatch is not an app release
                if (version.isBeta != release.prerelease) return@mapNotNull null
                AppRelease(version, release.tag, release.name ?: release.tag, release.url, release.body.orEmpty(), release.publishedAt)
            }
    }

    @Serializable
    private class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        @SerialName("html_url") val url: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
    )

    public companion object {
        public const val REPOSITORY: String = "aluo96078/shinsoux"
    }
}

public class AppUpdateException(message: String) : Exception(message)
