package dev.shinsou.kmp.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One app process per data directory. Windows and Linux start a second process when the app is
 * launched again, and with the window hidden for background downloads the first one is easy to
 * forget; two processes on one database would corrupt it. The second launch instead asks the
 * first to show its window (passing on its arguments, e.g. a deep link) and exits.
 * macOS needs none of this: LaunchServices reactivates the running app.
 */
internal class DesktopSingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
) : AutoCloseable {
    // Unbounded: a launch that arrives before the window collects is kept, and none is dropped.
    private val pending = Channel<List<String>>(Channel.UNLIMITED)

    /** Arguments of each later launch, delivered once; the window should come back for every one. */
    val activations: Flow<List<String>> = pending.receiveAsFlow()

    private fun listen() {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                runCatching { socket.use(::receive) }
            }
        }, "shinsou-single-instance").apply { isDaemon = true }.start()
    }

    private fun receive(socket: Socket) {
        socket.soTimeout = RECEIVE_TIMEOUT_MS
        val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        val presented = reader.readLine() ?: return
        // Only a launch that could read our data directory knows the token.
        if (!MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) return
        // The sender ends its half of the connection after the last argument.
        val arguments = generateSequence { reader.readLine() }
            .take(MAX_ARGUMENTS)
            .filter { it.length <= MAX_ARGUMENT_LENGTH }
            .toList()
        if (!pending.trySend(arguments).isSuccess) return
        // Acknowledged only once queued, so the new launch exits only when it was really handed over.
        socket.getOutputStream().apply {
            write(ACK.toByteArray(StandardCharsets.UTF_8))
            flush()
        }
    }

    override fun close() {
        pending.close()
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val LOCK_FILE = "instance.lock"
        private const val ENDPOINT_FILE = "instance.endpoint"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val RECEIVE_TIMEOUT_MS = 2_000
        private const val ENDPOINT_WAIT_MS = 10_000L
        private const val MAX_ARGUMENTS = 32
        private const val MAX_ARGUMENT_LENGTH = 8 * 1024
        private const val ACK = "ok\n"

        /** Held for the life of the process; a released reference must not drop the lock. */
        @Volatile
        private var current: DesktopSingleInstance? = null

        /** Outcome of [acquire]. */
        sealed interface Launch {
            /** This process is the instance. */
            data class Primary(val instance: DesktopSingleInstance) : Launch

            /** The running instance confirmed it received the arguments; this process exits. */
            data object HandedOff : Launch

            /**
             * Another process holds the data directory but did not answer. Starting anyway would
             * open the database twice, so the caller tells the user and exits.
             */
            data object RunningButUnreachable : Launch
        }

        fun acquire(root: Path, arguments: List<String>): Launch {
            Files.createDirectories(root)
            val channel = FileChannel.open(
                root.resolve(LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return if (signalRunningInstance(root, arguments)) Launch.HandedOff else Launch.RunningButUnreachable
            }
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            writeEndpoint(root, server.localPort, token)
            val instance = DesktopSingleInstance(channel, lock, server, token)
            current = instance
            instance.listen()
            return Launch.Primary(instance)
        }

        private fun writeEndpoint(root: Path, port: Int, token: String) {
            val target = root.resolve(ENDPOINT_FILE)
            val temporary = root.resolve("$ENDPOINT_FILE.tmp")
            Files.writeString(temporary, "$port\n$token\n", StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** The running instance may still be starting, so its endpoint is retried for a while. */
        private fun signalRunningInstance(root: Path, arguments: List<String>): Boolean {
            val deadline = System.currentTimeMillis() + ENDPOINT_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (runCatching { send(root, arguments) }.getOrDefault(false)) return true
                Thread.sleep(250)
            }
            return false
        }

        private fun send(root: Path, arguments: List<String>): Boolean {
            val lines = Files.readAllLines(root.resolve(ENDPOINT_FILE), StandardCharsets.UTF_8)
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
            val token = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
                val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                writer.write(token)
                writer.write("\n")
                arguments.filter { '\n' !in it && '\r' !in it }.take(MAX_ARGUMENTS).forEach {
                    writer.write(it)
                    writer.write("\n")
                }
                writer.flush()
                socket.shutdownOutput()
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                val reply = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                return reply == ACK.trim()
            }
        }
    }
}
