package dev.shinsou.kmp.ui.i18n

import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.events.PluginHostPermission

/** Localized, human-readable labels for permissions shown in plugin review UI. */
public fun PluginRuntimePermission.localizedLabel(strings: ShinsouStrings): String = strings.text(
    when (this) {
        PluginRuntimePermission.EXECUTE_SCRIPT -> "Run extension script"
        PluginRuntimePermission.NETWORK -> "Network access"
        PluginRuntimePermission.COOKIE_STORAGE -> "Cookie storage"
        PluginRuntimePermission.CREDENTIAL_ACCESS -> "Credential access"
        PluginRuntimePermission.LOGIN_PROMPT -> "Show login prompt"
        PluginRuntimePermission.FAVORITE_MUTATION -> "Modify favorites"
        PluginRuntimePermission.BROWSER_CHALLENGE -> "Open browser challenge"
        PluginRuntimePermission.COOKIE_READ -> "Read session cookies"
        PluginRuntimePermission.BROWSER_IMAGES -> "Load images in a browser"
    },
)

/** Localized, human-readable labels for host event permissions. */
public fun PluginHostPermission.localizedLabel(strings: ShinsouStrings): String = strings.text(
    when (this) {
        PluginHostPermission.REQUEST_LOGIN_UI -> "Request login UI"
        PluginHostPermission.REQUEST_SOURCE_REFRESH -> "Request source refresh"
        PluginHostPermission.REQUEST_LOGOUT -> "Request logout"
        PluginHostPermission.REPORT_DIAGNOSTIC -> "Report diagnostics"
        PluginHostPermission.REPORT_USER_MESSAGE -> "Show user messages"
        PluginHostPermission.REQUEST_BROWSER_CHALLENGE -> "Request browser challenge"
    },
)
