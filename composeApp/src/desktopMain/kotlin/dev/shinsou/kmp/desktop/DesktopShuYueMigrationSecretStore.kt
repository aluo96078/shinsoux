package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.migration.shuyue.KeyValueShuYueMigrationSecretStore
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationSecretStore
import java.nio.file.Path

/**
 * ShuYue batches use AES-GCM with the master key held by macOS Keychain or current-user DPAPI.
 * Unsupported credential stores fail closed in [DesktopPluginKeyValueStore].
 */
internal class DesktopShuYueMigrationSecretStore(
    directory: Path = DesktopAppDirectories.dataRoot,
) : ShuYueMigrationSecretStore by KeyValueShuYueMigrationSecretStore(
    DesktopPluginKeyValueStore(directory),
)
