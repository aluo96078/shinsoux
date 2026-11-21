package dev.shinsou.kmp.plugin

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

// R4-1 (docs/architecture/0006): the macOS WKWebView helper process, shared by the challenge
// dialog and the plugin transports; it was in ui.challenge, which plugin code may not import.

// R5-1: public for the challenge dialog (:ui, the app's desktop source set); the constructor that
// picks the OS, resources directory and candidates stays internal (tests in this module).
public class MacOsWebChallengeHelperLocator internal constructor(
    private val osName: String,
    private val resourcesDirectory: String?,
    private val developmentCandidates: List<Path>,
) {
    /** This host's OS, the packaged app's resources directory, then a development build's helper. */
    public constructor() : this(
        osName = System.getProperty("os.name").orEmpty(),
        resourcesDirectory = System.getProperty(WEB_CHALLENGE_RESOURCES_PROPERTY),
        developmentCandidates = defaultDevelopmentHelperCandidates(),
    )

    fun resolve(): Path {
        check(osName.lowercase().contains("mac")) {
            "The native WKWebView helper is available only on macOS"
        }
        val candidates = buildList {
            resourcesDirectory
                ?.takeIf(String::isNotBlank)
                ?.let { add(Path.of(it).resolve(WEB_CHALLENGE_HELPER_NAME)) }
            addAll(developmentCandidates)
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: throw IllegalStateException("The packaged WKWebView helper is missing")
    }

    fun prepareExecutableCopy(): Path {
        val source = resolve()
        val directory = Files.createTempDirectory("shinsou-wkwebview-")
        val target = directory.resolve(WEB_CHALLENGE_HELPER_NAME)
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            runCatching {
                Files.setPosixFilePermissions(
                    target,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }.getOrElse {
                check(target.toFile().setExecutable(true, true)) {
                    "The WKWebView helper is not executable"
                }
            }
            return target
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(target) }
            runCatching { Files.deleteIfExists(directory) }
            throw error
        }
    }
}

/** Keeps the launch command free of URLs, credentials, and cookies; those travel over stdin. */
public fun webChallengeProcessCommand(helper: Path): List<String> =
    listOf(helper.toAbsolutePath().toString())

public class BoundedUtf8LineReader(input: InputStream) {
    private val input = BufferedInputStream(input, IPC_READ_CHUNK_BYTES)
    private val chunk = ByteArray(IPC_READ_CHUNK_BYTES)
    private var position = 0
    private var limit = 0

    /** Returns a newline-delimited UTF-8 frame. EOF with a partial frame fails closed. */
    fun readLine(maxBytes: Int): String? {
        require(maxBytes > 0)
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 8_192))
        while (true) {
            if (position == limit) {
                limit = input.read(chunk)
                position = 0
                if (limit < 0) {
                    if (bytes.size() == 0) return null
                    throw EOFException("Truncated IPC frame")
                }
            }
            var newline = position
            while (newline < limit && chunk[newline] != '\n'.code.toByte()) newline++
            val count = newline - position
            if (bytes.size() > maxBytes - count) throw IOException("IPC frame exceeds safe limit")
            bytes.write(chunk, position, count)
            position = newline
            if (position < limit) {
                position++
                val framed = bytes.toByteArray().let { raw ->
                    if (raw.lastOrNull() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
                }
                return try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(framed))
                        .toString()
                } catch (error: Exception) {
                    throw IOException("Invalid UTF-8 IPC frame", error)
                }
            }
        }
    }

    private companion object {
        const val IPC_READ_CHUNK_BYTES: Int = 8 * 1_024
    }
}

private fun defaultDevelopmentHelperCandidates(): List<Path> {
    val workingDirectory = Path.of(System.getProperty("user.dir").orEmpty())
    // A test run of a module other than the app is told where the app built the helper.
    val explicit = System.getProperty(WEB_CHALLENGE_DEVELOPMENT_HELPER_PROPERTY)
        ?.takeIf(String::isNotBlank)
        ?.let { Path.of(it) }
    return listOfNotNull(
        explicit,
        workingDirectory.resolve("composeApp/build/generated/desktopAppResources/macos/$WEB_CHALLENGE_HELPER_NAME"),
        workingDirectory.resolve("build/generated/desktopAppResources/macos/$WEB_CHALLENGE_HELPER_NAME"),
    )
}

private const val WEB_CHALLENGE_HELPER_NAME = "shinsou-web-challenge"
private const val WEB_CHALLENGE_DEVELOPMENT_HELPER_PROPERTY = "shinsou.webChallengeHelper"
private const val WEB_CHALLENGE_RESOURCES_PROPERTY = "compose.application.resources.dir"
