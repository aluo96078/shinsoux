package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.domain.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The settings that follow an account from device to device (`setting` records, one per path).
 * Everything else in [AppSettings] describes this device (layout, downloads, security, sync
 * itself) and never leaves it. A path is `<section>.<field>` of the serialised settings and is
 * also the record key, so a field's JSON form is its wire form.
 */
internal object PortableSettings {
    val paths: List<String> = listOf(
        "general.languagePreference",
        "general.dateFormat",
        "general.defaultStartingScreen",
        "appearance.theme",
        "appearance.amoledDark",
        "appearance.tintColor",
        "appearance.timestampFormat",
        "appearance.relativeTimestamps",
        "library.sort",
        "library.filter",
        "library.categoryUpdateBehaviour",
        "library.globalUpdateRestrictions",
        "library.autoRefreshMetadata",
        "reader.readingMode",
        "reader.novelFontSizeSp",
        "reader.novelLineHeightMultiplier",
        "reader.novelMaxWidthDp",
        "reader.doubleTapToZoom",
        "reader.animatePageTransitions",
        "reader.showPageNumber",
        "reader.skipFilteredChapters",
        "reader.skipReadChapters",
        "reader.skipDuplicateChapters",
        "reader.colorFilter",
        "reader.splitTallImages",
        "reader.webtoonSidePadding",
        "tracking.autoSyncAfterRead",
        "tracking.updateProgressAfterRead",
        "browse.checkExtensionUpdates",
        "browse.showNsfwSources",
        "browse.enabledLanguages",
    )

    private val known = paths.toHashSet()

    /** Nulls are written out: "follow the system" is a value another device has to receive. */
    private val json = Json(AppSnapshotJson.format) {
        explicitNulls = true
        prettyPrint = false
    }

    fun isPortable(path: String): Boolean = path in known

    fun encode(settings: AppSettings): JsonObject =
        json.encodeToJsonElement(AppSettings.serializer(), settings) as JsonObject

    fun valueAt(encoded: JsonObject, path: String): JsonElement? {
        val section = encoded[path.substringBefore('.')] as? JsonObject ?: return null
        return section[path.substringAfter('.')]
    }

    /** Paths whose value differs between two settings objects. */
    fun changed(previous: AppSettings, next: AppSettings): List<String> {
        if (previous == next) return emptyList()
        val before = encode(previous)
        val after = encode(next)
        return paths.filter { valueAt(before, it) != valueAt(after, it) }
    }

    /**
     * [settings] with one portable field replaced. Throws [IllegalArgumentException] when the value
     * does not fit the field (another client version, or a hand-edited record).
     */
    fun patch(settings: AppSettings, path: String, value: JsonElement): AppSettings {
        require(isPortable(path)) { "Not a portable setting" }
        val encoded = encode(settings)
        val sectionName = path.substringBefore('.')
        val section = requireNotNull(encoded[sectionName] as? JsonObject) { "Unknown settings section" }
        val patched = JsonObject(encoded + (sectionName to JsonObject(section + (path.substringAfter('.') to value))))
        return json.decodeFromJsonElement(AppSettings.serializer(), patched)
    }
}
