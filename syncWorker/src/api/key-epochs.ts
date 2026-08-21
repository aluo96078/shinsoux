import type { D1Database } from "../runtime.ts";
import { all } from "../storage/d1.ts";

/**
 * Encrypted bodies keep depending on their current envelope epoch until re-wrap or final deletion.
 * Tombstoned/deleting bodies remain recoverable during their retention and GC windows.
 */
export const LIVE_BLOB_ENVELOPE_EPOCHS_SQL = `
  SELECT workspace_id, current_envelope_epoch AS key_epoch
  FROM encrypted_blob_manifests
  WHERE status IN ('committed', 'tombstoned', 'deleting')
`;

export async function loadRequiredWorkspaceKeyEpochs(
  db: D1Database,
  workspaceId: string,
): Promise<number[]> {
  const rows = await all<{ keyEpoch: number }>(db, `
    SELECT DISTINCT key_epoch AS keyEpoch FROM (
      SELECT active_key_epoch AS key_epoch FROM workspaces WHERE workspace_id = ?1
      UNION SELECT key_epoch FROM checkpoints WHERE workspace_id = ?1 AND status = 'stable'
      UNION SELECT key_epoch FROM events WHERE workspace_id = ?1
      UNION SELECT key_epoch FROM (
        ${LIVE_BLOB_ENVELOPE_EPOCHS_SQL}
      ) live_blob_epochs WHERE workspace_id = ?1
    ) ORDER BY key_epoch
  `, workspaceId);
  return rows.map((row) => row.keyEpoch);
}
