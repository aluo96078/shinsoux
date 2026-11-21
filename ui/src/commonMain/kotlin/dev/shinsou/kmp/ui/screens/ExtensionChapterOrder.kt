package dev.shinsou.kmp.ui.screens

/**
 * Keeps the extension's rendered website order authoritative. The optional user action reverses
 * the complete sequence without interpreting chapter titles as numbers or sortable labels.
 */
internal fun <T> websiteOrderedItems(items: List<T>, reverseWebsiteOrder: Boolean): List<T> =
    if (reverseWebsiteOrder) items.reversed() else items
