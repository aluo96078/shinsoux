package dev.shinsou.kmp.translation

import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.testing.TestOnlyApi
import dev.shinsou.kmp.translation.sql.TranslationMemoryDatabase

/**
 * The tests of higher modules reach these internal declarations of :feature-translation only
 * through here; see [TestOnlyApi]. The generated `TranslationMemory*` types are internal because
 * the iOS framework exports this module; its tests that need nothing above it use them directly.
 */
@TestOnlyApi
public object TranslationTestAccess {
    /** Creates the tables of the generated DDL (TranslationMemory.sq) in [driver]. */
    public fun createGeneratedTranslationMemorySchema(driver: SqlDriver) {
        TranslationMemoryDatabase.Schema.create(driver).value
    }
}
