package dev.shinsou.build

import org.gradle.api.provider.ProviderFactory

/*
 * R5-2 (0006): every signing input of the build, read in one place. Values are only passed on to
 * the signing configurations and tools; they are never logged.
 */

/**
 * The macOS signing identity (`-PshinsouMacOsSigningIdentity` or
 * `SHINSOU_MACOS_SIGNING_IDENTITY`) and optional keychain. Compose signs Developer ID builds; local
 * Apple Development builds are signed by DesktopAppPlugin's tasks, because Compose's signer
 * hard-codes the Developer ID prefix.
 */
public class MacOsSigning internal constructor(providers: ProviderFactory) {
    val identity: String? = providers.gradleProperty("shinsouMacOsSigningIdentity")
        .orElse(providers.environmentVariable("SHINSOU_MACOS_SIGNING_IDENTITY"))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    val localDevelopmentIdentity: String? = identity?.takeIf { it.startsWith("Apple Development:") }
    val composeIdentity: String? = identity?.takeUnless { it.startsWith("Apple Development:") }

    val keychain: String? = providers.gradleProperty("shinsouMacOsSigningKeychain")
        .orElse(providers.environmentVariable("SHINSOU_MACOS_SIGNING_KEYCHAIN"))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    /** Fails the configuration on a keychain without an identity or an unsupported identity. */
    internal fun checkValid() {
        check(keychain == null || identity != null) {
            "A macOS signing keychain requires shinsouMacOsSigningIdentity or " +
                "SHINSOU_MACOS_SIGNING_IDENTITY."
        }
        check(
            identity == null ||
                identity.startsWith("Apple Development:") ||
                identity.startsWith("Developer ID Application:") ||
                identity.startsWith("3rd Party Mac Developer Application:"),
        ) {
            "Unsupported macOS signing identity '$identity'. Use the full Apple Development, " +
                "Developer ID Application, or 3rd Party Mac Developer Application certificate name."
        }
    }
}

/** One Android signing configuration, enabled only when all four environment variables are set. */
public class AndroidSigningValues private constructor(
    private val kind: String,
    private val variableNames: List<String>,
    providers: ProviderFactory,
) {
    private val values: List<String?> = variableNames.map { providers.environmentVariable(it).orNull }

    val storeFile: String? get() = values[0]
    val storePassword: String? get() = values[1]
    val keyAlias: String? get() = values[2]
    val keyPassword: String? get() = values[3]

    val configured: Boolean = values.all { !it.isNullOrBlank() }

    internal fun checkComplete() {
        check(values.none { !it.isNullOrBlank() } || configured) {
            val names = variableNames.dropLast(1).joinToString(", ") + ", and " + variableNames.last()
            "Android $kind signing is only enabled when $names are all set."
        }
    }

    internal companion object {
        fun release(providers: ProviderFactory) = AndroidSigningValues(
            "release",
            listOf("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"),
            providers,
        )

        fun publicDebug(providers: ProviderFactory) = AndroidSigningValues(
            "public debug",
            listOf(
                "ANDROID_PUBLIC_DEBUG_KEYSTORE_PATH",
                "ANDROID_PUBLIC_DEBUG_KEYSTORE_PASSWORD",
                "ANDROID_PUBLIC_DEBUG_KEY_ALIAS",
                "ANDROID_PUBLIC_DEBUG_KEY_PASSWORD",
            ),
            providers,
        )
    }
}
