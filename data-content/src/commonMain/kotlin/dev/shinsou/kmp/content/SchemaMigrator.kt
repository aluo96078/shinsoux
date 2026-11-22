package dev.shinsou.kmp.content

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * The database was written by a newer Shinsou X than this one ([found] > [supported]): opening it
 * could lose what the newer version stored, so the app asks to be updated instead of starting.
 */
public class SchemaTooNewException(
    public val component: String,
    public val found: Long,
    public val supported: Long,
) : IllegalStateException("The $component data is format $found; this version reads up to $supported")

/**
 * One group of tables with its own format version (R3-1, docs/architecture/0006).
 *
 * [create] writes the newest tables into an empty database. [steps] upgrade an existing one: the
 * step under key `n` takes format `n - 1` to `n`. A database that predates the version record is
 * given [unrecordedVersion] (`null`: it has none of these tables); its history is not certain, so
 * after its steps [create] runs as well, and both must be safe to run on tables that already have
 * their changes.
 * [rebuildWhenNewer]: derived data (a search index) in any other format, newer or older, is
 * dropped with [drop] and created again instead of being refused or upgraded; it has no [steps].
 */
@Suppress("LongParameterList") // A declaration: every parameter but the first three has a default.
public class SchemaComponent internal constructor(
    public val name: String,
    public val version: Long,
    public val create: (SqlDriver) -> Unit,
    public val steps: Map<Long, (SqlDriver) -> Unit> = emptyMap(),
    public val unrecordedVersion: (SqlDriver) -> Long?,
    internal val versionStore: SchemaVersionStore,
    internal val rebuildWhenNewer: Boolean = false,
    public val drop: (SqlDriver) -> Unit = {},
) {
    /**
     * A component of another module records its version in the shared table (R5-1: where the
     * version is kept stays this module's business).
     */
    public constructor(
        name: String,
        version: Long,
        create: (SqlDriver) -> Unit,
        steps: Map<Long, (SqlDriver) -> Unit> = emptyMap(),
        unrecordedVersion: (SqlDriver) -> Long?,
        rebuildWhenNewer: Boolean = false,
        drop: (SqlDriver) -> Unit = {},
    ) : this(name, version, create, steps, unrecordedVersion, SchemaVersionStore.Shared(name), rebuildWhenNewer, drop)

    init {
        require(version >= 1) { "Schema $name needs a version of 1 or more" }
        require(steps.keys.all { it in 2..version }) { "Schema $name has a step outside 2..$version" }
        require(!rebuildWhenNewer || steps.isEmpty()) { "Schema $name is rebuilt, not upgraded: it takes no steps" }
    }
}

/** Where a component records its format version. */
internal sealed interface SchemaVersionStore {
    fun read(driver: SqlDriver): Long?
    fun write(driver: SqlDriver, version: Long)

    /** A row of the shared `shinsou_schema_versions` table. */
    class Shared(private val component: String) : SchemaVersionStore {
        override fun read(driver: SqlDriver): Long? {
            ensureTable(driver)
            return driver.queryStatement(
                "SELECT version FROM $VERSIONS_TABLE WHERE component = ?",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                1,
            ) { bindString(0, component) }.value
        }

        override fun write(driver: SqlDriver, version: Long) {
            ensureTable(driver)
            driver.executeStatement("INSERT OR REPLACE INTO $VERSIONS_TABLE(component, version) VALUES (?, ?)", 2) {
                bindString(0, component)
                bindLong(1, version)
            }.value
        }

        private fun ensureTable(driver: SqlDriver) {
            driver.executeUncached(
                "CREATE TABLE IF NOT EXISTS $VERSIONS_TABLE(component TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL) WITHOUT ROWID",
                0,
            ).value
        }
    }

    /**
     * A singleton marker table a component had before the shared one (`singleton_id = 1`,
     * `format_version`): kept, so an older app still finds the version it wrote.
     */
    class Singleton(private val table: String) : SchemaVersionStore {
        override fun read(driver: SqlDriver): Long? {
            if (!tableExists(driver, table)) return null
            return driver.queryStatement(
                "SELECT format_version FROM $table WHERE singleton_id = 1",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                0,
            ).value
        }

