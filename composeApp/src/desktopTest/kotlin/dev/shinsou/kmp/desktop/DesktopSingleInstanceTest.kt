package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.desktop.DesktopSingleInstance.Companion.Launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

class DesktopSingleInstanceTest {
    private fun primary(root: Path) = assertIs<Launch.Primary>(DesktopSingleInstance.acquire(root, emptyList())).instance

    @Test
    fun aSecondLaunchHandsItsArgumentsToTheFirstAndDoesNotStart() = runBlocking {
        val root = Files.createTempDirectory("shinsou-instance")
        val first = primary(root)
        try {
            assertEquals(Launch.HandedOff, DesktopSingleInstance.acquire(root, listOf("shinsou://library", "--flag")))
            assertEquals(listOf("shinsou://library", "--flag"), withTimeout(10_000) { first.activations.first() })
        } finally {
            first.close()
        }
        // Once the first instance is gone, the next launch becomes the instance.
        primary(root).close()
    }

    @Test
    fun aBurstOfLaunchesIsDeliveredInFull() = runBlocking {
        val root = Files.createTempDirectory("shinsou-instance")
        val first = primary(root)
        try {
            repeat(20) { assertEquals(Launch.HandedOff, DesktopSingleInstance.acquire(root, listOf("link-$it"))) }
            val received = withTimeout(10_000) { first.activations.take(20).toList() }
            assertEquals((0 until 20).map { listOf("link-$it") }, received)
        } finally {
            first.close()
        }
    }

    @Test
    fun aHolderThatDoesNotAnswerIsReportedNotTreatedAsAHandoff() {
        val root = Files.createTempDirectory("shinsou-instance")
        // Another process's lock, with an endpoint that accepts but never acknowledges.
        val silent = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val drain = thread(isDaemon = true) {
            runCatching { while (true) silent.accept().use { it.getInputStream().readBytes() } }
        }
        Files.writeString(root.resolve("instance.endpoint"), "${silent.localPort}\nsome-token\n")
        val holderLock = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp", System.getProperty("java.class.path"),
            LockHolder::class.java.name, root.resolve("instance.lock").toString(),
        ).start()
        try {
            holderLock.inputStream.bufferedReader().readLine() // "locked"
            assertEquals(Launch.RunningButUnreachable, DesktopSingleInstance.acquire(root, listOf("x")))
        } finally {
            holderLock.destroy()
            silent.close()
            drain.interrupt()
        }
    }

    @Test
    fun aConnectionWithoutTheTokenIsIgnored() = runBlocking {
        val root = Files.createTempDirectory("shinsou-instance")
        val first = primary(root)
        try {
            val port = Files.readAllLines(root.resolve("instance.endpoint"))[0].trim().toInt()
            val received = async { withTimeoutOrNull(1_500) { first.activations.first() } }
            yield()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write("not-the-token\nshinsou://library\n".toByteArray())
                socket.shutdownOutput()
                assertNull(socket.getInputStream().bufferedReader().readLine())
            }
            assertNull(received.await())
        } finally {
            first.close()
        }
    }
}

/** Holds the lock from a separate process, as a running app would. */
object LockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel.lock()
        println("locked")
        System.out.flush()
        Thread.sleep(60_000)
    }
}
