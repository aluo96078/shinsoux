package dev.shinsou.kmp.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * The offline models on this device: `<root>/<model id>/<file name>`. A file only appears under
 * its final name after its size and SHA-256 matched the catalog, so a download that was
 * interrupted or tampered with is never handed to the engine.
 */
public class OfflineModelStore(
    root: String,
    private val http: HttpClient,
    private val baseUrl: String = OfflineModelCatalog.ATTACHMENT_BASE_URL,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : OfflineModelFiles {
    private val root: Path = root.toPath()
    private val downloads = Mutex()

    private fun directory(model: OfflineModel): Path = root / model.id

    override fun installed(model: OfflineModel): OfflineModelPaths? {
        val directory = directory(model)
        fun path(file: OfflineModelFile): String? {
            val path = directory / file.name
            val metadata = fileSystem.metadataOrNull(path) ?: return null
            return path.toString().takeIf { metadata.isRegularFile && metadata.size == file.size }
        }
        return OfflineModelPaths(
            model = path(model.model) ?: return null,
            sourceVocab = path(model.sourceVocab) ?: return null,
            targetVocab = path(model.targetVocab) ?: return null,
            shortlist = path(model.shortlist) ?: return null,
        )
    }

    override suspend fun download(model: OfflineModel, onProgress: (received: Long, total: Long) -> Unit): Unit = downloads.withLock {
        val directory = directory(model)
        fileSystem.createDirectories(directory)
        val total = model.downloadBytes
        var received = 0L
        for (file in model.files) {
            val target = directory / file.name
            if (fileSystem.metadataOrNull(target)?.size == file.size) {
                received += file.size
                onProgress(received, total)
                continue
            }
            val partial = directory / (file.name + ".part")
            try {
                var written = 0L
                val hashing = HashingSink.sha256(fileSystem.sink(partial))
                try {
                    hashing.buffer().use { output ->
                        http.prepareGet(baseUrl + file.location) {
                            // The client's deadline is for API calls; a 44 MB file on a slow
                            // line needs longer. A stalled connection still times out.
                            if (http.pluginOrNull(HttpTimeout) != null) {
                                timeout {
                                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                                    socketTimeoutMillis = 60_000
                                }
                            }
                        }.execute { response ->
                            if (!response.status.isSuccess()) throw unavailable("Model download failed: HTTP ${response.status.value}")
                            val channel = response.bodyAsChannel()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = channel.readAvailable(buffer, 0, buffer.size)
                                if (read < 0) break
                                if (read == 0) continue
                                written += read
                                // Stop as soon as the server sends more than the pinned size.
                                if (written > file.size) throw unavailable("${file.name} is larger than expected")
                                output.write(buffer, 0, read)
                                onProgress(received + written, total)
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TranslationException) {
                    throw error
                } catch (error: Exception) {
                    throw TranslationException(TranslationFailure.PROVIDER_UNAVAILABLE, "Model download failed: ${error.message}", error)
                }
                if (written != file.size || hashing.hash.hex() != file.sha256) throw unavailable("${file.name} did not match its pinned checksum")
                fileSystem.atomicMove(partial, target)
                received += file.size
            } finally {
                fileSystem.delete(partial, mustExist = false)
            }
        }
    }

    override fun remove(model: OfflineModel) {
        fileSystem.deleteRecursively(directory(model), mustExist = false)
    }

    private fun unavailable(message: String) = TranslationException(TranslationFailure.PROVIDER_UNAVAILABLE, message)
}
