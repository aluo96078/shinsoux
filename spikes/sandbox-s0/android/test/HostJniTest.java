import dev.shinsou.sandbox.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** macOS/JVM test of the JNI layer: callbacks, status propagation, async re-entry, cancel, frames. */
public class HostJniTest {
    static int failures;
    static void check(boolean c, String m) { if (!c) { failures++; System.out.println("  FAIL " + m); } }
    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String s(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    static class Host implements NativeHost {
        NativeCore core;
        final List<Integer> started = new ArrayList<>();
        int delivered, cancelled, logs, bridgeCalls;
        String lastMethod, lastArgs;
        public long nowMs() { return System.nanoTime() / 1_000_000; }
        public byte[] bridgeCall(String m, byte[] a) {
            bridgeCalls++; lastMethod = m; lastArgs = s(a);
            if (m.equals("httpGet") && !s(a).contains("throws")) return b("\"<div class=\\\"item\\\"><a href=\\\"/m/1\\\">One</a></div><div class=\\\"item\\\"><a href=\\\"/m/2\\\">Two</a></div>\"");
            if (s(a).contains("throws")) throw new RuntimeException("host failure must not reach JS");
            return b("null");
        }
        public int asyncStart(int op, byte[] r) { started.add(op); return 0; }
        public void asyncCancel(int op) { cancelled++; }
        public int asyncWait(long deadline) {
            int n = 0;
            while (delivered < started.size()) {
                int op = started.get(delivered++);
                if (core.deliverAsync(op, true, b("{\"status\":200,\"body\":\"op-" + op + "\"}")) == 0) n++;
            }
            return n;
        }
        public void log(byte[] m) { logs++; }
    }

    static final String SCRIPT =
        "var source={ok:function(){return 'ok';},"
      + " parse:function(){var d=Jsoup.parse(bridge.httpGet('https://x.test/'),'https://x.test/');return d.select('.item a').map(function(a){return a.text();});},"
      + " echo:function(a,b){return [a,b];},"
      + " loop:function(){for(;;){}},"
      + " thrower:function(){return bridge.call2 ? 1 : (function(){try{return bridge.log('x');}catch(e){return 'caught';}})();},"
      + " fetchMany:async function(){var r=await Promise.all([1,2,3].map(function(i){return bridge.net.fetch('https://x.test/'+i);}));return r.map(function(x){return x.text();});},"
      + " leak:function(){bridge.net.fetch('https://x.test/late');return 'returned';}};";

    public static void main(String[] a) throws Exception {
        NativeCore.loadLibrary();
        byte[] boot = Files.readAllBytes(Paths.get(a[0]));
        Host h = new Host();
        NativeCore.Limits lim = new NativeCore.Limits();
        lim.invokeTimeoutMs = 300;
        try (NativeCore core = new NativeCore(h, lim)) {
            h.core = core;
            check(core.init(boot, b(SCRIPT)) == 0, "init");

            NativeCore.Result r = core.invoke("parse", b("[]"));
            System.out.println("parse -> " + r.status + " " + s(r.json));
            check(r.status == 0 && s(r.json).equals("[\"One\",\"Two\"]"), "parse result");
            check("httpGet".equals(h.lastMethod) && h.lastArgs.contains("x.test"), "bridge args reach host");

            r = core.invoke("echo", b("[1,{\"k\":\"v\"}]"));
            check(r.status == 0 && s(r.json).equals("[1,{\"k\":\"v\"}]"), "args round trip: " + s(r.json));

            r = core.invoke("missing", b("[]"));
            check(r.status == 8 && r.json.length == 0, "missing hook status " + r.status);

            long t0 = System.nanoTime();
            r = core.invoke("loop", b("[]"));
            System.out.println("loop -> " + r.status + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            check(r.status == 4, "time limit");

            Thread t = new Thread(() -> { try { Thread.sleep(50); } catch (InterruptedException e) {} core.cancel(); });
            lim.invokeTimeoutMs = 300;
            t.start();
            r = core.invoke("loop", b("[]"));
            t.join();
            check(r.status == 4 || r.status == 7, "cancel/timeout status " + r.status);

            r = core.invoke("fetchMany", b("[]"));
            System.out.println("fetchMany -> " + r.status + " " + s(r.json));
            check(r.status == 0 && s(r.json).equals("[\"op-1\",\"op-2\",\"op-3\"]".replace("op-1", "op-" + h.started.get(0)).replace("op-2", "op-" + h.started.get(1)).replace("op-3", "op-" + h.started.get(2))), "async results re-enter via JNI");

            h.cancelled = 0;
            int before = h.started.size();
            r = core.invoke("leak", b("[]"));
            check(r.status == 0 && h.cancelled == 1, "pending op cancelled at invocation end: " + h.cancelled);
            check(core.deliverAsync(h.started.get(before), true, b("{}")) == -1, "late result dropped");

            r = core.invoke("ok", b("[]"));
            check(r.status == 0 && s(r.json).equals("\"ok\""), "reusable");
            check(core.memoryUsed() > 0, "memory accounting");
        }

        // Host exception in a callback must become a null bridge result, not a JVM crash.
        try (NativeCore core = new NativeCore(h, new NativeCore.Limits())) {
            h.core = core;
            check(core.init(boot, b("var source={t:function(){return bridge.httpGet('throws');}};")) == 0, "init2");
            NativeCore.Result r = core.invoke("t", b("[]"));
            System.out.println("host exception -> " + r.status + " " + s(r.json));
            check(r.status == 0 && s(r.json).equals("null"), "survives host exception -> null");
        }

        // Frame codec.
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] big = new byte[3 * FrameCodec.MAX_FRAME + 17];
        new Random(1).nextBytes(big);
        FrameCodec.write(o, 7, 42, big);
        FrameCodec.write(o, 1, 43, new byte[0]);
        InputStream in = new ByteArrayInputStream(o.toByteArray());
        FrameCodec.Message m = FrameCodec.read(in, 8L << 20);
        check(m.type == 7 && m.id == 42 && Arrays.equals(m.payload, big), "chunked round trip");
        m = FrameCodec.read(in, 8L << 20);
        check(m.type == 1 && m.id == 43 && m.payload.length == 0, "empty frame");
        check(FrameCodec.read(in, 8L << 20) == null, "clean EOF");
        try { FrameCodec.read(new ByteArrayInputStream(o.toByteArray()), 1 << 20); check(false, "total cap"); }
        catch (FrameCodec.ProtocolException e) { }
        try { FrameCodec.read(new ByteArrayInputStream(new byte[]{0x7f, 0, 0, 0}), 1 << 20); check(false, "bad len"); }
        catch (FrameCodec.ProtocolException e) { }
        try { FrameCodec.read(new ByteArrayInputStream(Arrays.copyOf(o.toByteArray(), 100)), 8L << 20); check(false, "truncated"); }
        catch (IOException e) { }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
