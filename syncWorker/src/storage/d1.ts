import { ApiError } from "../http.ts";
import type { D1Database, D1PreparedStatement, D1Result } from "../runtime.ts";

export function affectedRows(result: D1Result): number {
  return result.meta?.changes ?? 0;
}

export async function first<T>(
  db: D1Database,
  sql: string,
  ...bindings: unknown[]
): Promise<T | null> {
  return db.prepare(sql).bind(...bindings).first<T>();
}

export async function all<T>(
  db: D1Database,
  sql: string,
  ...bindings: unknown[]
): Promise<T[]> {
  const result = await db.prepare(sql).bind(...bindings).all<T>();
  if (!result.success) throw new ApiError(503, "database_unavailable");
  return result.results ?? [];
}

export async function run(
  db: D1Database,
  sql: string,
  ...bindings: unknown[]
): Promise<D1Result> {
  const result = await db.prepare(sql).bind(...bindings).run();
  if (!result.success) throw new ApiError(503, "database_unavailable");
  return result;
}

export async function returning<T>(
  db: D1Database,
  sql: string,
  ...bindings: unknown[]
): Promise<T | null> {
  const result = await db.prepare(sql).bind(...bindings).all<T>();
  if (!result.success) throw new ApiError(503, "database_unavailable");
  return result.results?.[0] ?? null;
}

export async function atomicBatch(
  db: D1Database,
  statements: D1PreparedStatement[],
): Promise<D1Result[]> {
  try {
    const results = await db.batch(statements);
    if (results.some((result) => !result.success)) throw new Error("D1 batch failed");
    return results;
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    const known = [
      "instance_already_initialized",
      "invite_invalid", "pairing_invalid", "pairing_incomplete_keyring",
      "user_quota_exceeded", "device_quota_exceeded",
      "device_attestation_missing", "device_identity_immutable", "device_attestation_immutable",
      "cannot_revoke_current_device", "device_revoke_forbidden",
      "rotation_lease_invalid", "rotation_recipient_changed", "rotation_envelopes_incomplete",
      "rotation_envelope_mismatch", "rotation_recovery_envelope_missing",
      "checkpoint_not_promotable", "recovery_challenge_invalid", "recovery_keyring_incomplete",
      "emergency_handoff_invalid", "emergency_handoff_device_invalid",
      "emergency_handoff_attestation_missing", "emergency_handoff_key_missing",
      "emergency_handoff_recovery_key_missing", "emergency_handoff_recovery_credential_missing",
      "blob_upload_session_invalid", "blob_chunk_receipt_invalid",
      "blob_manifest_commit_invalid", "blob_envelope_rewrap_invalid",
      "blob_rotation_envelopes_incomplete",
      "blob_tombstone_invalid", "blob_tombstone_ack_invalid",
      "blob_tombstone_revival_invalid",
      "blob_gc_claim_invalid", "blob_gc_completion_invalid",
    ].find((code) => message.includes(code));
    if (known) throw new ApiError(409, known);
    if (message.includes("UNIQUE constraint failed")) throw new ApiError(409, "conflict");
    throw new ApiError(503, "database_transaction_failed");
  }
}

export function blobToBytes(value: unknown): Uint8Array {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (Array.isArray(value) && value.every((entry) => Number.isInteger(entry))) {
    return Uint8Array.from(value as number[]);
  }
  throw new ApiError(500, "invalid_database_blob");
}

export async function expireEphemeralRows(db: D1Database, now: number): Promise<void> {
  await db.batch([
    db.prepare("UPDATE checkpoint_leases SET status = 'expired' WHERE status IN ('active', 'uploaded') AND expires_at <= ?1").bind(now),
    db.prepare("UPDATE blob_upload_sessions SET status = 'expired' WHERE status = 'active' AND expires_at <= ?1").bind(now),
    db.prepare("UPDATE key_rotations SET status = 'expired' WHERE status = 'leased' AND lease_expires_at <= ?1").bind(now),
    db.prepare("UPDATE workspaces SET pending_rotation_id = NULL WHERE pending_rotation_id IN (SELECT rotation_id FROM key_rotations WHERE status = 'expired')").bind(),
    db.prepare("UPDATE invites SET status = 'expired' WHERE status = 'active' AND expires_at <= ?1").bind(now),
    db.prepare("UPDATE pairing_sessions SET status = 'expired' WHERE status IN ('open', 'candidate') AND expires_at <= ?1").bind(now),
  ]);
}

export async function enforceRateWindow(
  db: D1Database,
  table: "edge_rate_windows" | "event_rate_windows",
  keyColumn: "scope_hash" | "scope_key",
  scope: string,
  now: number,
  limit: number,
  windowMillis = 60_000,
): Promise<void> {
  const windowStart = Math.floor(now / windowMillis) * windowMillis;
  const row = await returning<{ request_count?: number; event_count?: number }>(
    db,
    `INSERT INTO ${table}(${keyColumn}, window_start, ${table === "edge_rate_windows" ? "request_count" : "event_count"})
     VALUES (?1, ?2, 1)
     ON CONFLICT(${keyColumn}, window_start) DO UPDATE SET
       ${table === "edge_rate_windows" ? "request_count" : "event_count"} =
       ${table === "edge_rate_windows" ? "request_count" : "event_count"} + 1
     WHERE ${table === "edge_rate_windows" ? "request_count" : "event_count"} < ?3
     RETURNING ${table === "edge_rate_windows" ? "request_count" : "event_count"}`,
    scope,
    windowStart,
    limit,
  );
  if (!row) throw new ApiError(429, "rate_limited", "Too many requests", { retryAfterMillis: windowStart + windowMillis - now });
}
