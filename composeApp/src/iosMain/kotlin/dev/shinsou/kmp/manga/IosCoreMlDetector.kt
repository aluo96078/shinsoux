package dev.shinsou.kmp.manga

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.CoreML.MLComputeUnitsCPUAndGPU
import platform.CoreML.MLComputeUnitsCPUAndNeuralEngine
import platform.CoreML.MLComputeUnitsCPUOnly
import platform.CoreML.MLDictionaryFeatureProvider
import platform.CoreML.MLFeatureValue
import platform.CoreML.MLModel
import platform.CoreML.MLModelConfiguration
import platform.CoreML.MLMultiArray
import platform.CoreML.MLMultiArrayDataTypeFloat16
import platform.CoreML.MLMultiArrayDataTypeFloat32
import platform.CoreML.compileModelAtURL
import platform.CoreML.create
import platform.CoreML.objectAtIndexedSubscript
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

/**
 * The comic text and bubble detector on the GPU (Core ML, `vision-coreml-v1`; see
 * docs/research/manga-gpu-npu.md): RT-DETRv2 R50 in fp16 at 640×640. On the GPU it matches the
 * full-precision model (the Neural Engine's fp16 moves boxes), and it is four times as fast as
 * the CPU's detector on an iPhone 17 Pro Max.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosCoreMlDetector(private val ops: VisionOps, packagePath: String, private val units: Units = Units.GPU) : PageDetector {
    /** Where Core ML runs it; the Neural Engine's fp16 moves boxes, so its pages are cached apart. */
    enum class Units(val id: String) { GPU(ID), NEURAL_ENGINE("$ID-ane"), CPU("$ID-cpu") }

    override val id: String get() = units.id

    private val model: MLModel
    private val input: MLMultiArray = MLMultiArray.create(shape = listOf(1, 3, SIZE, SIZE), dataType = MLMultiArrayDataTypeFloat32, error = null)
        ?: throw MangaModelException("Core ML input could not be made")
    private val features = MLDictionaryFeatureProvider(dictionary = mapOf<Any?, Any?>("pixels" to MLFeatureValue.featureValueWithMultiArray(input)), error = null)
        ?: throw MangaModelException("Core ML input could not be made")

    init {
        // compiled on this device (about a tenth of a second); the compiled copy is temporary
        val compiled = MLModel.compileModelAtURL(NSURL.fileURLWithPath(packagePath), null)
            ?: throw MangaModelException("The Core ML detector could not be compiled")
        val config = MLModelConfiguration().apply {
            computeUnits = when (units) {
                Units.GPU -> MLComputeUnitsCPUAndGPU
                Units.NEURAL_ENGINE -> MLComputeUnitsCPUAndNeuralEngine
                Units.CPU -> MLComputeUnitsCPUOnly
            }
        }
        model = MLModel.modelWithContentsOfURL(compiled, config, null)
            ?: throw MangaModelException("The Core ML detector could not be loaded")
        NSFileManager.defaultManager.removeItemAtURL(compiled, null)
        // A GPU that cannot run the model returns zeros (the simulator's does): such a detector
        // would find nothing on every page, so it is refused and the CPU's detector used instead.
        if (scoresOf(RgbImage(SIZE, SIZE, ByteArray(SIZE * SIZE * 3) { 0x80.toByte() })).first.all { it == 0f }) {
            throw MangaModelException("The Core ML detector returns nothing on this device")
        }
    }

    override fun detect(page: RgbImage): List<Detection> {
        val (scores, boxes) = scoresOf(ops.resize(page, SIZE, SIZE))
        return MangaDetector.fromQueries(scores, boxes, page.width, page.height)
    }

    /** The model's scores and boxes for a 640 by 640 page. */
    private fun scoresOf(small: RgbImage): Pair<FloatArray, FloatArray> {
        val plane = SIZE * SIZE
        val data = input.dataPointer!!.reinterpret<FloatVar>()
        for (i in 0 until plane) {
            data[i] = small.r(i) / 255f; data[plane + i] = small.g(i) / 255f; data[2 * plane + i] = small.b(i) / 255f
        }
        val out = model.predictionFromFeatures(features, null) ?: throw MangaModelException("Core ML detection failed")
        val scores = floats(out.featureValueForName("scores")?.multiArrayValue ?: throw MangaModelException("no scores"))
        val boxes = floats(out.featureValueForName("boxes")?.multiArrayValue ?: throw MangaModelException("no boxes"))
        return scores to boxes
    }

    /**
     * An output as packed floats in row order, whether Core ML kept it in fp32 or fp16, and
     * following its strides: the GPU pads rows (a row of three scores may take four places).
     */
    private fun floats(array: MLMultiArray): FloatArray {
        val shape = array.shape.map { (it as platform.Foundation.NSNumber).intValue }
        val strides = array.strides.map { (it as platform.Foundation.NSNumber).intValue }
        val n = shape.fold(1) { a, b -> a * b }
        val offsets = IntArray(n)
        val index = IntArray(shape.size)
        for (i in 0 until n) {
            var offset = 0
            for (d in shape.indices) offset += index[d] * strides[d]
            offsets[i] = offset
            var d = shape.size - 1
            while (d >= 0) { index[d]++; if (index[d] < shape[d]) break; index[d] = 0; d-- }
        }
        return when (array.dataType) {
            MLMultiArrayDataTypeFloat32 -> array.dataPointer!!.reinterpret<FloatVar>().let { p -> FloatArray(n) { p[offsets[it]] } }
            MLMultiArrayDataTypeFloat16 -> array.dataPointer!!.reinterpret<UShortVar>().let { p -> FloatArray(n) { halfToFloat(p[offsets[it]].toInt()) } }
            else -> FloatArray(n) { array.objectAtIndexedSubscript(it.toLong()).floatValue }
        }
    }

    internal companion object {
        const val ID: String = "coreml-r50"
        const val SIZE: Int = 640

        fun halfToFloat(h: Int): Float {
            val sign = (h shr 15) and 1; val exp = (h shr 10) and 0x1f; val mantissa = h and 0x3ff
            val bits = when {
                exp == 0 && mantissa == 0 -> sign shl 31
                exp == 0 -> { // subnormal
                    var e = 0; var m = mantissa
                    while (m and 0x400 == 0) { m = m shl 1; e-- }
                    (sign shl 31) or ((e + 127 - 14) shl 23) or ((m and 0x3ff) shl 13)
                }
                exp == 0x1f -> (sign shl 31) or (0xff shl 23) or (mantissa shl 13)
                else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mantissa shl 13)
            }
            return Float.fromBits(bits)
        }
    }
}
