package dev.shinsou.kmp.ui.i18n

import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.events.PluginHostPermission

/** Localized, human-readable labels for permissions shown in plugin review UI. */
public fun PluginRuntimePermission.localizedLabel(strings: ShinsouStrings): String = strings.text(
    when (this) {
        PluginRuntimePermission.EXECUTE_SCRIPT -> StringKey.RUN_EXTENSION_SCRIPT
        PluginRuntimePermission.NETWORK -> StringKey.NETWORK_ACCESS
        PluginRuntimePermission.COOKIE_STORAGE -> StringKey.COOKIE_STORAGE
        PluginRuntimePermission.CREDENTIAL_ACCESS -> StringKey.CREDENTIAL_ACCESS
        PluginRuntimePermission.LOGIN_PROMPT -> StringKey.SHOW_LOGIN_PROMPT
        PluginRuntimePermission.FAVORITE_MUTATION -> StringKey.MODIFY_FAVORITES
        PluginRuntimePermission.BROWSER_CHALLENGE -> StringKey.OPEN_BROWSER_CHALLENGE
        PluginRuntimePermission.COOKIE_READ -> StringKey.READ_SESSION_COOKIES
        PluginRuntimePermission.BROWSER_IMAGES -> StringKey.LOAD_IMAGES_IN_A_BROWSER
    },
)

/** Localized, human-readable labels for host event permissions. */
public fun PluginHostPermission.localizedLabel(strings: ShinsouStrings): String = strings.text(
    when (this) {
        PluginHostPermission.REQUEST_LOGIN_UI -> StringKey.REQUEST_LOGIN_UI
        PluginHostPermission.REQUEST_SOURCE_REFRESH -> StringKey.REQUEST_SOURCE_REFRESH
        PluginHostPermission.REQUEST_LOGOUT -> StringKey.REQUEST_LOGOUT
        PluginHostPermission.REPORT_DIAGNOSTIC -> StringKey.REPORT_DIAGNOSTICS
        PluginHostPermission.REPORT_USER_MESSAGE -> StringKey.SHOW_USER_MESSAGES
        PluginHostPermission.REQUEST_BROWSER_CHALLENGE -> StringKey.REQUEST_BROWSER_CHALLENGE
    },
)
