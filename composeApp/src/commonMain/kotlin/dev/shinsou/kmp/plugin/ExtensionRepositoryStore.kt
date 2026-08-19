package dev.shinsou.kmp.plugin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

public interface ExtensionRepositoryStore {
    public suspend fun list(): List<ExtensionRepository>
    public suspend fun put(repository: ExtensionRepository)
    public suspend fun remove(baseUrl: String)
    public suspend fun selected(): String?
    public suspend fun select(baseUrl: String?)
}

public class KeyValueExtensionRepositoryStore(
    private val keyValueStore: PluginKeyValueStore,
    private val json: Json = PluginJson,
) : ExtensionRepositoryStore {
    private val mutex = Mutex()
    private val repositoriesKey = "plugin.repositories"
    private val selectedKey = "plugin.repositories.selected"

    override suspend fun list(): List<ExtensionRepository> = mutex.withLock { read() }

    override suspend fun put(repository: ExtensionRepository): Unit = mutex.withLock {
        val repositories = read().filterNot { it.baseUrl == repository.baseUrl } + repository
        write(repositories)
    }

    override suspend fun remove(baseUrl: String): Unit = mutex.withLock {
        write(read().filterNot { it.baseUrl == baseUrl })
        if (keyValueStore.getString(selectedKey) == baseUrl) keyValueStore.remove(selectedKey)
    }

    override suspend fun selected(): String? = keyValueStore.getString(selectedKey)

    override suspend fun select(baseUrl: String?) {
        if (baseUrl == null) keyValueStore.remove(selectedKey)
        else keyValueStore.putString(selectedKey, baseUrl)
    }

    private suspend fun read(): List<ExtensionRepository> {
        val encoded = keyValueStore.getString(repositoriesKey) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ExtensionRepository.serializer()), encoded)
        }.getOrDefault(emptyList())
    }

    private suspend fun write(repositories: List<ExtensionRepository>) {
        if (repositories.isEmpty()) keyValueStore.remove(repositoriesKey)
        else keyValueStore.putString(
            repositoriesKey,
            json.encodeToString(ListSerializer(ExtensionRepository.serializer()), repositories),
        )
    }
}
