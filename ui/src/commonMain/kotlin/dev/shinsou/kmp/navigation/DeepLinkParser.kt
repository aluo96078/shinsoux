package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.plugin.trust.AddRepositoryLink

import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ShinsouDeepLink

/** Parses the URL contract already published by the native Shinsou application. */
public object DeepLinkParser {
    public fun parse(rawValue: String): ShinsouDeepLink? {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("shinsou://", ignoreCase = true)) return null
        if (trimmed.length > MAX_DEEP_LINK_LENGTH || '#' in trimmed) return null
        val routeValue = trimmed.substringAfter("://")
        val route = routeValue.substringBefore('?').trim('/').split('/')
        return when (route.firstOrNull()?.lowercase()) {
            "library" -> ShinsouDeepLink.OpenSection(DeepLinkSection.Library)
            "updates" -> ShinsouDeepLink.OpenSection(DeepLinkSection.Updates)
            "history" -> ShinsouDeepLink.OpenSection(DeepLinkSection.History)
            "browse" -> ShinsouDeepLink.OpenSection(DeepLinkSection.Browse)
            "more" -> ShinsouDeepLink.OpenSection(DeepLinkSection.More)
            "settings" -> ShinsouDeepLink.OpenSettings
            "manga" -> route.getOrNull(1)?.toLongOrNull()?.let(ShinsouDeepLink::OpenManga)
            "chapter" -> {
                val chapterId = route.getOrNull(1)?.toLongOrNull() ?: return null
                val mangaId = route.getOrNull(2)?.toLongOrNull() ?: -1L
                ShinsouDeepLink.OpenChapter(mangaId = mangaId, chapterId = chapterId)
            }
            "add-repository" -> AddRepositoryLink.parse(trimmed)?.let {
                ShinsouDeepLink.AddRepository(it.repositoryUrl, it.fingerprint)
            }
            "add-dev-repository" -> parseDevRepositoryLink(trimmed)
            else -> null
        }
    }

    private fun parseDevRepositoryLink(link: String): ShinsouDeepLink? {
        // A parse probe on an incoming link: one that is not a URL is ignored.
        val url = runCatching { io.ktor.http.Url(link) }.getOrNull() ?: return null
        val repository = url.parameters.getAll("url")?.singleOrNull() ?: return null
        if (!repository.startsWith("http://") && !repository.startsWith("https://")) return null
        return ShinsouDeepLink.AddDevRepository(repository)
    }

    private const val MAX_DEEP_LINK_LENGTH = 4_096
}
