package dev.shinsou.kmp.domain.model

/** Sentinel used by [LibrarySettings.defaultCategoryId] to ask whenever a title is added. */
public const val ALWAYS_ASK_CATEGORY_ID: Long = -1L

/**
 * Normalizes a saved category assignment.
 *
 * Default is mutually exclusive with real categories. An empty assignment means Default, matching
 * the original app's virtual Default category.
 */
public fun normalizeMangaCategorySelection(categoryIds: Collection<Long>): Set<Long> {
    val realCategoryIds = categoryIds.filterTo(linkedSetOf()) { it != Category.Default.id }
    return realCategoryIds.ifEmpty { linkedSetOf(Category.Default.id) }
}

/** Applies one picker tap while keeping Default mutually exclusive with real categories. */
public fun toggleMangaCategorySelection(
    selectedCategoryIds: Set<Long>,
    categoryId: Long,
): Set<Long> {
    if (categoryId == Category.Default.id) return setOf(Category.Default.id)

    val updated = selectedCategoryIds.toMutableSet()
    if (categoryId in updated) {
        updated.remove(categoryId)
        if (updated.isEmpty() || updated == setOf(Category.Default.id)) return setOf(Category.Default.id)
    } else {
        updated.remove(Category.Default.id)
        updated.add(categoryId)
    }
    return normalizeMangaCategorySelection(updated)
}

/** Returns the categories common to every selected manga, falling back to Default when none overlap. */
public fun commonMangaCategorySelection(categorySelections: Collection<Collection<Long>>): Set<Long> {
    val iterator = categorySelections.iterator()
    if (!iterator.hasNext()) return setOf(Category.Default.id)

    val common = normalizeMangaCategorySelection(iterator.next()).toMutableSet()
    while (iterator.hasNext()) {
        common.retainAll(normalizeMangaCategorySelection(iterator.next()))
    }
    return common.ifEmpty { setOf(Category.Default.id) }
}

/** Always Ask is a picker policy, never a real Library tab. */
public fun resolveLibraryStartingCategory(
    configuredCategoryId: Long,
    availableCategoryIds: Collection<Long>,
): Long = configuredCategoryId.takeIf { it != ALWAYS_ASK_CATEGORY_ID && it in availableCategoryIds }
    ?: Category.Default.id

/** Always Ask only needs a dialog when at least one real category can be selected. */
public fun shouldAskForCategoriesOnFavorite(
    configuredCategoryId: Long,
    availableCategoryIds: Collection<Long>,
): Boolean = configuredCategoryId == ALWAYS_ASK_CATEGORY_ID &&
    availableCategoryIds.any { it > Category.Default.id }
