package dev.shinsou.kmp.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** ONNX Runtime through its Java API, the same on Android (`onnxruntime-android`) and desktop. */
public class JvmOnnxRuntime : OnnxRuntime {
    private val env = OrtEnvironment.getEnvironment()

    override fun open(path: String, threads: Int, variableShapes: Boolean, accelerator: Accelerator): OnnxSession {
        fun options(withAccelerator: Boolean) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (variableShapes) { setCPUArenaAllocator(false); setMemoryPatternOptimization(false) }
            if (withAccelerator) when (accelerator) {
                Accelerator.CPU -> Unit
                Accelerator.COREML -> addCoreML()
                Accelerator.NNAPI -> addNnapi()
                Accelerator.XNNPACK -> addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
        }
        if (accelerator != Accelerator.CPU) {
            runCatching { return Session(env.createSession(path, options(withAccelerator = true))) }
        }
        return Session(env.createSession(path, options(withAccelerator = false)))
    }

    override fun floats(data: FloatArray, shape: LongArray): OnnxValue =
        Value(OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape), owned = true)

    override fun longs(data: LongArray, shape: LongArray): OnnxValue =
        Value(OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape), owned = true)

    override fun bools(data: BooleanArray, shape: LongArray): OnnxValue =
        // a one-element boolean flag (the decoder's cache branch) is all the pipeline passes
        Value(if (shape.isEmpty() || (shape.size == 1 && shape[0] == 1L)) OnnxTensor.createTensor(env, booleanArrayOf(data[0])) else error("only flag tensors"), owned = true)

    private class Value(val tensor: OnnxTensor, private val owned: Boolean) : OnnxValue {
        override val shape: LongArray get() = tensor.info.shape

        override fun floats(): FloatArray {
            val b = tensor.floatBuffer
            return FloatArray(b.remaining()).also { b.get(it) }
        }

        override fun longs(): LongArray {
            val b = tensor.longBuffer
            return LongArray(b.remaining()).also { b.get(it) }
        }

        override fun close() {
            if (owned) tensor.close()
        }
    }

    private class Session(private val session: OrtSession) : OnnxSession {
        override val inputNames: List<String> = session.inputNames.toList()
        override val outputNames: List<String> = session.outputNames.toList()

        override fun run(inputs: Map<String, OnnxValue>): OnnxOutputs {
            val result = session.run(inputs.mapValues { (it.value as Value).tensor })
            return object : OnnxOutputs {
                override fun get(name: String): OnnxValue = Value(result.get(name).get() as OnnxTensor, owned = false)
                override fun close() = result.close()
            }
        }

        override fun close() = session.close()
    }
}
