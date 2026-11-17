package dev.shinsou.kmp.logging

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LogTest {
    private val records = mutableListOf<Triple<LogLevel, String, Throwable?>>()
    private lateinit var previousLogger: Logger
    private lateinit var previousLevel: LogLevel

    @BeforeTest
    fun capture() {
        previousLogger = Log.logger
        previousLevel = Log.minimumLevel
        Log.logger = Logger { level, tag, message, error -> records += Triple(level, "$tag: $message", error) }
    }

    @AfterTest
    fun restore() {
        Log.logger = previousLogger
        Log.minimumLevel = previousLevel
    }

    @Test
    fun recordsBelowTheMinimumAreNotBuilt() {
        Log.minimumLevel = LogLevel.WARN
        var built = false
        Log.debug("t") { built = true; "x" }
        Log.info("t") { built = true; "x" }
        assertFalse(built)
        assertEquals(emptyList(), records)
    }

    @Test
    fun warningsAndErrorsReachTheLoggerWithTheirCause() {
        Log.minimumLevel = LogLevel.WARN
        val cause = IllegalStateException("boom")
        Log.warn("Queue", cause) { "page failed" }
        Log.error("Store") { "write failed" }
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), records.map { it.first })
        assertEquals("Queue: page failed", records[0].second)
        assertSame(cause, records[0].third)
    }

    @Test
    fun aFailingLoggerDoesNotThrow() {
        Log.logger = Logger { _, _, _, _ -> error("logger is broken") }
        Log.error("t") { "x" }
    }

    @Test
    fun theDefaultIsWarnings() {
        assertEquals(LogLevel.WARN, previousLevel)
    }
}
