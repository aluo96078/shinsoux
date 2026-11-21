package dev.shinsou.kmp.manga

import dev.shinsou.interop.onnx.ONNXTensorElementDataType
import dev.shinsou.interop.onnx.ORT_API_VERSION
import dev.shinsou.interop.onnx.ORT_ENABLE_ALL
import dev.shinsou.interop.onnx.OrtApi
import dev.shinsou.interop.onnx.OrtArenaAllocator
import dev.shinsou.interop.onnx.OrtGetApiBase
import dev.shinsou.interop.onnx.OrtLoggingLevel
import dev.shinsou.interop.onnx.OrtMemTypeDefault
import dev.shinsou.kmp.logging.Log
import kotlinx.cinterop.*
import platform.posix.memcpy
import cnames.structs.OrtEnv
import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession as OrtSessionHandle
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtStatus
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtValue

/**
 * ONNX Runtime on iOS through its C API (the OrtApi function table, ADR-0005 §4). Tensors made
 * here own a native copy of their data; values a run returns stay in ORT's memory until their
 * [OnnxOutputs] is closed, so the decoder's key/value state never round-trips through Kotlin.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosOnnxRuntime : OnnxRuntime {
    private val api: OrtApi = OrtGetApiBase()!!.pointed.GetApi!!.invoke(ORT_API_VERSION.convert())!!.pointed
    private val env: CPointer<OrtEnv>
    private val memInfo: CPointer<OrtMemoryInfo>

    init {
        memScoped {
            val e = alloc<CPointerVar<OrtEnv>>()
            check(api.CreateEnv!!(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "shinsou".cstr.ptr, e.ptr)); env = e.value!!
            // Nothing about the translation leaves the device, ONNX Runtime's usage events included.
            check(api.DisableTelemetryEvents!!(env))
            val m = alloc<CPointerVar<OrtMemoryInfo>>()
            check(api.CreateCpuMemoryInfo!!(OrtArenaAllocator, OrtMemTypeDefault, m.ptr)); memInfo = m.value!!
        }
    }

    private fun check(status: CPointer<OrtStatus>?) {
        if (status != null) {
            val message = api.GetErrorMessage!!(status)?.toKString().orEmpty()
            api.ReleaseStatus!!(status)
            throw MangaModelException("ONNX Runtime: $message")
        }
    }

    override fun open(path: String, threads: Int, variableShapes: Boolean, accelerator: Accelerator): OnnxSession {
        // CoreML first when asked for; any failure (or another accelerator) runs on the CPU
        if (accelerator == Accelerator.COREML) {
            runCatching { return open(path, threads, variableShapes, coreMl = true) }
                .onFailure { Log.warn("Manga", it) { "Opening the model with Core ML failed; using the CPU" } }
        }
        return open(path, threads, variableShapes, coreMl = false)
    }

    private fun open(path: String, threads: Int, variableShapes: Boolean, coreMl: Boolean): OnnxSession = memScoped {
        val options = alloc<CPointerVar<OrtSessionOptions>>()
        check(api.CreateSessionOptions!!(options.ptr))
        try {
            check(api.SetIntraOpNumThreads!!(options.value, threads))
            check(api.SetSessionGraphOptimizationLevel!!(options.value, ORT_ENABLE_ALL))
            if (variableShapes) { check(api.DisableCpuMemArena!!(options.value)); check(api.DisableMemPattern!!(options.value)) }
            if (coreMl) check(api.SessionOptionsAppendExecutionProvider!!(options.value, "CoreML".cstr.ptr, null, null, 0u))
            val session = alloc<CPointerVar<OrtSessionHandle>>()
            check(api.CreateSession!!(env, path.cstr.ptr, options.value, session.ptr))
            Session(session.value!!)
        } finally {
            api.ReleaseSessionOptions!!(options.value)
        }
    }

    override fun floats(data: FloatArray, shape: LongArray): OnnxValue {
        val bytes = data.size * 4
        val buffer = nativeHeap.allocArray<ByteVar>(maxOf(1, bytes))
        if (bytes > 0) data.usePinned { memcpy(buffer, it.addressOf(0), bytes.convert()) }
        return create(buffer, bytes, shape, ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
    }

    override fun longs(data: LongArray, shape: LongArray): OnnxValue {
        val bytes = data.size * 8
        val buffer = nativeHeap.allocArray<ByteVar>(maxOf(1, bytes))
        if (bytes > 0) data.usePinned { memcpy(buffer, it.addressOf(0), bytes.convert()) }
        return create(buffer, bytes, shape, ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64)
    }

    override fun bools(data: BooleanArray, shape: LongArray): OnnxValue {
        val buffer = nativeHeap.allocArray<ByteVar>(maxOf(1, data.size))
        data.forEachIndexed { i, b -> buffer[i] = if (b) 1 else 0 }
        return create(buffer, data.size, shape, ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL)
    }

    private fun create(buffer: CPointer<ByteVar>, bytes: Int, shape: LongArray, type: ONNXTensorElementDataType): OnnxValue = memScoped {
        val dims = allocArray<LongVar>(maxOf(1, shape.size)).also { d -> shape.forEachIndexed { i, v -> d[i] = v } }
        val value = alloc<CPointerVar<OrtValue>>()
        check(api.CreateTensorWithDataAsOrtValue!!(memInfo, buffer, bytes.convert(), dims, shape.size.convert(), type, value.ptr))
        Value(value.value!!, buffer)
    }

    private inner class Value(val handle: CPointer<OrtValue>, private val buffer: CPointer<ByteVar>?) : OnnxValue {
        override val shape: LongArray
            get() = memScoped {
                val info = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
                check(api.GetTensorTypeAndShape!!(handle, info.ptr))
                try {
                    val count = alloc<ULongVar>(); check(api.GetDimensionsCount!!(info.value, count.ptr))
                    val dims = allocArray<LongVar>(maxOf(1, count.value.toInt())); check(api.GetDimensions!!(info.value, dims, count.value))
                    LongArray(count.value.toInt()) { dims[it] }
                } finally {
                    api.ReleaseTensorTypeAndShapeInfo!!(info.value)
                }
            }

        private fun elements(): Int = shape.fold(1L) { a, b -> a * b }.toInt()

        private fun data(): CPointer<ByteVar>? = memScoped {
            val p = alloc<COpaquePointerVar>()
            check(api.GetTensorMutableData!!(handle, p.ptr))
            p.value?.reinterpret()
        }

        override fun floats(): FloatArray {
            val n = elements()
            if (n == 0) return FloatArray(0)
            val p = data()!!.reinterpret<FloatVar>()
            return FloatArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), p, (n * 4).convert()) } }
        }

        override fun longs(): LongArray {
            val n = elements()
            if (n == 0) return LongArray(0)
            val p = data()!!.reinterpret<LongVar>()
            return LongArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), p, (n * 8).convert()) } }
        }

        /** Values made by [floats]/[longs]/[bools] are freed here; a run's outputs by their result. */
        override fun close() {
            if (buffer != null) {
                api.ReleaseValue!!(handle)
                nativeHeap.free(buffer)
            }
        }

        fun release() = api.ReleaseValue!!(handle)
    }

    private inner class Session(private val session: CPointer<OrtSessionHandle>) : OnnxSession {
        override val inputNames: List<String> = names(input = true)
        override val outputNames: List<String> = names(input = false)

        private fun names(input: Boolean): List<String> = memScoped {
            val allocator = alloc<CPointerVar<dev.shinsou.interop.onnx.OrtAllocator>>()
            check(api.GetAllocatorWithDefaultOptions!!(allocator.ptr))
            val count = alloc<ULongVar>()
            check(if (input) api.SessionGetInputCount!!(session, count.ptr) else api.SessionGetOutputCount!!(session, count.ptr))
            (0uL until count.value).map { i ->
                val name = alloc<CPointerVar<ByteVar>>()
                check(if (input) api.SessionGetInputName!!(session, i, allocator.value, name.ptr) else api.SessionGetOutputName!!(session, i, allocator.value, name.ptr))
                val text = name.value!!.toKString()
                check(api.AllocatorFree!!(allocator.value, name.value))
                text
            }
        }

        override fun run(inputs: Map<String, OnnxValue>): OnnxOutputs = memScoped {
            val names = allocArray<CPointerVar<ByteVar>>(inputs.size)
            val values = allocArray<CPointerVar<OrtValue>>(inputs.size)
            inputs.entries.forEachIndexed { i, (name, value) ->
                names[i] = name.cstr.ptr
                values[i] = (value as Value).handle
            }
            val outNames = allocArray<CPointerVar<ByteVar>>(outputNames.size).also { a -> outputNames.forEachIndexed { i, n -> a[i] = n.cstr.ptr } }
            val outs = allocArray<CPointerVar<OrtValue>>(outputNames.size)
            check(api.Run!!(session, null, names, values, inputs.size.convert(), outNames, outputNames.size.convert(), outs))
            val result = outputNames.mapIndexed { i, name -> name to Value(outs[i]!!, buffer = null) }.toMap()
            object : OnnxOutputs {
                override fun get(name: String): OnnxValue = result.getValue(name)
                override fun close() = result.values.forEach { it.release() }
            }
        }

        override fun close() = api.ReleaseSession!!(session)
    }
}
