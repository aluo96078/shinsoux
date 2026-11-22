package dev.shinsou.kmp.manga

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What manga translation downloads and where detection runs (0007 C5): the recognition models,
 * the language and font packs, the accelerators' models and runtimes, and what older builds left
 * behind. Every change lets the [pipelines] go, so chapters started after use it.
 */
internal class MangaResourceManager(
    private val platform: MangaPlatform?,
    private val models: MangaModelStore,
    private val pipelines: MangaPipelines,
    private val work: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun downloadModels(onProgress: (received: Long, total: Long) -> Unit) {
        withContext(ioDispatcher) { models.download(MangaModelCatalog.VISION, onProgress) }
        pipelines.release()
    }

    /**
     * What a chapter from [from] into [to] still needs to download (MangaLanguages.packs): the
     * recogniser for pages not in Japanese, the font for simplified Chinese. Zero when all is here.
     */
    fun missingLanguageBytes(from: String, to: String): Long = MangaLanguages.packs(from, to).sumOf(models::missingBytes)

    /** Downloads what [missingLanguageBytes] counts; chapters started from then on use it. */
    suspend fun downloadLanguage(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit) {
        withContext(ioDispatcher) {
            val packs = MangaLanguages.packs(from, to)
            val total = packs.sumOf(models::missingBytes)
            var done = 0L
            for (pack in packs) {
                val missing = models.missingBytes(pack)
                if (missing == 0L) continue
                models.download(pack) { received, _ -> onProgress(done + received - (pack.downloadBytes - missing), total) }
                done += missing
            }
        }
        pipelines.release()
    }

    /** The ways this device can run detection, which are chosen, and what each still needs. */
    fun acceleratorStates(): List<MangaAccelerators.AcceleratorState> {
        val p = platform ?: return emptyList()
        val selected = models.selectedAccelerators()
        return p.accelerators().map { support ->
            val missing = support.packs.sumOf { models.missingBytes(it) } + runtimeMissingBytes(support.runtime)
            MangaAccelerators.AcceleratorState(support, support.accelerator in selected, installed = support.supported && missing == 0L, missingBytes = missing)
        }
    }

    /** What [runtime] takes once installed, when it is not installed yet; zero without one. */
    private fun runtimeMissingBytes(runtime: NativeRuntime?): Long =
        runtime?.takeIf { models.runtimeInstalled(it) == null }?.installedBytes ?: 0

    /**
     * Downloads what [accelerator] needs (its models, and for Qualcomm's NPU the QNN runtime from
     * Maven Central), then readies it ([MangaPlatform.prepare]); [onPreparing] is told when the
     * downloads are done and the (longer) preparation starts.
     */
    suspend fun downloadAccelerator(accelerator: MangaAccelerator, onProgress: (received: Long, total: Long) -> Unit, onPreparing: () -> Unit) {
        val p = platform ?: throw MangaModelException("Manga translation is not available on this device")
        val support = p.accelerators().first { it.accelerator == accelerator }
        if (!support.supported) throw MangaModelException(support.unsupportedReason ?: "Not available")
        withContext(ioDispatcher) { downloadAll(p, support, onProgress) }
        // without the recognition models yet, it is readied by the first chapter instead
        pathsWithRuntimes(p)?.let { paths ->
            onPreparing()
            withContext(work) { p.prepare(accelerator, paths) }
        }
        pipelines.release()
    }

    /** The packs of [support], then its runtime, with the progress counted over all of them. */
    private suspend fun downloadAll(p: MangaPlatform, support: AcceleratorSupport, onProgress: (received: Long, total: Long) -> Unit) {
        val total = support.packs.sumOf { models.missingBytes(it) } + (support.runtime?.takeIf { models.runtimeInstalled(it) == null }?.size ?: 0)
        var done = 0L
        for (pack in support.packs) {
            val missing = models.missingBytes(pack)
            models.download(pack) { received, _ -> onProgress(done + received, total) }
            done += missing
        }
        support.runtime?.let { runtime ->
            val before = done
            models.downloadRuntime(runtime, p::extractRuntime) { received, _ -> onProgress(before + received, total) }
        }
    }

    /**
     * Removes what [accelerator] downloaded and stops using it, and every other choice that needs
     * the same files (on an iPhone the GPU and the Neural Engine share one model).
     */
    suspend fun removeAccelerator(accelerator: MangaAccelerator) {
        val p = platform ?: return
        val supports = p.accelerators()
        val support = supports.first { it.accelerator == accelerator }
        pipelines.whileIdle {
            withContext(ioDispatcher) {
                support.packs.forEach(models::remove)
                support.runtime?.let(models::removeRuntime)
                p.forget(accelerator)
            }
        }
        val sharing = supports.filter { other -> other.packs.any { it in support.packs } || (other.runtime != null && other.runtime == support.runtime) }
            .map { it.accelerator }.toSet() + accelerator
        val left = models.selectedAccelerators() - sharing
        models.selectAccelerators(left.ifEmpty { setOf(MangaAccelerator.CPU) })
    }

    /** Chooses where detection runs (at least one); chapters started from then on use the choice. */
    suspend fun selectAccelerators(accelerators: Set<MangaAccelerator>) {
        models.selectAccelerators(accelerators)
        pipelines.release()
    }

    /** The models' paths with the downloaded runtimes' directories filled in. */
    fun pathsWithRuntimes(p: MangaPlatform): MangaModelPaths? {
        val paths = models.paths() ?: return null
        val qnn = p.accelerators().firstNotNullOfOrNull { it.runtime }?.let { models.runtimeInstalled(it) }
        return paths.copy(qnnRuntime = qnn)
    }

    /**
     * Everything manga translation downloads on this device, for the settings to list and manage:
     * the recognition models, the optional packs, what each accelerator needs (one row for choices
     * that share their files), and what older builds left behind.
     */
    fun resources(): List<MangaResource> {
        val p = platform ?: return emptyList()
        fun pack(kind: MangaResourceKind, pack: ModelPack, language: String? = null) =
            MangaResource(pack.id, kind, language, emptyList(), models.diskBytes(pack), models.missingBytes(pack))
        val list = mutableListOf(
            pack(MangaResourceKind.RECOGNITION, MangaModelCatalog.VISION),
            pack(MangaResourceKind.INPAINT, MangaModelCatalog.INPAINT),
            pack(MangaResourceKind.LANGUAGE, MangaModelCatalog.OCR_LINES),
            pack(MangaResourceKind.LANGUAGE, MangaModelCatalog.OCR_ZH, "zh"),
            pack(MangaResourceKind.LANGUAGE, MangaModelCatalog.OCR_EN, "en"),
            pack(MangaResourceKind.FONT, MangaModelCatalog.FONT_SC, "zh-Hans"),
        )
        val supports = p.accelerators().filter { it.supported && (it.packs.isNotEmpty() || it.runtime != null) }
        for ((_, group) in supports.groupBy { it.packs.map(ModelPack::id) to it.runtime?.id }) {
            list += acceleratorResource(group)
        }
        for ((name, bytes) in models.leftovers(inUse(p))) {
            list += MangaResource(name, MangaResourceKind.LEFTOVER, null, emptyList(), bytes, 0)
        }
        return list
    }

    /** One row for accelerators that share their files: those of the [group]'s first. */
    private fun acceleratorResource(group: List<AcceleratorSupport>): MangaResource {
        val first = group.first()
        val runtime = first.runtime
        val disk = first.packs.sumOf(models::diskBytes) + (runtime?.let(models::runtimeBytes) ?: 0)
        val missing = first.packs.sumOf(models::missingBytes) + runtimeMissingBytes(runtime)
        val id = (first.packs.map { it.id } + listOfNotNull(runtime?.id)).joinToString("+")
        return MangaResource(id, MangaResourceKind.ACCELERATOR, null, group.map { it.accelerator }, disk, missing)
    }

    /** Downloads what [resource] still misses; an accelerator's download also chooses it. */
    suspend fun downloadResource(resource: MangaResource, onProgress: (received: Long, total: Long) -> Unit, onPreparing: () -> Unit) {
        when (resource.kind) {
            MangaResourceKind.RECOGNITION -> downloadModels(onProgress)
            MangaResourceKind.INPAINT -> downloadInpaint(onProgress)
            MangaResourceKind.LANGUAGE, MangaResourceKind.FONT -> {
                val pack = MangaModelCatalog.PACKS.first { it.id == resource.id }
                withContext(ioDispatcher) { models.download(pack, onProgress) }
                pipelines.release()
            }
            MangaResourceKind.ACCELERATOR -> {
                val accelerator = resource.accelerators.first()
                downloadAccelerator(accelerator, onProgress, onPreparing)
                selectAccelerators(models.selectedAccelerators() + accelerator)
            }
            MangaResourceKind.LEFTOVER -> Unit
        }
    }

    /** Deletes [resource] from this device; chapters that need it offer the download again. */
    suspend fun removeResource(resource: MangaResource) {
        val p = platform ?: return
        when (resource.kind) {
            MangaResourceKind.RECOGNITION, MangaResourceKind.LANGUAGE, MangaResourceKind.FONT -> {
                val pack = MangaModelCatalog.PACKS.first { it.id == resource.id }
                pipelines.whileIdle { withContext(ioDispatcher) { models.remove(pack) } }
            }
            MangaResourceKind.INPAINT -> removeInpaint()
            MangaResourceKind.ACCELERATOR -> removeAccelerator(resource.accelerators.first())
            MangaResourceKind.LEFTOVER -> {
                pipelines.whileIdle { withContext(ioDispatcher) { models.removeLeftover(resource.id, inUse(p)) } }
            }
        }
    }

    /** The packs and runtimes this device's accelerators use, which are not left over even when outside the catalog. */
    private fun inUse(p: MangaPlatform): Set<String> =
        p.accelerators().flatMap { support -> support.packs.map { it.id } + listOfNotNull(support.runtime?.let { "runtime/${it.id}" }) }.toSet()

    /** Downloads the LaMa pack; chapters started from then on use it. */
    suspend fun downloadInpaint(onProgress: (received: Long, total: Long) -> Unit) {
        withContext(ioDispatcher) { models.download(MangaModelCatalog.INPAINT, onProgress) }
        pipelines.release()
    }

    /** Removes the LaMa pack; text over artwork stays as drawn again. */
    suspend fun removeInpaint() {
        pipelines.whileIdle { withContext(ioDispatcher) { models.remove(MangaModelCatalog.INPAINT) } }
    }

    /**
     * A pipeline for the chosen detectors this device has everything for (the CPU's when none is
     * left), with [tuning] or the device's own.
     */
    fun newPipeline(tuning: MangaTuning?, readVocab: (String) -> List<String>): MangaPipeline {
        val p = platform ?: throw MangaModelException("Manga translation is not available on this device")
        val paths = pathsWithRuntimes(p) ?: throw MangaModelException("The manga models are not installed")
        val chosen = models.selectedAccelerators()
        val supports = acceleratorStates().filter { it.support.accelerator in chosen && it.installed }.map { it.support }
        return MangaPipeline(p, paths, tuning ?: MangaTuning.of(p.profile(), models.cpuMode()), supports, readVocab)
    }
}
