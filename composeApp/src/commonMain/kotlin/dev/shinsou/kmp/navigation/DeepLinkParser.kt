package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ShinsouDeepLink

/** Parses the URL contract already published by the native Shinsou application. */
object DeepLinkParser {
    fun parse(rawValue: String): ShinsouDeepLink? {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("shinsou://", ignoreCase = true)) return null
        val route = trimmed.substringAfter("://").substringBefore('?').trim('/').split('/')
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
            else -> null
        }
    }
}
