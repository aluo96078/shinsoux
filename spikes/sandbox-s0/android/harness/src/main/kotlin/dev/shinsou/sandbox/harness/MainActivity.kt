package dev.shinsou.sandbox.harness

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import dev.shinsou.sandbox.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * S0 on-device harness. Start with:  adb shell am start -n dev.shinsou.sandbox.harness/.MainActivity
 * Optional extras: --es strategy PIPE|SHARED_MEMORY. Results go to logcat, tag SSC-S0, and the screen.
 */
class MainActivity : Activity() {
    private lateinit var view: TextView
    private fun out(s: String) { Log.i(TAG, s); runOnUiThread { view.append(s + "\n") } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        view = TextView(this).apply { textSize = 11f; setPadding(16, 16, 16, 16) }
        setContentView(ScrollView(this).apply { addView(view) })
        val strategy = PayloadCodec.Strategy.valueOf(intent.getStringExtra("strategy") ?: "PIPE")
        thread { runCatching { run(strategy) }.onFailure { out("HARNESS ERROR: $it"); Log.e(TAG, "failed", it) } }
    }

    private fun bootstrap() = assets.open("bootstrap.js").readBytes()

    private val script = """
        var source={
          ok:function(){return 'ok';},
          big:function(n){return 'y'.repeat(n);},
          pull:function(){return bridge.httpGet('https://x.test/big').length;},
          loop:function(){for(;;){}},
          fetch:async function(){var r=await bridge.net.fetch('https://x.test/a');return r.text();}
        };""".trimIndent().toByteArray()

    private class FakeHost(val bigBytes: Int) : HostBridgeHandler {
        override fun bridgeCall(method: String, argsJson: ByteArray): ByteArray =
            ("\"" + "z".repeat(bigBytes) + "\"").toByteArray()
        override fun asyncStart(opId: Int, requestJson: ByteArray, complete: (Boolean, ByteArray) -> Unit) =
            complete(true, "{\"status\":200,\"body\":\"hello\"}".toByteArray())
        override fun asyncCancel(opId: Int) {}
    }

    // max result 4 MiB = ADR-0002 §7 and the transport cap in PayloadCodec.
    private fun limits() = longArrayOf(64L shl 20, 1L shl 20, 5000, 10_000, 4096, 6, 64, 4L shl 20, 100_000, 512)

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0

    private fun run(strategy: PayloadCodec.Strategy) {
        out("== S0 harness, strategy=$strategy, host pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        val mgr = SandboxManager(this, strategy = strategy)
        val boot = bootstrap()

        // 1. Per-package isolation + cold start
        val clients = listOf("pkg.alpha", "pkg.beta").map { key ->
            val t0 = System.nanoTime()
            val c = mgr.acquire(key, FakeHost(1 shl 20), boot, script, limits())
            val r = c.invoke("ok", "[]".toByteArray()).get(30, TimeUnit.SECONDS)
            out("$key cold start (bind+load+first invoke): ${"%.1f".format(ms(t0))} ms status=${r.status} -> ${String(r.json)}")
            c
        }
        for (c in clients) {
            val st = c.stats()
            out("${c.packageKey}: sandbox pid=${st[0]} uid=${st[1]} (host pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}) artHeap=${st[2] shr 10} kB quickjs=${st[3] shr 10} kB")
            c.probe().lines().filter { it.isNotBlank() }.forEach { Log.i("SSC-S0-PROBE", "${c.packageKey} $it"); out("  probe $it") }
        }

        // 2. Large payload latency: sandbox -> host (result) and host -> sandbox (bridge reply)
        for (size in listOf(64 * 1024, 512 * 1024, 1 shl 20, (4 shl 20) - 16)) {
            val c = clients[0]
            val a = bench { c.invoke("big", "[$size]".toByteArray()).get(30, TimeUnit.SECONDS) }
            out("result $size B: median ${"%.2f".format(a)} ms")
        }
        // One byte over the cap must fail fast with LIMIT_RESULT, not leave the caller waiting.
        val tOver = System.nanoTime()
        val over = clients[0].invoke("big", "[${4 shl 20}]".toByteArray()).get(30, TimeUnit.SECONDS)
        out("result over 4 MiB -> status ${over.status} after ${"%.0f".format(ms(tOver))} ms (expect 6=LIMIT_RESULT)")
        val pull = bench { clients[0].invoke("pull", "[]".toByteArray()).get(30, TimeUnit.SECONDS) }
        out("bridge reply ~1 MiB host->sandbox: median ${"%.2f".format(pull)} ms")

        // 3. Async
        out("fetch -> " + String(clients[0].invoke("fetch", "[]".toByteArray()).get(10, TimeUnit.SECONDS).json))

        // 4. Memory
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        out("host PSS ${pss} kB (sandbox PSS: use `adb shell dumpsys meminfo` per README)")

        // 5. Timeout + crash isolation
        val t0 = System.nanoTime()
        val loop = clients[1].invoke("loop", "[]".toByteArray()).get(30, TimeUnit.SECONDS)
        out("loop -> status ${loop.status} after ${"%.0f".format(ms(t0))} ms (expect 4=LIMIT_TIME)")
        // linkToDeath: kill alpha, in-flight and later calls must fail fast with CRASHED while beta keeps working
        clients[0].crashForTest()
        Thread.sleep(500)
        val after = clients[0].invoke("ok", "[]".toByteArray()).get(10, TimeUnit.SECONDS)
        out("after kill: alpha status=${after.status} (expect ${SandboxResult.DISCONNECTED}/${SandboxResult.CRASHED}); manager blocked=${mgr.isBlocked("pkg.alpha")}")
        val beta = clients[1].invoke("ok", "[]".toByteArray()).get(10, TimeUnit.SECONDS)
        out("after kill: beta status=${beta.status} json=${String(beta.json)} (host still alive)")
        out("DONE (host alive). Kill a sandbox with: adb shell kill <pid>, then re-run the invoke.")
        mgr.shutdown()
    }

    private inline fun bench(n: Int = 15, block: () -> SandboxResult): Double {
        block()
        val t = DoubleArray(n) { val t0 = System.nanoTime(); val r = block(); check(r.status == 0) { "status ${r.status}" }; ms(t0) }
        t.sort(); return t[n / 2]
    }

    companion object { const val TAG = "SSC-S0" }
}
