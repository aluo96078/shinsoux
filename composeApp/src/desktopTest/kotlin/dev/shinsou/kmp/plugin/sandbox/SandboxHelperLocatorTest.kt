package dev.shinsou.kmp.plugin.sandbox

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The packaged helper is found, copied out of the (non-executable) app resources and runs. */
class SandboxHelperLocatorTest {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    @Test
    fun aPackagedHelperRunsFromAPrivateExecutableCopy() = runBlocking<Unit> {
        val bin = sandboxHelperBinary() ?: run { println("SKIPPED: shinsou-sandbox not built"); return@runBlocking }
        if (!isMac) { println("SKIPPED: the packaged helper ships on macOS only"); return@runBlocking }
        // jpackage installs resources without execute permission.
        val resources = Files.createTempDirectory("shinsou-resources-")
        val packaged = resources.resolve("shinsou-sandbox")
        Files.copy(bin.toPath(), packaged)
        Files.setPosixFilePermissions(packaged, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        val locator = SandboxHelperLocator(resourcesDirectory = resources.toString(), overridePath = null, developmentCandidates = emptyList())
        val helper = locator.executable()
        assertNotEquals(packaged, helper)
        assertEquals(helper, locator.executable(), "copied once per run")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(helper.parent),
        )
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(helper))

        // It speaks the protocol: Init → Ready.
        val transport = DesktopSandboxTransportProvider { helper }.open("dev.example.locator") as DesktopSandboxTransport
        try {
            val limits = SandboxLimits(16L shl 20, 1L shl 20, 2_000, 2_000, 64, 6, 64, 1L shl 20, 100_000, 512)
            SandboxFrameCodec.encode(SandboxFrame.Init(1, SANDBOX_PROTOCOL_VERSION, 1, limits, "var source={};")).forEach { transport.send(it) }
            val reader = SandboxFrameReader(SandboxPeer.HOST)
            var frames = emptyList<SandboxFrame>()
            while (frames.isEmpty()) frames = reader.feed(checkNotNull(transport.receive()))
            assertTrue(frames.first() is SandboxFrame.Ready, "${frames.first()}")
        } finally {
            transport.close()
        }
    }

    @Test
    fun noHelperMeansNoSandboxNotAnUnconfinedFallback() {
        val empty = Files.createTempDirectory("shinsou-resources-")
        val locator = SandboxHelperLocator(resourcesDirectory = empty.toString(), overridePath = null, osName = "Mac OS X", developmentCandidates = emptyList())
        val error = assertFailsWith<IllegalStateException> { locator.executable() }
        assertTrue("missing" in error.message.orEmpty())
        assertFailsWith<IllegalStateException> { SandboxHelperLocator(overridePath = null, osName = "Windows 11").executable() }
    }

    @Test
    fun aPackagedAppIgnoresTheEnvironmentAndWorkingDirectoryOverrides() {
        if (!isMac) { println("SKIPPED: the packaged helper ships on macOS only"); return }
        val decoy = Files.createTempFile("decoy-", "").toString()
        val elsewhere = Files.createTempDirectory("build-output-").resolve("shinsou-sandbox").also { Files.writeString(it, "x") }
        // Packaged (a resources directory) without the helper: no override or build path may stand in.
        val packaged = SandboxHelperLocator(
            resourcesDirectory = Files.createTempDirectory("shinsou-resources-").toString(),
            overridePath = decoy,
            developmentCandidates = listOf(elsewhere),
        )
        assertFailsWith<IllegalStateException> { packaged.executable() }
        // A development run (no resources directory) does honour them.
        val development = SandboxHelperLocator(resourcesDirectory = null, overridePath = decoy, developmentCandidates = listOf(elsewhere))
        assertEquals(decoy, development.executable().toString())
    }
}