        override fun write(driver: SqlDriver, version: Long) {
            driver.executeUncached(
                "CREATE TABLE IF NOT EXISTS $table(singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1), format_version INTEGER NOT NULL)",
                0,
            ).value
            driver.executeStatement("INSERT OR REPLACE INTO $table(singleton_id, format_version) VALUES (1, ?)", 1) {
                bindLong(0, version)
            }.value
        }
    }
}

/**
 * Brings each [SchemaComponent] to its version (R3-1): nothing when it is there already; the
 * missing steps in one transaction when it is older; [SchemaTooNewException] when it is newer,
 * unless the component is rebuildable.
 */
public object SchemaMigrator {
    public fun migrate(driver: SqlDriver, component: SchemaComponent) {
        val transactions = object : TransacterImpl(driver) {}
        transactions.transaction(noEnclosing = false) {
            val recorded = component.versionStore.read(driver)
            val stored = recorded ?: component.unrecordedVersion(driver)
            when {
                stored == null -> {
                    component.create(driver)
                    component.versionStore.write(driver, component.version)
                }
                // Derived data in any other format than this one, older included: there are no
                // steps for it, and recording the new version over the old tables would leave
                // them as they were.
                component.rebuildWhenNewer && stored != component.version -> {
                    component.drop(driver)
                    component.create(driver)
                    component.versionStore.write(driver, component.version)
                }
                stored <= component.version -> {
                    for (next in stored + 1..component.version) component.steps[next]?.invoke(driver)
                    if (recorded == null) component.create(driver)
                    if (recorded != component.version) component.versionStore.write(driver, component.version)
                }
                else -> throw SchemaTooNewException(component.name, stored, component.version)
            }
        }
    }

    /** The first of [components] this version cannot open, read without changing anything. */
    public fun tooNew(driver: SqlDriver, components: List<SchemaComponent>): SchemaTooNewException? =
        components.firstNotNullOfOrNull { component ->
            if (component.rebuildWhenNewer) return@firstNotNullOfOrNull null
            val stored = component.versionStore.readWithoutCreating(driver) ?: return@firstNotNullOfOrNull null
            if (stored > component.version) SchemaTooNewException(component.name, stored, component.version) else null
        }

    /**
     * The format each of [components] is in now, as one line: what a copy of the database was
     * taken of. Reads only.
     */
    public fun formats(driver: SqlDriver, components: List<SchemaComponent>): String =
        components.joinToString(",") { component ->
            val recorded = component.versionStore.readWithoutCreating(driver)
            "${component.name}=${recorded ?: "unrecorded:${component.unrecordedVersion(driver)}"}"
        }

    /**
     * Whether opening the stores will upgrade a table group of [components] that already holds
     * data: one older than its version, or one from before versions were recorded. Reads only.
     */
    public fun upgrades(driver: SqlDriver, components: List<SchemaComponent>): Boolean =
        components.any { component ->
            val recorded = component.versionStore.readWithoutCreating(driver)
            val stored = recorded ?: component.unrecordedVersion(driver)
            stored != null && (recorded == null || stored < component.version)
        }

    private fun SchemaVersionStore.readWithoutCreating(driver: SqlDriver): Long? = when (this) {
        is SchemaVersionStore.Shared -> if (tableExists(driver, VERSIONS_TABLE)) read(driver) else null
        is SchemaVersionStore.Singleton -> read(driver)
    }
}

public fun tableExists(driver: SqlDriver, table: String): Boolean =
    driver.queryStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        { cursor -> QueryResult.Value(cursor.next().value) },
        1,
    ) { bindString(0, table) }.value

public fun columnExists(driver: SqlDriver, table: String, column: String): Boolean =
    driver.queryStatement(
        "SELECT 1 FROM pragma_table_info(?) WHERE name = ?",
        { cursor -> QueryResult.Value(cursor.next().value) },
        2,
    ) {
        bindString(0, table)
        bindString(1, column)
    }.value

internal const val VERSIONS_TABLE = "shinsou_schema_versions"
