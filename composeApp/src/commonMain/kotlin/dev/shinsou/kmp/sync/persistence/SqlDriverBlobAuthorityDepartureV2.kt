package dev.shinsou.kmp.sync.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.shinsou.kmp.sync.v2.validateLifecycleTenant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public data class BlobAuthorityDepartureResultV2(
    val transferRows: Int,
    val lifecycleRows: Int,
) {
    init {
        require(transferRows >= 0 && lifecycleRows >= 0)
    }
}

/** Atomically retires all body-plane state before the departed authority's keys are erased. */
public class SqlDriverBlobAuthorityDepartureV2(
    private val driver: SqlDriver,
) {
    private val mutex = Mutex()

    init {
        // Constructors also perform the fail-closed unscoped transfer migration. No authority
        // cleanup can accidentally leave an unreadable legacy table outside the transaction.
        SqlDriverBlobTransferJournalV2(driver)
        SqlDriverBlobLifecycleJournalV2(driver)
    }

    public suspend fun clearAuthority(
        instanceId: String,
        workspaceId: String,
    ): BlobAuthorityDepartureResultV2 = mutex.withLock {
        validateLifecycleTenant(instanceId, workspaceId)
        val transferRows = count(
            SqlDriverBlobTransferJournalV2.TABLE_NAME,
            instanceId,
            workspaceId,
        )
        val lifecycleRows = count(
            SqlDriverBlobLifecycleJournalV2.TABLE_NAME,
            instanceId,
            workspaceId,
        )
        driver.execute(null, "BEGIN IMMEDIATE", 0).await()
        try {
            delete(SqlDriverBlobTransferJournalV2.TABLE_NAME, instanceId, workspaceId)
            delete(SqlDriverBlobTransferJournalV2.CURSOR_TABLE, instanceId, workspaceId)
            delete(SqlDriverBlobLifecycleJournalV2.TABLE_NAME, instanceId, workspaceId)
            driver.execute(null, "COMMIT", 0).await()
        } catch (failure: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0).await() }
            throw failure
        }
        BlobAuthorityDepartureResultV2(transferRows, lifecycleRows)
    }

    private suspend fun count(table: String, instanceId: String, workspaceId: String): Int =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table WHERE instance_id = ? AND workspace_id = ?",
            { cursor ->
                check(cursor.next().value)
                QueryResult.Value(requireNotNull(cursor.getLong(0)).toInt())
            },
            2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()

    private suspend fun delete(table: String, instanceId: String, workspaceId: String) {
        driver.execute(
            null,
            "DELETE FROM $table WHERE instance_id = ? AND workspace_id = ?",
            2,
        ) {
            bindString(0, instanceId)
            bindString(1, workspaceId)
        }.await()
    }
}
