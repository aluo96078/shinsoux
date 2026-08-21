import {
  ApiError,
  readBytes,
  readJson,
  requireInteger,
  requireObject,
  requireString,
  securityHeaders,
  utf8Bytes,
} from "../http.ts";
import type {
  CapabilityPrincipal,
  D1PreparedStatement,
  Env,
  R2Bucket,
} from "../runtime.ts";
import {
  bytesToHex,
  canonicalJson,
  decodeBase64Url,
  encodeBase64Url,
  randomUuid,
  sha256Base64Url,
  sha256Bytes,
  timingSafeEqual,
  verifyEd25519,
  versionedDomainSeparatedMessage,
} from "../crypto/base.ts";
import {
  LIMITS,
  blobChunkObjectKey,
  blobManifestObjectKey,
  requireSha256Base64Url,
  requireUuid,
} from "../protocol.ts";
import {
  all,
  atomicBatch,
  expireEphemeralRows,
  first,
  run,
} from "../storage/d1.ts";
import { requireBodyPlaneVersions } from "../versions.ts";

type JsonObject = Record<string, unknown>;

const ENVELOPE_SCHEMA_VERSION = 1;
const ENVELOPE_CIPHER_SUITE = "CHACHA20_POLY1305";
const AEAD_TAG_BYTES = 16;

function exactKeys(value: JsonObject, expected: readonly string[], name: string): void {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new ApiError(400, `invalid_${name}_fields`);
  }
}

function requireWriter(principal: CapabilityPrincipal): void {
  if (principal.role === "reader") throw new ApiError(403, "blob_body_write_forbidden");
}

function requireBodyV2(env: Env, value: JsonObject): void {
  const protocolVersion = requireInteger(value, "protocolVersion", 1, 0x7fff_ffff);
  const schemaVersion = requireInteger(value, "schemaVersion", 1, 0x7fff_ffff);
  requireBodyPlaneVersions(env, protocolVersion, schemaVersion);
}

function requireBodyPlaneAvailable(env: Env): R2Bucket {
  requireBodyPlaneVersions(env, 2, 2);
  if (!env.BLOBS) throw new ApiError(503, "blob_store_not_configured");
  return env.BLOBS;
}

function configuredSeconds(value: string | undefined, fallback: number, name: string): number {
  const encoded = value ?? String(fallback);
  if (!/^[1-9][0-9]*$/.test(encoded)) throw new ApiError(503, `${name}_invalid`);
  const parsed = Number(encoded);
  if (!Number.isSafeInteger(parsed) || parsed > 7 * 24 * 60 * 60) {
    throw new ApiError(503, `${name}_invalid`);
  }
  return parsed;
}

function nullableString(object: JsonObject, key: string): string | null {
  const value = object[key];
  if (value === null) return null;
  if (typeof value !== "string" || value.length === 0 || value.length > 4096) {
    throw new ApiError(400, `invalid_${key}`);
  }
  return value;
}

function canonicalFields(...values: string[]): Uint8Array {
  const parts: Uint8Array[] = [];
  let total = 0;
  for (const value of values) {
    const bytes = utf8Bytes(value);
    const length = new Uint8Array(4);
    const view = new DataView(length.buffer);
    view.setUint32(0, bytes.byteLength, false);
    parts.push(length, bytes);
    total += length.byteLength + bytes.byteLength;
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.byteLength;
  }
  return result;
}

interface DekEnvelope {
  schemaVersion: 1;
  blobId: string;
  keyEpoch: number;
  cipherSuite: typeof ENVELOPE_CIPHER_SUITE;
  nonceBase64Url: string;
  wrappedDekBase64Url: string;
  envelopeSha256Base64Url: string;
  previousEnvelopeSha256Base64Url: string | null;
}

async function parseDekEnvelope(value: unknown, expectedBlobId: string): Promise<DekEnvelope> {
  const envelope = requireObject(value, "initialDekEnvelope");
  exactKeys(envelope, [
    "schemaVersion", "blobId", "keyEpoch", "cipherSuite", "nonceBase64Url",
    "wrappedDekBase64Url", "envelopeSha256Base64Url", "previousEnvelopeSha256Base64Url",
  ], "dek_envelope");
  const schemaVersion = requireInteger(envelope, "schemaVersion", 1, 1);
  const blobId = requireUuid(requireString(envelope, "blobId", 36, 36), "envelope_blob_id");
  if (blobId !== expectedBlobId) throw new ApiError(400, "blob_envelope_binding_mismatch");
  const keyEpoch = requireInteger(envelope, "keyEpoch", 1, 0x7fff_ffff);
  const cipherSuite = requireString(envelope, "cipherSuite", 1, 64);
  if (cipherSuite !== ENVELOPE_CIPHER_SUITE) throw new ApiError(400, "unsupported_blob_envelope_cipher_suite");
  const nonceBase64Url = requireString(envelope, "nonceBase64Url", 1, 128);
  const wrappedDekBase64Url = requireString(envelope, "wrappedDekBase64Url", 1, 1024);
  const envelopeSha256Base64Url = requireSha256Base64Url(
    requireString(envelope, "envelopeSha256Base64Url", 1, 128),
    "envelope_sha256",
  );
  const previousEnvelopeSha256Base64Url = nullableString(
    envelope,
    "previousEnvelopeSha256Base64Url",
  );
  if (previousEnvelopeSha256Base64Url !== null) {
    requireSha256Base64Url(previousEnvelopeSha256Base64Url, "previous_envelope_sha256");
  }
  const nonce = decodeBase64Url(nonceBase64Url, "blob_envelope_nonce");
  const wrapped = decodeBase64Url(wrappedDekBase64Url, "wrapped_blob_dek");
  if (nonce.byteLength !== 12 || wrapped.byteLength !== 32 + AEAD_TAG_BYTES) {
    throw new ApiError(400, "invalid_blob_envelope_geometry");
  }
  const actualHash = await sha256Base64Url(canonicalFields(
    "shinsou-blob-dek-envelope-hash-v2",
    blobId,
    String(keyEpoch),
    nonceBase64Url,
    wrappedDekBase64Url,
    previousEnvelopeSha256Base64Url ?? "",
  ));
  if (actualHash !== envelopeSha256Base64Url) throw new ApiError(400, "blob_envelope_hash_mismatch");
  return {
    schemaVersion: schemaVersion as 1,
    blobId,
    keyEpoch,
    cipherSuite: ENVELOPE_CIPHER_SUITE,
    nonceBase64Url,
    wrappedDekBase64Url,
    envelopeSha256Base64Url,
    previousEnvelopeSha256Base64Url,
  };
}

interface PlannedChunk {
  index: number;
  ciphertextByteSize: number;
  ciphertextSha256Base64Url: string;
  ciphertextSha256Hex: string;
  r2Key: string;
}

async function parseChunkPlan(
  workspaceId: string,
  blobId: string,
  manifestId: string,
  value: unknown,
  chunkSizeBytes: number,
): Promise<{ chunks: PlannedChunk[]; planSha256: string; totalBytes: number }> {
  if (!Array.isArray(value) || value.length > LIMITS.blobChunks) {
    throw new ApiError(400, "invalid_chunks");
  }
  const chunks: PlannedChunk[] = [];
  let totalBytes = 0;
  value.forEach((entry, expectedIndex) => {
    const chunk = requireObject(entry, "chunk");
    exactKeys(chunk, ["index", "ciphertextByteSize", "ciphertextSha256Base64Url"], "chunk");
    const index = requireInteger(chunk, "index", 0, LIMITS.blobChunks - 1);
    if (index !== expectedIndex) throw new ApiError(400, "blob_chunk_plan_not_contiguous");
    const ciphertextByteSize = requireInteger(
      chunk,
      "ciphertextByteSize",
      AEAD_TAG_BYTES,
      chunkSizeBytes + AEAD_TAG_BYTES,
    );
    const ciphertextSha256Base64Url = requireSha256Base64Url(
      requireString(chunk, "ciphertextSha256Base64Url", 1, 128),
      "chunk_ciphertext_sha256",
    );
    const hashBytes = decodeBase64Url(ciphertextSha256Base64Url, "chunk_ciphertext_sha256");
    const ciphertextSha256Hex = bytesToHex(hashBytes);
    totalBytes += ciphertextByteSize;
    if (!Number.isSafeInteger(totalBytes) || totalBytes > LIMITS.blobCiphertext) {
      throw new ApiError(413, "blob_body_too_large");
    }
    chunks.push({
      index,
      ciphertextByteSize,
      ciphertextSha256Base64Url,
      ciphertextSha256Hex,
      r2Key: blobChunkObjectKey(workspaceId, blobId, manifestId, index),
    });
  });
  const canonicalPlan = chunks.map((chunk) => ({
    index: chunk.index,
    ciphertextByteSize: chunk.ciphertextByteSize,
    ciphertextSha256Base64Url: chunk.ciphertextSha256Base64Url,
  }));
  return {
    chunks,
    totalBytes,
    planSha256: await sha256Base64Url(utf8Bytes(canonicalJson(canonicalPlan))),
  };
}

interface SessionRow {
  sessionId: string;
  workspaceId: string;
  blobId: string;
  manifestId: string;
  keyEpoch: number;
  uploaderDeviceId: string;
  chunkSizeBytes: number;
  chunkPlanSha256: string;
  chunkCount: number;
  totalChunkBytes: number;
  manifestCiphertextSha256: string;
  manifestByteSize: number;
  reservedBytes: number;
  status: string;
  expiresAt: number;
  committedAt: number | null;
  orphanCleanupChunkCursor: number;
}

async function loadSession(env: Env, workspaceId: string, sessionId: string): Promise<SessionRow | null> {
  return first<SessionRow>(env.DB, `
    SELECT session_id AS sessionId, workspace_id AS workspaceId, blob_id AS blobId,
           manifest_id AS manifestId, key_epoch AS keyEpoch,
           uploader_device_id AS uploaderDeviceId, chunk_size_bytes AS chunkSizeBytes,
           chunk_plan_sha256 AS chunkPlanSha256, chunk_count AS chunkCount,
           total_chunk_bytes AS totalChunkBytes,
           manifest_ciphertext_sha256 AS manifestCiphertextSha256,
           manifest_byte_size AS manifestByteSize, reserved_bytes AS reservedBytes,
           status, expires_at AS expiresAt, committed_at AS committedAt,
           orphan_cleanup_chunk_cursor AS orphanCleanupChunkCursor
    FROM blob_upload_sessions WHERE workspace_id = ?1 AND session_id = ?2
  `, workspaceId, sessionId);
}

async function sessionResponse(env: Env, row: SessionRow): Promise<Record<string, unknown>> {
  const receivedChunks = await all<{
    index: number;
    ciphertextByteSize: number;
    ciphertextSha256Base64Url: string;
  }>(env.DB, `
    SELECT chunk_index AS 'index', byte_size AS ciphertextByteSize,
           ciphertext_sha256 AS ciphertextSha256Base64Url
    FROM blob_chunk_receipts WHERE session_id = ?1 ORDER BY chunk_index
  `, row.sessionId);
  return {
    sessionId: row.sessionId,
    blobId: row.blobId,
    manifestId: row.manifestId,
    keyEpoch: row.keyEpoch,
    expiresAtEpochMillis: row.expiresAt,
    reservedBytes: row.reservedBytes,
    receivedChunks,
  };
}

// One session and at most 24 chunk delete+HEAD pairs (plus one manifest pair) keeps a pass within
// 50 R2 subrequests while repeated workspace traffic advances the durable cursor.
const MAX_STALE_SESSIONS_PER_SWEEP = 1;
const MAX_ORPHAN_CHUNKS_PER_SESSION_SWEEP = 24;
const ORPHAN_SWEEP_QUIESCENCE_MILLIS = 60_000;

/**
 * Advances a bounded, workspace-wide R2 orphan sweep.
 *
 * Expired/cancelled reservations stay charged until every planned key is confirmed absent. The
 * durable chunk cursor makes each pass bounded and crash-safe even for a 1,024-chunk upload. A
 * post-expiry quiescence interval fences requests that passed their D1 precheck immediately before
 * expiry; a later upload for any blob in the workspace continues the sweep without requiring the
 * original blob id to be retried.
 */
async function sweepStaleUploadOrphans(
  env: Env,
  workspaceId: string,
  preferredBlobId: string,
  now: number,
): Promise<void> {
  const bucket = requireBodyPlaneAvailable(env);
  const stale = await all<SessionRow>(env.DB, `
    SELECT session_id AS sessionId, workspace_id AS workspaceId, blob_id AS blobId,
           manifest_id AS manifestId, key_epoch AS keyEpoch,
           uploader_device_id AS uploaderDeviceId, chunk_size_bytes AS chunkSizeBytes,
           chunk_plan_sha256 AS chunkPlanSha256, chunk_count AS chunkCount,
           total_chunk_bytes AS totalChunkBytes,
           manifest_ciphertext_sha256 AS manifestCiphertextSha256,
           manifest_byte_size AS manifestByteSize, reserved_bytes AS reservedBytes,
           status, expires_at AS expiresAt, committed_at AS committedAt,
           orphan_cleanup_chunk_cursor AS orphanCleanupChunkCursor
    FROM blob_upload_sessions
    WHERE workspace_id = ?1 AND status IN ('expired', 'cancelled')
      AND expires_at <= ?3
    ORDER BY CASE WHEN blob_id = ?2 THEN 0 ELSE 1 END,
             expires_at, created_at, session_id
    LIMIT ${MAX_STALE_SESSIONS_PER_SWEEP}
  `, workspaceId, preferredBlobId, now - ORPHAN_SWEEP_QUIESCENCE_MILLIS);
  for (const session of stale) {
    if (session.orphanCleanupChunkCursor < session.chunkCount - 1) {
      const chunks = await all<{ index: number; r2Key: string }>(env.DB, `
        SELECT chunk_index AS 'index', r2_key AS r2Key
        FROM blob_upload_session_chunks
        WHERE session_id = ?1 AND chunk_index > ?2
        ORDER BY chunk_index LIMIT ${MAX_ORPHAN_CHUNKS_PER_SESSION_SWEEP}
      `, session.sessionId, session.orphanCleanupChunkCursor);
      const expectedCount = Math.min(
        MAX_ORPHAN_CHUNKS_PER_SESSION_SWEEP,
        session.chunkCount - session.orphanCleanupChunkCursor - 1,
      );
      if (chunks.length !== expectedCount || chunks.some(
        (chunk, index) => chunk.index !== session.orphanCleanupChunkCursor + index + 1,
      )) {
        throw new ApiError(503, "blob_orphan_cleanup_plan_corrupt");
      }
      for (const chunk of chunks) await bucket.delete(chunk.r2Key);
      const remainingChunks = await Promise.all(chunks.map((chunk) => bucket.head(chunk.r2Key)));
      if (remainingChunks.some(Boolean)) continue;
      const nextCursor = chunks[chunks.length - 1]?.index ?? session.orphanCleanupChunkCursor;
      await run(env.DB, `UPDATE blob_upload_sessions
        SET orphan_cleanup_chunk_cursor = ?3
        WHERE workspace_id = ?1 AND session_id = ?2
          AND status IN ('expired', 'cancelled')
          AND orphan_cleanup_chunk_cursor = ?4`,
      workspaceId, session.sessionId, nextCursor, session.orphanCleanupChunkCursor);
    }

    const advanced = await loadSession(env, workspaceId, session.sessionId);
    if (!advanced || advanced.status === "cleaned" ||
        advanced.orphanCleanupChunkCursor < advanced.chunkCount - 1) {
      continue;
    }
    const manifestKey = blobManifestObjectKey(
      workspaceId,
      advanced.blobId,
      advanced.manifestId,
    );
    await bucket.delete(manifestKey);
    if (await bucket.head(manifestKey)) continue;
    await run(env.DB, `UPDATE blob_upload_sessions SET status = 'cleaned', cleaned_at = ?3
      WHERE workspace_id = ?1 AND session_id = ?2
        AND status IN ('expired', 'cancelled')
        AND orphan_cleanup_chunk_cursor = chunk_count - 1`,
    workspaceId, advanced.sessionId, now);
    const cleaned = await loadSession(env, workspaceId, advanced.sessionId);
    if (!cleaned || cleaned.status !== "cleaned") {
      throw new ApiError(503, "blob_orphan_cleanup_not_persisted");
    }
  }
}

export async function createBlobUploadSession(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  requireBodyPlaneAvailable(env);
  await expireEphemeralRows(env.DB, now);
  const { value } = await readJson<JsonObject>(request, LIMITS.jsonBody);
  exactKeys(value, [
    "protocolVersion", "schemaVersion", "blobId", "manifestId", "keyEpoch",
    "chunkSizeBytes", "expectedBodyCiphertextBytes", "expectedManifestCiphertextBytes",
    "manifestCiphertextSha256Base64Url", "chunks", "initialDekEnvelope",
  ], "blob_upload_reservation");
  requireBodyV2(env, value);
  const blobId = requireUuid(requireString(value, "blobId", 36, 36), "blob_id");
  const manifestId = requireUuid(requireString(value, "manifestId", 36, 36), "manifest_id");
  const keyEpoch = requireInteger(value, "keyEpoch", 1, 0x7fff_ffff);
  if (keyEpoch !== principal.keyEpoch) throw new ApiError(409, "blob_key_epoch_stale");
  const chunkSizeBytes = requireInteger(
    value,
    "chunkSizeBytes",
    LIMITS.blobChunkPlaintextMinimum,
    LIMITS.blobChunkPlaintextMaximum,
  );
  const expectedBodyCiphertextBytes = requireInteger(
    value,
    "expectedBodyCiphertextBytes",
    0,
    LIMITS.blobCiphertext,
  );
  const expectedManifestCiphertextBytes = requireInteger(
    value,
    "expectedManifestCiphertextBytes",
    AEAD_TAG_BYTES,
    LIMITS.blobManifestCiphertext,
  );
  const manifestCiphertextSha256 = requireSha256Base64Url(
    requireString(value, "manifestCiphertextSha256Base64Url", 1, 128),
    "manifest_ciphertext_sha256",
  );
  const plan = await parseChunkPlan(
    principal.workspaceId,
    blobId,
    manifestId,
    value.chunks,
    chunkSizeBytes,
  );
  if (plan.totalBytes !== expectedBodyCiphertextBytes) {
    throw new ApiError(400, "blob_body_size_plan_mismatch");
  }
  const envelope = await parseDekEnvelope(value.initialDekEnvelope, blobId);
  if (envelope.keyEpoch !== keyEpoch || envelope.previousEnvelopeSha256Base64Url !== null) {
    throw new ApiError(400, "invalid_initial_blob_envelope");
  }
  await sweepStaleUploadOrphans(env, principal.workspaceId, blobId, now);
  const existing = await first<SessionRow>(env.DB, `
    SELECT session_id AS sessionId, workspace_id AS workspaceId, blob_id AS blobId,
           manifest_id AS manifestId, key_epoch AS keyEpoch,
           uploader_device_id AS uploaderDeviceId, chunk_size_bytes AS chunkSizeBytes,
           chunk_plan_sha256 AS chunkPlanSha256, chunk_count AS chunkCount,
           total_chunk_bytes AS totalChunkBytes,
           manifest_ciphertext_sha256 AS manifestCiphertextSha256,
           manifest_byte_size AS manifestByteSize, reserved_bytes AS reservedBytes,
           status, expires_at AS expiresAt, committed_at AS committedAt,
           orphan_cleanup_chunk_cursor AS orphanCleanupChunkCursor
    FROM blob_upload_sessions
    WHERE workspace_id = ?1 AND blob_id = ?2 AND manifest_id = ?3 AND status <> 'cleaned'
    ORDER BY created_at DESC LIMIT 1
  `, principal.workspaceId, blobId, manifestId);
  if (existing) {
    const existingEnvelope = await first<{ envelopeSha256: string }>(env.DB, `
      SELECT envelope_sha256 AS envelopeSha256
      FROM blob_upload_initial_envelopes WHERE session_id = ?1
    `, existing.sessionId);
    if (existing.keyEpoch !== keyEpoch || existing.chunkSizeBytes !== chunkSizeBytes ||
        existing.chunkPlanSha256 !== plan.planSha256 ||
        existing.totalChunkBytes !== expectedBodyCiphertextBytes ||
        existing.manifestCiphertextSha256 !== manifestCiphertextSha256 ||
        existing.manifestByteSize !== expectedManifestCiphertextBytes ||
        existingEnvelope?.envelopeSha256 !== envelope.envelopeSha256Base64Url) {
      throw new ApiError(409, "blob_upload_reservation_conflict");
    }
    if (existing.status === "active" || existing.status === "committed") {
      return sessionResponse(env, existing);
    }
  }
  const sessionId = randomUuid();
  const expiresAt = now + configuredSeconds(
    env.BLOB_UPLOAD_SESSION_TTL_SECONDS,
    3600,
    "blob_upload_session_ttl",
  ) * 1000;
  const reservedBytes = expectedBodyCiphertextBytes + expectedManifestCiphertextBytes;
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(`INSERT INTO blob_upload_sessions(
      session_id, workspace_id, blob_id, manifest_id, protocol_version, schema_version,
      key_epoch, uploader_device_id, initial_capability_id, chunk_size_bytes,
      chunk_plan_sha256, chunk_count, total_chunk_bytes, manifest_ciphertext_sha256,
      manifest_byte_size, reserved_bytes, status, expires_at, created_at
    ) VALUES (?1, ?2, ?3, ?4, 2, 2, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12,
              ?13, ?14, 'staging', ?15, ?16)`)
      .bind(sessionId, principal.workspaceId, blobId, manifestId, keyEpoch,
        principal.deviceId, principal.capabilityId, chunkSizeBytes, plan.planSha256,
        plan.chunks.length, expectedBodyCiphertextBytes, manifestCiphertextSha256,
        expectedManifestCiphertextBytes, reservedBytes, expiresAt, now),
    env.DB.prepare(`INSERT INTO blob_upload_initial_envelopes(
      session_id, workspace_id, blob_id, schema_version, key_epoch, cipher_suite,
      nonce, wrapped_dek, envelope_sha256, previous_envelope_sha256
    ) VALUES (?1, ?2, ?3, 1, ?4, ?5, ?6, ?7, ?8, NULL)`)
      .bind(sessionId, principal.workspaceId, blobId, keyEpoch, envelope.cipherSuite,
        envelope.nonceBase64Url, envelope.wrappedDekBase64Url,
        envelope.envelopeSha256Base64Url),
  ];
  if (plan.chunks.length > 0) {
    const values: unknown[] = [];
    const tuples = plan.chunks.map((chunk) => {
      const start = values.length + 1;
      values.push(sessionId, principal.workspaceId, blobId, chunk.index,
        chunk.ciphertextSha256Base64Url, chunk.ciphertextSha256Hex,
        chunk.ciphertextByteSize, chunk.r2Key);
      return `(?${start}, ?${start + 1}, ?${start + 2}, ?${start + 3}, ?${start + 4}, ` +
        `?${start + 5}, ?${start + 6}, ?${start + 7})`;
    });
    statements.push(env.DB.prepare(`INSERT INTO blob_upload_session_chunks(
      session_id, workspace_id, blob_id, chunk_index, ciphertext_sha256,
      ciphertext_sha256_hex, byte_size, r2_key
    ) VALUES ${tuples.join(",")}`).bind(...values));
  }
  statements.push(env.DB.prepare(`INSERT INTO blob_upload_session_activations(session_id, activated_at)
    VALUES (?1, ?2)`).bind(sessionId, now));
  try {
    await atomicBatch(env.DB, statements);
  } catch (error) {
    const raced = await first<SessionRow>(env.DB, `
      SELECT session_id AS sessionId, workspace_id AS workspaceId, blob_id AS blobId,
             manifest_id AS manifestId, key_epoch AS keyEpoch,
             uploader_device_id AS uploaderDeviceId, chunk_size_bytes AS chunkSizeBytes,
             chunk_plan_sha256 AS chunkPlanSha256, chunk_count AS chunkCount,
             total_chunk_bytes AS totalChunkBytes,
             manifest_ciphertext_sha256 AS manifestCiphertextSha256,
             manifest_byte_size AS manifestByteSize, reserved_bytes AS reservedBytes,
             status, expires_at AS expiresAt, committed_at AS committedAt,
             orphan_cleanup_chunk_cursor AS orphanCleanupChunkCursor
      FROM blob_upload_sessions
      WHERE workspace_id = ?1 AND blob_id = ?2 AND manifest_id = ?3 AND status <> 'cleaned'
      ORDER BY created_at DESC LIMIT 1
    `, principal.workspaceId, blobId, manifestId);
    if (raced && raced.chunkPlanSha256 === plan.planSha256 &&
        raced.manifestCiphertextSha256 === manifestCiphertextSha256) {
      const racedEnvelope = await first<{ envelopeSha256: string }>(env.DB, `
        SELECT envelope_sha256 AS envelopeSha256
        FROM blob_upload_initial_envelopes WHERE session_id = ?1
      `, raced.sessionId);
      if (racedEnvelope?.envelopeSha256 === envelope.envelopeSha256Base64Url) {
        return sessionResponse(env, raced);
      }
    }
    const quota = await first<{
      committedBytes: number;
      reservedBytes: number;
      maximumBytes: number;
      maximumBlobBytes: number;
      maximumManifestBytes: number;
      maximumChunks: number;
    }>(env.DB, `
      SELECT workspace.committed_bytes AS committedBytes,
             workspace.reserved_bytes AS reservedBytes,
             quota.max_workspace_bytes AS maximumBytes,
             quota.max_blob_bytes AS maximumBlobBytes,
             quota.max_blob_manifest_bytes AS maximumManifestBytes,
             quota.max_blob_chunks AS maximumChunks
      FROM workspaces workspace
      JOIN users user ON user.user_id = workspace.owner_user_id
      JOIN quota_profiles quota ON quota.quota_profile_id = user.quota_profile_id
      WHERE workspace.workspace_id = ?1
    `, principal.workspaceId);
    if (quota && (expectedBodyCiphertextBytes > quota.maximumBlobBytes ||
        expectedManifestCiphertextBytes > quota.maximumManifestBytes ||
        plan.chunks.length > quota.maximumChunks ||
        quota.committedBytes + quota.reservedBytes + reservedBytes > quota.maximumBytes)) {
      throw new ApiError(507, "workspace_quota_exceeded");
    }
    throw error;
  }
  const created = await loadSession(env, principal.workspaceId, sessionId);
  if (!created) throw new ApiError(503, "blob_upload_session_not_persisted");
  return sessionResponse(env, created);
}

export async function getBlobUploadSession(
  env: Env,
  principal: CapabilityPrincipal,
  sessionIdInput: string,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireBodyPlaneAvailable(env);
  await expireEphemeralRows(env.DB, now);
  const sessionId = requireUuid(sessionIdInput, "session_id");
  const row = await loadSession(env, principal.workspaceId, sessionId);
  if (!row) throw new ApiError(404, "blob_upload_session_not_found");
  if (["expired", "cancelled", "cleaned"].includes(row.status)) {
    throw new ApiError(409, "blob_upload_session_expired", "The upload reservation is no longer active", {
      sessionId,
    });
  }
  return sessionResponse(env, row);
}

function objectMetadataMatches(
  object: { size: number; customMetadata?: Record<string, string> },
  size: number,
  expected: Record<string, string>,
): boolean {
  return object.size === size && Object.entries(expected).every(
    ([key, value]) => object.customMetadata?.[key] === value,
  );
}

export async function uploadBlobChunk(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  sessionIdInput: string,
  chunkIndex: number,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  const bucket = requireBodyPlaneAvailable(env);
  await expireEphemeralRows(env.DB, now);
  if (request.headers.get("Content-Type")?.split(";", 1)[0].trim() !==
      "application/octet-stream") {
    throw new ApiError(415, "octet_stream_content_type_required");
  }
  const sessionId = requireUuid(sessionIdInput, "session_id");
  if (!Number.isSafeInteger(chunkIndex) || chunkIndex < 0 || chunkIndex >= LIMITS.blobChunks) {
    throw new ApiError(400, "invalid_chunk_index");
  }
  const declaredHash = requireSha256Base64Url(
    request.headers.get("X-Shinsou-Ciphertext-Sha256"),
    "ciphertext_sha256",
  );
  const ciphertext = await readBytes(request, LIMITS.blobChunkCiphertext);
  if (ciphertext.byteLength < AEAD_TAG_BYTES) throw new ApiError(400, "blob_chunk_too_small");
  const actualHash = await sha256Base64Url(ciphertext);
  if (actualHash !== declaredHash) throw new ApiError(400, "blob_chunk_hash_mismatch");
  const planned = await first<{
    blobId: string;
    manifestId: string;
    uploaderDeviceId: string;
    status: string;
    expiresAt: number;
    keyEpoch: number;
    ciphertextSha256: string;
    byteSize: number;
    r2Key: string;
  }>(env.DB, `
    SELECT session.blob_id AS blobId, session.manifest_id AS manifestId,
           session.uploader_device_id AS uploaderDeviceId,
           session.status, session.expires_at AS expiresAt, session.key_epoch AS keyEpoch,
           chunk.ciphertext_sha256 AS ciphertextSha256, chunk.byte_size AS byteSize,
           chunk.r2_key AS r2Key
    FROM blob_upload_sessions session
    JOIN blob_upload_session_chunks chunk ON chunk.session_id = session.session_id
    WHERE session.workspace_id = ?1 AND session.session_id = ?2 AND chunk.chunk_index = ?3
  `, principal.workspaceId, sessionId, chunkIndex);
  if (!planned) throw new ApiError(404, "blob_chunk_not_planned");
  if (planned.status !== "active" || planned.expiresAt <= now ||
      planned.uploaderDeviceId !== principal.deviceId || planned.keyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_upload_session_not_active");
  }
  if (planned.ciphertextSha256 !== declaredHash || planned.byteSize !== ciphertext.byteLength) {
    throw new ApiError(409, "blob_chunk_plan_mismatch");
  }
  const existingReceipt = await first<{
    ciphertextSha256: string;
    ciphertextByteSize: number;
  }>(env.DB, `SELECT ciphertext_sha256 AS ciphertextSha256, byte_size AS ciphertextByteSize
    FROM blob_chunk_receipts WHERE session_id = ?1 AND chunk_index = ?2`, sessionId, chunkIndex);
  if (existingReceipt) {
    if (existingReceipt.ciphertextSha256 !== declaredHash ||
        existingReceipt.ciphertextByteSize !== ciphertext.byteLength) {
      throw new ApiError(409, "blob_chunk_receipt_conflict");
    }
  }
  const metadata = {
    kind: "blobChunkV2",
    workspaceId: principal.workspaceId,
    blobId: planned.blobId,
    manifestId: planned.manifestId,
    sessionId,
    chunkIndex: String(chunkIndex),
    ciphertextSha256: declaredHash,
  };
  const head = await bucket.head(planned.r2Key);
  let createdObject = false;
  if (head) {
    if (!objectMetadataMatches(head, ciphertext.byteLength, metadata)) {
      throw new ApiError(409, "blob_chunk_object_conflict");
    }
  } else {
    const put = await bucket.put(planned.r2Key, ciphertext, {
      customMetadata: metadata,
      httpMetadata: { contentType: "application/octet-stream" },
      onlyIf: { etagDoesNotMatch: "*" },
    });
    createdObject = Boolean(put);
    if (!put) {
      const raced = await bucket.head(planned.r2Key);
      if (!raced || !objectMetadataMatches(raced, ciphertext.byteLength, metadata)) {
        throw new ApiError(409, "blob_chunk_object_conflict");
      }
    }
  }
  if (existingReceipt) {
    return {
      index: chunkIndex,
      ciphertextByteSize: ciphertext.byteLength,
      ciphertextSha256Base64Url: declaredHash,
    };
  }
  try {
    await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_chunk_receipts(
      receipt_id, session_id, workspace_id, blob_id, chunk_index, ciphertext_sha256,
      byte_size, r2_key, uploaded_by_device_id, capability_id, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)`)
      .bind(randomUuid(), sessionId, principal.workspaceId, planned.blobId, chunkIndex,
        declaredHash, ciphertext.byteLength, planned.r2Key, principal.deviceId,
        principal.capabilityId, now)]);
  } catch (error) {
    const raced = await first<{ ciphertextSha256: string; byteSize: number }>(env.DB, `
      SELECT ciphertext_sha256 AS ciphertextSha256, byte_size AS byteSize
      FROM blob_chunk_receipts WHERE session_id = ?1 AND chunk_index = ?2
    `, sessionId, chunkIndex);
    if (!raced || raced.ciphertextSha256 !== declaredHash || raced.byteSize !== ciphertext.byteLength) {
      if (createdObject) await bucket.delete(planned.r2Key);
      throw error;
    }
  }
  return {
    index: chunkIndex,
    ciphertextByteSize: ciphertext.byteLength,
    ciphertextSha256Base64Url: declaredHash,
  };
}

interface PrivateManifest {
  nonceBase64Url: string;
  ciphertextBase64Url: string;
  ciphertextSha256Base64Url: string;
  ciphertextByteSize: number;
  ciphertext: Uint8Array;
}

async function parsePrivateManifest(value: unknown): Promise<PrivateManifest> {
  const manifest = requireObject(value, "encryptedPrivateManifest");
  exactKeys(manifest, [
    "nonceBase64Url", "ciphertextBase64Url", "ciphertextSha256Base64Url", "ciphertextByteSize",
  ], "encrypted_private_manifest");
  const nonceBase64Url = requireString(manifest, "nonceBase64Url", 1, 128);
  if (decodeBase64Url(nonceBase64Url, "private_manifest_nonce").byteLength !== 12) {
    throw new ApiError(400, "invalid_private_manifest_nonce");
  }
  const ciphertextBase64Url = requireString(
    manifest,
    "ciphertextBase64Url",
    1,
    Math.ceil(LIMITS.blobManifestCiphertext * 4 / 3) + 4,
  );
  const ciphertext = decodeBase64Url(ciphertextBase64Url, "private_manifest_ciphertext");
  const ciphertextByteSize = requireInteger(
    manifest,
    "ciphertextByteSize",
    AEAD_TAG_BYTES,
    LIMITS.blobManifestCiphertext,
  );
  if (ciphertext.byteLength !== ciphertextByteSize) {
    throw new ApiError(400, "private_manifest_size_mismatch");
  }
  const ciphertextSha256Base64Url = requireSha256Base64Url(
    requireString(manifest, "ciphertextSha256Base64Url", 1, 128),
    "private_manifest_ciphertext_sha256",
  );
  if (await sha256Base64Url(ciphertext) !== ciphertextSha256Base64Url) {
    throw new ApiError(400, "private_manifest_hash_mismatch");
  }
  return {
    nonceBase64Url,
    ciphertextBase64Url,
    ciphertextSha256Base64Url,
    ciphertextByteSize,
    ciphertext,
  };
}

async function committedReceipt(
  env: Env,
  workspaceId: string,
  blobId: string,
): Promise<Record<string, unknown> | null> {
  const row = await first<{
    receiptId: string;
    sessionId: string;
    manifestId: string;
    manifestHash: string;
    manifestBytes: number;
    bodyBytes: number;
    chunkCount: number;
    chunkSizeBytes: number;
    committedAt: number;
  }>(env.DB, `
    SELECT commit_row.receipt_id AS receiptId, commit_row.session_id AS sessionId,
           manifest.manifest_id AS manifestId,
           manifest.manifest_ciphertext_sha256 AS manifestHash,
           manifest.manifest_byte_size AS manifestBytes,
           manifest.total_chunk_bytes AS bodyBytes, manifest.chunk_count AS chunkCount,
           manifest.chunk_size_bytes AS chunkSizeBytes, manifest.committed_at AS committedAt
    FROM encrypted_blob_manifests manifest
    JOIN blob_manifest_commits commit_row
      ON commit_row.workspace_id = manifest.workspace_id
     AND commit_row.blob_id = manifest.blob_id
     AND commit_row.session_id = manifest.session_id
     AND commit_row.manifest_id = manifest.manifest_id
    WHERE manifest.workspace_id = ?1 AND manifest.blob_id = ?2
      AND manifest.status IN ('committed', 'tombstoned', 'deleting')
  `, workspaceId, blobId);
  if (!row) return null;
  return {
    receiptId: row.receiptId,
    sessionId: row.sessionId,
    manifest: {
      schemaVersion: 1,
      manifestId: row.manifestId,
      blobId,
      manifestCiphertextSha256Base64Url: row.manifestHash,
      manifestCiphertextByteSize: row.manifestBytes,
      bodyCiphertextByteSize: row.bodyBytes,
      chunkCount: row.chunkCount,
      chunkSizeBytes: row.chunkSizeBytes,
      committedAtEpochMillis: row.committedAt,
      commitReceiptId: row.receiptId,
    },
  };
}

export async function commitBlobUpload(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  sessionIdInput: string,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  const bucket = requireBodyPlaneAvailable(env);
  await expireEphemeralRows(env.DB, now);
  const sessionId = requireUuid(sessionIdInput, "session_id");
  const { value } = await readJson<JsonObject>(request, LIMITS.blobManifestCiphertext * 2);
  exactKeys(value, ["protocolVersion", "schemaVersion", "encryptedPrivateManifest"], "blob_commit");
  requireBodyV2(env, value);
  const privateManifest = await parsePrivateManifest(value.encryptedPrivateManifest);
  const session = await loadSession(env, principal.workspaceId, sessionId);
  if (!session) throw new ApiError(404, "blob_upload_session_not_found");
  const existing = await committedReceipt(env, principal.workspaceId, session.blobId);
  if (existing) {
    const existingManifest = existing.manifest as Record<string, unknown>;
    const committedNonce = await first<{ nonce: string }>(env.DB, `
      SELECT private_manifest_nonce AS nonce FROM encrypted_blob_manifests
      WHERE workspace_id = ?1 AND blob_id = ?2
    `, principal.workspaceId, session.blobId);
    if (existing.sessionId === sessionId &&
        existingManifest.manifestCiphertextSha256Base64Url ===
          privateManifest.ciphertextSha256Base64Url &&
        committedNonce?.nonce === privateManifest.nonceBase64Url) {
      return existing;
    }
    throw new ApiError(409, "blob_manifest_commit_conflict");
  }
  if (session.status !== "active" || session.expiresAt <= now ||
      session.uploaderDeviceId !== principal.deviceId || session.keyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_upload_session_not_active");
  }
  if (session.manifestCiphertextSha256 !== privateManifest.ciphertextSha256Base64Url ||
      session.manifestByteSize !== privateManifest.ciphertextByteSize) {
    throw new ApiError(409, "private_manifest_reservation_mismatch");
  }
  const receiptCount = await first<{ count: number; bytes: number }>(env.DB, `
    SELECT COUNT(*) AS count, COALESCE(SUM(byte_size), 0) AS bytes
    FROM blob_chunk_receipts WHERE session_id = ?1
  `, sessionId);
  if (!receiptCount || receiptCount.count !== session.chunkCount ||
      receiptCount.bytes !== session.totalChunkBytes) {
    throw new ApiError(409, "blob_chunks_incomplete", "Upload all exact chunks before commit", {
      expectedChunks: session.chunkCount,
      receivedChunks: receiptCount?.count ?? 0,
    });
  }
  const committedChunks = await all<{
    index: number;
    byteSize: number;
    ciphertextSha256: string;
    r2Key: string;
  }>(env.DB, `
    SELECT planned.chunk_index AS 'index', planned.byte_size AS byteSize,
           planned.ciphertext_sha256 AS ciphertextSha256, planned.r2_key AS r2Key
    FROM blob_upload_session_chunks planned
    JOIN blob_chunk_receipts receipt
      ON receipt.session_id = planned.session_id
     AND receipt.chunk_index = planned.chunk_index
     AND receipt.ciphertext_sha256 = planned.ciphertext_sha256
     AND receipt.byte_size = planned.byte_size
     AND receipt.r2_key = planned.r2_key
    WHERE planned.session_id = ?1 ORDER BY planned.chunk_index
  `, sessionId);
  for (let offset = 0; offset < committedChunks.length; offset += 32) {
    const window = committedChunks.slice(offset, offset + 32);
    const heads = await Promise.all(window.map((chunk) => bucket.head(chunk.r2Key)));
    heads.forEach((head, index) => {
      const chunk = window[index];
      if (!head || !objectMetadataMatches(head, chunk.byteSize, {
        kind: "blobChunkV2",
        workspaceId: principal.workspaceId,
        blobId: session.blobId,
        manifestId: session.manifestId,
        sessionId,
        chunkIndex: String(chunk.index),
        ciphertextSha256: chunk.ciphertextSha256,
      })) {
        throw new ApiError(409, "blob_chunk_object_missing", "A committed chunk receipt has no exact R2 object", {
          index: chunk.index,
        });
      }
    });
  }
  const manifestR2Key = blobManifestObjectKey(
    principal.workspaceId,
    session.blobId,
    session.manifestId,
  );
  const metadata = {
    kind: "encryptedBlobPrivateManifestV2",
    workspaceId: principal.workspaceId,
    blobId: session.blobId,
    manifestId: session.manifestId,
    sessionId,
    ciphertextSha256: privateManifest.ciphertextSha256Base64Url,
    nonce: privateManifest.nonceBase64Url,
  };
  const head = await bucket.head(manifestR2Key);
  let createdObject = false;
  if (head) {
    if (!objectMetadataMatches(head, privateManifest.ciphertextByteSize, metadata)) {
      throw new ApiError(409, "blob_manifest_object_conflict");
    }
  } else {
    const put = await bucket.put(manifestR2Key, privateManifest.ciphertext, {
      customMetadata: metadata,
      httpMetadata: { contentType: "application/octet-stream" },
      onlyIf: { etagDoesNotMatch: "*" },
    });
    createdObject = Boolean(put);
    if (!put) {
      const raced = await bucket.head(manifestR2Key);
      if (!raced || !objectMetadataMatches(raced, privateManifest.ciphertextByteSize, metadata)) {
        throw new ApiError(409, "blob_manifest_object_conflict");
      }
    }
  }
  const receiptId = randomUuid();
  try {
    await atomicBatch(env.DB, [
      // A deleted row is the stable FK anchor for tombstone/revival/GC audit history. Remove
      // only its obsolete key material, then reuse the same (workspace, blob) anchor for a new
      // candidate lifecycle. A concurrently revived live row makes these deletes no-ops.
      env.DB.prepare(`DELETE FROM blob_dek_envelopes
        WHERE workspace_id = ?1 AND blob_id = ?2
          AND EXISTS (
            SELECT 1 FROM encrypted_blob_manifests manifest
            WHERE manifest.workspace_id = ?1 AND manifest.blob_id = ?2
              AND manifest.status = 'deleted'
          )`)
        .bind(principal.workspaceId, session.blobId),
      env.DB.prepare(`DELETE FROM blob_upload_initial_envelopes
        WHERE session_id = (
          SELECT manifest.session_id FROM encrypted_blob_manifests manifest
          WHERE manifest.workspace_id = ?1 AND manifest.blob_id = ?2
            AND manifest.status = 'deleted'
        )`)
        .bind(principal.workspaceId, session.blobId),
      env.DB.prepare(`INSERT INTO encrypted_blob_manifests(
        workspace_id, blob_id, manifest_id, session_id, protocol_version, schema_version,
        key_epoch, private_manifest_nonce, chunk_plan_sha256, chunk_count, chunk_size_bytes,
        total_chunk_bytes, manifest_ciphertext_sha256, manifest_byte_size, manifest_r2_key,
        uploader_device_id, current_envelope_epoch, status, created_at
      ) VALUES (?1, ?2, ?3, ?4, 2, 2, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12,
                ?13, ?14, ?5, 'candidate', ?15)
        ON CONFLICT(workspace_id, blob_id) DO UPDATE SET
          manifest_id = excluded.manifest_id,
          session_id = excluded.session_id,
          protocol_version = excluded.protocol_version,
          schema_version = excluded.schema_version,
          key_epoch = excluded.key_epoch,
          private_manifest_nonce = excluded.private_manifest_nonce,
          chunk_plan_sha256 = excluded.chunk_plan_sha256,
          chunk_count = excluded.chunk_count,
          chunk_size_bytes = excluded.chunk_size_bytes,
          total_chunk_bytes = excluded.total_chunk_bytes,
          manifest_ciphertext_sha256 = excluded.manifest_ciphertext_sha256,
          manifest_byte_size = excluded.manifest_byte_size,
          manifest_r2_key = excluded.manifest_r2_key,
          uploader_device_id = excluded.uploader_device_id,
          current_envelope_epoch = excluded.current_envelope_epoch,
          status = 'candidate',
          created_at = excluded.created_at,
          committed_at = NULL,
          deleted_at = NULL
        WHERE encrypted_blob_manifests.status = 'deleted'`)
        .bind(principal.workspaceId, session.blobId, session.manifestId, sessionId,
          session.keyEpoch, privateManifest.nonceBase64Url, session.chunkPlanSha256,
          session.chunkCount, session.chunkSizeBytes, session.totalChunkBytes,
          privateManifest.ciphertextSha256Base64Url, privateManifest.ciphertextByteSize,
          manifestR2Key, principal.deviceId, now),
      env.DB.prepare(`INSERT INTO blob_dek_envelopes(
        workspace_id, blob_id, key_epoch, envelope_version, cipher_suite, nonce, wrapped_dek,
        envelope_sha256, previous_envelope_sha256, evidence_checkpoint_id,
        evidence_checkpoint_sha256, evidence_through_seq, created_by_device_id, status, created_at
      ) SELECT ?1, ?2, initial.key_epoch, initial.schema_version, initial.cipher_suite,
               initial.nonce, initial.wrapped_dek, initial.envelope_sha256,
               initial.previous_envelope_sha256, NULL, NULL, NULL, ?3, 'current', ?4
        FROM blob_upload_initial_envelopes initial WHERE initial.session_id = ?5`)
        .bind(principal.workspaceId, session.blobId, principal.deviceId, now, sessionId),
      env.DB.prepare(`INSERT INTO blob_manifest_commits(
        receipt_id, session_id, workspace_id, blob_id, manifest_id,
        manifest_ciphertext_sha256, chunk_plan_sha256, capability_id,
        committed_by_device_id, committed_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)`)
        .bind(receiptId, sessionId, principal.workspaceId, session.blobId, session.manifestId,
          privateManifest.ciphertextSha256Base64Url, session.chunkPlanSha256,
          principal.capabilityId, principal.deviceId, now),
    ]);
  } catch (error) {
    const raced = await committedReceipt(env, principal.workspaceId, session.blobId);
    if (raced && raced.sessionId === sessionId) return raced;
    if (createdObject) await bucket.delete(manifestR2Key);
    throw error;
  }
  const committed = await committedReceipt(env, principal.workspaceId, session.blobId);
  if (!committed) throw new ApiError(503, "blob_manifest_commit_not_persisted");
  return committed;
}

interface CommittedManifestRow {
  workspaceId: string;
  blobId: string;
  manifestId: string;
  sessionId: string;
  keyEpoch: number;
  privateManifestNonce: string;
  chunkCount: number;
  chunkSizeBytes: number;
  totalChunkBytes: number;
  manifestCiphertextSha256: string;
  manifestByteSize: number;
  manifestR2Key: string;
  currentEnvelopeEpoch: number;
  status: string;
  committedAt: number;
  receiptId: string;
  activeKeyEpoch: number;
}

async function loadCommittedManifest(
  env: Env,
  workspaceId: string,
  blobId: string,
): Promise<CommittedManifestRow | null> {
  return first<CommittedManifestRow>(env.DB, `
    SELECT manifest.workspace_id AS workspaceId, manifest.blob_id AS blobId,
           manifest.manifest_id AS manifestId, manifest.session_id AS sessionId,
           manifest.key_epoch AS keyEpoch,
           manifest.private_manifest_nonce AS privateManifestNonce,
           manifest.chunk_count AS chunkCount, manifest.chunk_size_bytes AS chunkSizeBytes,
           manifest.total_chunk_bytes AS totalChunkBytes,
           manifest.manifest_ciphertext_sha256 AS manifestCiphertextSha256,
           manifest.manifest_byte_size AS manifestByteSize,
           manifest.manifest_r2_key AS manifestR2Key,
           manifest.current_envelope_epoch AS currentEnvelopeEpoch,
           manifest.status, manifest.committed_at AS committedAt,
           commit_row.receipt_id AS receiptId, workspace.active_key_epoch AS activeKeyEpoch
    FROM encrypted_blob_manifests manifest
    JOIN blob_manifest_commits commit_row
      ON commit_row.workspace_id = manifest.workspace_id
     AND commit_row.blob_id = manifest.blob_id
     AND commit_row.session_id = manifest.session_id
     AND commit_row.manifest_id = manifest.manifest_id
    JOIN workspaces workspace ON workspace.workspace_id = manifest.workspace_id
    WHERE manifest.workspace_id = ?1 AND manifest.blob_id = ?2
  `, workspaceId, blobId);
}

function requireReadableManifestEpoch(
  manifest: CommittedManifestRow,
  principal: CapabilityPrincipal,
): void {
  if (manifest.activeKeyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_key_epoch_stale");
  }
  if (manifest.currentEnvelopeEpoch !== manifest.activeKeyEpoch) {
    throw new ApiError(409, "blob_envelope_rewrap_required", "The blob has no envelope for the active workspace epoch", {
      activeKeyEpoch: manifest.activeKeyEpoch,
      currentEnvelopeEpoch: manifest.currentEnvelopeEpoch,
    });
  }
}

function remoteManifestRef(manifest: CommittedManifestRow): Record<string, unknown> {
  return {
    schemaVersion: 1,
    manifestId: manifest.manifestId,
    blobId: manifest.blobId,
    manifestCiphertextSha256Base64Url: manifest.manifestCiphertextSha256,
    manifestCiphertextByteSize: manifest.manifestByteSize,
    bodyCiphertextByteSize: manifest.totalChunkBytes,
    chunkCount: manifest.chunkCount,
    chunkSizeBytes: manifest.chunkSizeBytes,
    committedAtEpochMillis: manifest.committedAt,
    commitReceiptId: manifest.receiptId,
  };
}

interface EnvelopeRow {
  schemaVersion: number;
  blobId: string;
  keyEpoch: number;
  cipherSuite: string;
  nonceBase64Url: string;
  wrappedDekBase64Url: string;
  envelopeSha256Base64Url: string;
  previousEnvelopeSha256Base64Url: string | null;
  evidenceCheckpointId: string | null;
  evidenceCheckpointSha256: string | null;
  evidenceThroughSeq: number | null;
  status: string;
}

function envelopeResponse(envelope: EnvelopeRow | DekEnvelope): Record<string, unknown> {
  return {
    schemaVersion: envelope.schemaVersion,
    blobId: envelope.blobId,
    keyEpoch: envelope.keyEpoch,
    cipherSuite: envelope.cipherSuite,
    nonceBase64Url: envelope.nonceBase64Url,
    wrappedDekBase64Url: envelope.wrappedDekBase64Url,
    envelopeSha256Base64Url: envelope.envelopeSha256Base64Url,
    previousEnvelopeSha256Base64Url: envelope.previousEnvelopeSha256Base64Url,
  };
}

/**
 * Returns the exact envelope that won a previous re-wrap epoch.
 *
 * This is intentionally a small authenticated control-plane read. It lets a client recover from
 * losing the successful POST response without downloading body ciphertext or trusting its own
 * stale random envelope. The original checkpoint evidence is returned so the recovered value can
 * travel through the ordinary signed metadata mutation path.
 */
export async function getBlobEnvelope(
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
  keyEpochInput: number,
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  if (!Number.isSafeInteger(keyEpochInput) || keyEpochInput < 1 ||
      keyEpochInput > principal.keyEpoch) {
    throw new ApiError(400, "invalid_blob_envelope_epoch");
  }
  const envelope = await first<EnvelopeRow & {
    manifestId: string;
    manifestStatus: string;
    currentEnvelopeEpoch: number;
  }>(env.DB, `
    SELECT envelope.envelope_version AS schemaVersion, envelope.blob_id AS blobId,
           envelope.key_epoch AS keyEpoch, envelope.cipher_suite AS cipherSuite,
           envelope.nonce AS nonceBase64Url, envelope.wrapped_dek AS wrappedDekBase64Url,
           envelope.envelope_sha256 AS envelopeSha256Base64Url,
           envelope.previous_envelope_sha256 AS previousEnvelopeSha256Base64Url,
           envelope.evidence_checkpoint_id AS evidenceCheckpointId,
           envelope.evidence_checkpoint_sha256 AS evidenceCheckpointSha256,
           envelope.evidence_through_seq AS evidenceThroughSeq, envelope.status,
           manifest.manifest_id AS manifestId, manifest.status AS manifestStatus,
           manifest.current_envelope_epoch AS currentEnvelopeEpoch
    FROM blob_dek_envelopes envelope
    JOIN encrypted_blob_manifests manifest
      ON manifest.workspace_id = envelope.workspace_id
     AND manifest.blob_id = envelope.blob_id
    WHERE envelope.workspace_id = ?1 AND envelope.blob_id = ?2
      AND envelope.key_epoch = ?3
      AND manifest.status IN ('committed', 'tombstoned')
  `, principal.workspaceId, blobId, keyEpochInput);
  if (!envelope) throw new ApiError(404, "blob_envelope_not_found");

  const isInitial = envelope.previousEnvelopeSha256Base64Url === null;
  const hasNoEvidence = envelope.evidenceCheckpointId === null &&
    envelope.evidenceCheckpointSha256 === null && envelope.evidenceThroughSeq === null;
  const hasCompleteEvidence = envelope.evidenceCheckpointId !== null &&
    envelope.evidenceCheckpointSha256 !== null && envelope.evidenceThroughSeq !== null;
  const expectedStatus = envelope.keyEpoch === envelope.currentEnvelopeEpoch
    ? "current"
    : "retained";
  if (envelope.schemaVersion !== ENVELOPE_SCHEMA_VERSION ||
      envelope.blobId !== blobId || envelope.keyEpoch !== keyEpochInput ||
      envelope.currentEnvelopeEpoch > principal.keyEpoch || envelope.status !== expectedStatus ||
      (isInitial ? !hasNoEvidence : !hasCompleteEvidence)) {
    throw new ApiError(503, "blob_envelope_record_corrupt");
  }
  return {
    protocolVersion: 2,
    schemaVersion: 2,
    manifestId: envelope.manifestId,
    envelope: envelopeResponse(envelope),
    checkpointEvidence: isInitial ? null : {
      checkpointId: envelope.evidenceCheckpointId,
      checkpointCiphertextSha256Base64Url: envelope.evidenceCheckpointSha256,
      throughWorkspaceSeq: envelope.evidenceThroughSeq,
    },
    status: envelope.status,
  };
}

export async function downloadBlobManifest(
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
): Promise<Record<string, unknown>> {
  const bucket = requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  const manifest = await loadCommittedManifest(env, principal.workspaceId, blobId);
  if (!manifest || !["committed", "tombstoned"].includes(manifest.status)) {
    throw new ApiError(404, "blob_manifest_not_found");
  }
  requireReadableManifestEpoch(manifest, principal);
  const chunks = await all<{
    index: number;
    ciphertextByteSize: number;
    ciphertextSha256Base64Url: string;
  }>(env.DB, `
    SELECT chunk_index AS 'index', byte_size AS ciphertextByteSize,
           ciphertext_sha256 AS ciphertextSha256Base64Url
    FROM blob_upload_session_chunks
    WHERE session_id = ?1 ORDER BY chunk_index
  `, manifest.sessionId);
  if (chunks.length !== manifest.chunkCount ||
      chunks.reduce((sum, chunk) => sum + chunk.ciphertextByteSize, 0) !==
        manifest.totalChunkBytes ||
      chunks.some((chunk, index) => chunk.index !== index)) {
    throw new ApiError(503, "blob_chunk_plan_corrupt");
  }
  const envelopes = await all<EnvelopeRow>(env.DB, `
    SELECT envelope_version AS schemaVersion, blob_id AS blobId, key_epoch AS keyEpoch,
           cipher_suite AS cipherSuite, nonce AS nonceBase64Url,
           wrapped_dek AS wrappedDekBase64Url, envelope_sha256 AS envelopeSha256Base64Url,
           previous_envelope_sha256 AS previousEnvelopeSha256Base64Url,
           evidence_checkpoint_id AS evidenceCheckpointId,
           evidence_checkpoint_sha256 AS evidenceCheckpointSha256,
           evidence_through_seq AS evidenceThroughSeq, status
    FROM blob_dek_envelopes
    WHERE workspace_id = ?1 AND blob_id = ?2 ORDER BY key_epoch
  `, principal.workspaceId, blobId);
  if (envelopes.length === 0 || envelopes[0].previousEnvelopeSha256Base64Url !== null ||
      envelopes.some((envelope, index) =>
        envelope.blobId !== blobId || envelope.schemaVersion !== ENVELOPE_SCHEMA_VERSION ||
        (index > 0 && envelope.previousEnvelopeSha256Base64Url !==
          envelopes[index - 1].envelopeSha256Base64Url)) ||
      envelopes.filter((envelope) => envelope.status === "current").length !== 1 ||
      !envelopes.some((envelope) =>
        envelope.status === "current" && envelope.keyEpoch === manifest.currentEnvelopeEpoch)) {
    throw new ApiError(503, "blob_envelope_chain_corrupt");
  }
  const object = await bucket.get(manifest.manifestR2Key);
  if (!object || !objectMetadataMatches(object, manifest.manifestByteSize, {
    kind: "encryptedBlobPrivateManifestV2",
    workspaceId: principal.workspaceId,
    blobId,
    manifestId: manifest.manifestId,
    sessionId: manifest.sessionId,
    ciphertextSha256: manifest.manifestCiphertextSha256,
    nonce: manifest.privateManifestNonce,
  })) {
    throw new ApiError(503, "blob_manifest_object_missing");
  }
  const ciphertext = new Uint8Array(await object.arrayBuffer());
  const actualHash = await sha256Bytes(ciphertext);
  const expectedHash = decodeBase64Url(
    manifest.manifestCiphertextSha256,
    "manifest_ciphertext_sha256",
  );
  if (ciphertext.byteLength !== manifest.manifestByteSize ||
      !timingSafeEqual(actualHash, expectedHash)) {
    throw new ApiError(503, "blob_manifest_object_corrupt");
  }
  return {
    remote: remoteManifestRef(manifest),
    chunks,
    encryptedPrivateManifest: {
      nonceBase64Url: manifest.privateManifestNonce,
      ciphertextBase64Url: encodeBase64Url(ciphertext),
      ciphertextSha256Base64Url: manifest.manifestCiphertextSha256,
      ciphertextByteSize: manifest.manifestByteSize,
    },
    dekEnvelopes: envelopes.map(envelopeResponse),
  };
}

export async function downloadBlobChunk(
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
  chunkIndex: number,
): Promise<Response> {
  const bucket = requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  if (!Number.isSafeInteger(chunkIndex) || chunkIndex < 0 || chunkIndex >= LIMITS.blobChunks) {
    throw new ApiError(400, "invalid_chunk_index");
  }
  const chunk = await first<{
    sessionId: string;
    manifestId: string;
    activeKeyEpoch: number;
    currentEnvelopeEpoch: number;
    manifestStatus: string;
    ciphertextByteSize: number;
    ciphertextSha256Base64Url: string;
    r2Key: string;
  }>(env.DB, `
    SELECT manifest.session_id AS sessionId, manifest.manifest_id AS manifestId,
           workspace.active_key_epoch AS activeKeyEpoch,
           manifest.current_envelope_epoch AS currentEnvelopeEpoch,
           manifest.status AS manifestStatus, planned.byte_size AS ciphertextByteSize,
           planned.ciphertext_sha256 AS ciphertextSha256Base64Url, planned.r2_key AS r2Key
    FROM encrypted_blob_manifests manifest
    JOIN workspaces workspace ON workspace.workspace_id = manifest.workspace_id
    JOIN blob_upload_session_chunks planned ON planned.session_id = manifest.session_id
    JOIN blob_chunk_receipts receipt
      ON receipt.session_id = planned.session_id
     AND receipt.chunk_index = planned.chunk_index
     AND receipt.ciphertext_sha256 = planned.ciphertext_sha256
     AND receipt.byte_size = planned.byte_size
     AND receipt.r2_key = planned.r2_key
    WHERE manifest.workspace_id = ?1 AND manifest.blob_id = ?2 AND planned.chunk_index = ?3
  `, principal.workspaceId, blobId, chunkIndex);
  if (!chunk || !["committed", "tombstoned"].includes(chunk.manifestStatus)) {
    throw new ApiError(404, "blob_chunk_not_found");
  }
  if (chunk.activeKeyEpoch !== principal.keyEpoch ||
      chunk.currentEnvelopeEpoch !== chunk.activeKeyEpoch) {
    throw new ApiError(409, "blob_envelope_rewrap_required");
  }
  const object = await bucket.get(chunk.r2Key);
  if (!object || !objectMetadataMatches(object, chunk.ciphertextByteSize, {
    kind: "blobChunkV2",
    workspaceId: principal.workspaceId,
    blobId,
    manifestId: chunk.manifestId,
    sessionId: chunk.sessionId,
    chunkIndex: String(chunkIndex),
    ciphertextSha256: chunk.ciphertextSha256Base64Url,
  })) {
    throw new ApiError(503, "blob_chunk_object_missing");
  }
  const ciphertext = new Uint8Array(await object.arrayBuffer());
  if (ciphertext.byteLength !== chunk.ciphertextByteSize ||
      !timingSafeEqual(
        await sha256Bytes(ciphertext),
        decodeBase64Url(chunk.ciphertextSha256Base64Url, "chunk_ciphertext_sha256"),
      )) {
    throw new ApiError(503, "blob_chunk_object_corrupt");
  }
  const headers = securityHeaders();
  headers.set("Content-Type", "application/octet-stream");
  headers.set("Content-Length", String(ciphertext.byteLength));
  headers.set("X-Shinsou-Ciphertext-Sha256", chunk.ciphertextSha256Base64Url);
  return new Response(ciphertext, { status: 200, headers });
}

interface CheckpointEvidence {
  checkpointId: string;
  checkpointCiphertextSha256Base64Url: string;
  throughWorkspaceSeq: number;
}

function parseCheckpointEvidence(value: unknown): CheckpointEvidence {
  const evidence = requireObject(value, "checkpointEvidence");
  exactKeys(evidence, [
    "checkpointId", "checkpointCiphertextSha256Base64Url", "throughWorkspaceSeq",
  ], "checkpoint_evidence");
  return {
    checkpointId: requireUuid(
      requireString(evidence, "checkpointId", 36, 36),
      "checkpoint_id",
    ),
    checkpointCiphertextSha256Base64Url: requireSha256Base64Url(
      requireString(evidence, "checkpointCiphertextSha256Base64Url", 1, 128),
      "checkpoint_ciphertext_sha256",
    ),
    throughWorkspaceSeq: requireInteger(
      evidence,
      "throughWorkspaceSeq",
      0,
      Number.MAX_SAFE_INTEGER,
    ),
  };
}

async function requireStableCheckpointEvidence(
  env: Env,
  workspaceId: string,
  evidence: CheckpointEvidence,
  keyEpoch: number,
  minimumThroughSeq = 0,
): Promise<void> {
  const checkpoint = await first<{ found: number }>(env.DB, `
    SELECT 1 AS found FROM checkpoints
    WHERE workspace_id = ?1 AND checkpoint_id = ?2 AND ciphertext_sha256 = ?3
      AND through_seq = ?4 AND through_seq >= ?5 AND key_epoch = ?6
      AND schema_version = 2 AND status = 'stable'
  `, workspaceId, evidence.checkpointId,
  evidence.checkpointCiphertextSha256Base64Url, evidence.throughWorkspaceSeq,
  minimumThroughSeq, keyEpoch);
  if (!checkpoint) {
    throw new ApiError(409, "stable_checkpoint_evidence_invalid");
  }
}

export async function rewrapBlobEnvelope(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  const { value } = await readJson<JsonObject>(request, LIMITS.jsonBody);
  exactKeys(value, [
    "protocolVersion", "schemaVersion", "manifestId", "envelope", "checkpointEvidence",
  ], "blob_envelope_rewrap");
  requireBodyV2(env, value);
  const manifestId = requireUuid(
    requireString(value, "manifestId", 36, 36),
    "manifest_id",
  );
  const envelope = await parseDekEnvelope(value.envelope, blobId);
  if (envelope.previousEnvelopeSha256Base64Url === null) {
    throw new ApiError(400, "blob_rewrap_previous_envelope_required");
  }
  const evidence = parseCheckpointEvidence(value.checkpointEvidence);
  const manifest = await first<{
    manifestId: string;
    status: string;
    currentEnvelopeEpoch: number;
    currentEnvelopeSha256: string;
    activeKeyEpoch: number;
  }>(env.DB, `
    SELECT manifest.manifest_id AS manifestId, manifest.status,
           manifest.current_envelope_epoch AS currentEnvelopeEpoch,
           current_envelope.envelope_sha256 AS currentEnvelopeSha256,
           workspace.active_key_epoch AS activeKeyEpoch
    FROM encrypted_blob_manifests manifest
    JOIN workspaces workspace ON workspace.workspace_id = manifest.workspace_id
    JOIN blob_dek_envelopes current_envelope
      ON current_envelope.workspace_id = manifest.workspace_id
     AND current_envelope.blob_id = manifest.blob_id
     AND current_envelope.key_epoch = manifest.current_envelope_epoch
     AND current_envelope.status = 'current'
    WHERE manifest.workspace_id = ?1 AND manifest.blob_id = ?2
  `, principal.workspaceId, blobId);
  if (!manifest) throw new ApiError(404, "blob_manifest_not_found");
  if (manifest.manifestId !== manifestId) throw new ApiError(409, "blob_manifest_identity_mismatch");
  if (!["committed", "tombstoned"].includes(manifest.status)) {
    throw new ApiError(409, "blob_not_live");
  }
  if (manifest.activeKeyEpoch !== principal.keyEpoch || envelope.keyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_key_epoch_stale");
  }
  await requireStableCheckpointEvidence(
    env,
    principal.workspaceId,
    evidence,
    envelope.keyEpoch,
  );
  const existing = await first<EnvelopeRow>(env.DB, `
    SELECT envelope_version AS schemaVersion, blob_id AS blobId, key_epoch AS keyEpoch,
           cipher_suite AS cipherSuite, nonce AS nonceBase64Url,
           wrapped_dek AS wrappedDekBase64Url, envelope_sha256 AS envelopeSha256Base64Url,
           previous_envelope_sha256 AS previousEnvelopeSha256Base64Url,
           evidence_checkpoint_id AS evidenceCheckpointId,
           evidence_checkpoint_sha256 AS evidenceCheckpointSha256,
           evidence_through_seq AS evidenceThroughSeq, status
    FROM blob_dek_envelopes
    WHERE workspace_id = ?1 AND blob_id = ?2 AND key_epoch = ?3
  `, principal.workspaceId, blobId, envelope.keyEpoch);
  if (existing) {
    if (existing.envelopeSha256Base64Url !== envelope.envelopeSha256Base64Url ||
        existing.nonceBase64Url !== envelope.nonceBase64Url ||
        existing.wrappedDekBase64Url !== envelope.wrappedDekBase64Url ||
        existing.previousEnvelopeSha256Base64Url !==
          envelope.previousEnvelopeSha256Base64Url ||
        existing.evidenceCheckpointId !== evidence.checkpointId ||
        existing.evidenceCheckpointSha256 !==
          evidence.checkpointCiphertextSha256Base64Url ||
        existing.evidenceThroughSeq !== evidence.throughWorkspaceSeq) {
      throw new ApiError(409, "blob_envelope_rewrap_conflict");
    }
    return envelopeResponse(existing);
  }
  if (envelope.keyEpoch <= manifest.currentEnvelopeEpoch ||
      envelope.previousEnvelopeSha256Base64Url !== manifest.currentEnvelopeSha256) {
    throw new ApiError(409, "blob_envelope_chain_mismatch");
  }
  try {
    await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_dek_envelopes(
      workspace_id, blob_id, key_epoch, envelope_version, cipher_suite, nonce, wrapped_dek,
      envelope_sha256, previous_envelope_sha256, evidence_checkpoint_id,
      evidence_checkpoint_sha256, evidence_through_seq, created_by_device_id, status, created_at
      , authorization_capability_id, authorization_device_auth_epoch,
      authorization_membership_auth_epoch, authorization_key_epoch, authorization_role
    ) VALUES (?1, ?2, ?3, 1, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, 'current', ?13,
              ?14, ?15, ?16, ?17, ?18)`)
      .bind(principal.workspaceId, blobId, envelope.keyEpoch, envelope.cipherSuite,
        envelope.nonceBase64Url, envelope.wrappedDekBase64Url,
        envelope.envelopeSha256Base64Url, envelope.previousEnvelopeSha256Base64Url,
        evidence.checkpointId, evidence.checkpointCiphertextSha256Base64Url,
        evidence.throughWorkspaceSeq, principal.deviceId, now, principal.capabilityId,
        principal.deviceAuthEpoch, principal.membershipAuthEpoch, principal.keyEpoch,
        principal.role)]);
  } catch (error) {
    const raced = await first<EnvelopeRow>(env.DB, `
      SELECT envelope_version AS schemaVersion, blob_id AS blobId, key_epoch AS keyEpoch,
             cipher_suite AS cipherSuite, nonce AS nonceBase64Url,
             wrapped_dek AS wrappedDekBase64Url,
             envelope_sha256 AS envelopeSha256Base64Url,
             previous_envelope_sha256 AS previousEnvelopeSha256Base64Url,
             evidence_checkpoint_id AS evidenceCheckpointId,
             evidence_checkpoint_sha256 AS evidenceCheckpointSha256,
             evidence_through_seq AS evidenceThroughSeq, status
      FROM blob_dek_envelopes
      WHERE workspace_id = ?1 AND blob_id = ?2 AND key_epoch = ?3
    `, principal.workspaceId, blobId, envelope.keyEpoch);
    if (raced && raced.envelopeSha256Base64Url === envelope.envelopeSha256Base64Url &&
        raced.nonceBase64Url === envelope.nonceBase64Url &&
        raced.wrappedDekBase64Url === envelope.wrappedDekBase64Url &&
        raced.previousEnvelopeSha256Base64Url === envelope.previousEnvelopeSha256Base64Url &&
        raced.evidenceCheckpointId === evidence.checkpointId &&
        raced.evidenceCheckpointSha256 === evidence.checkpointCiphertextSha256Base64Url &&
        raced.evidenceThroughSeq === evidence.throughWorkspaceSeq) {
      return envelopeResponse(raced);
    }
    throw error;
  }
  return envelopeResponse(envelope);
}

type BlobTombstoneStatus = "waiting" | "cancelled" | "deleting" | "deleted";

interface BlobTombstoneCreationRow {
  tombstoneId: string;
  blobId: string;
  manifestId: string;
  referenceThroughSeq: number;
  requestedCreatedAt: number;
  executeAfter: number;
  status: BlobTombstoneStatus;
}

function tombstoneDisposition(status: BlobTombstoneStatus):
  "active" | "cancelled" | "reupload_required" {
  if (status === "waiting") return "active";
  if (status === "cancelled") return "cancelled";
  return "reupload_required";
}

function tombstoneCreationResponse(
  env: Pick<Env, "INSTANCE_ID">,
  workspaceId: string,
  row: BlobTombstoneCreationRow,
): Record<string, unknown> {
  return {
    protocolVersion: 2,
    schemaVersion: 2,
    handle: {
      protocolVersion: 2,
      schemaVersion: 2,
      instanceId: env.INSTANCE_ID.toLowerCase(),
      workspaceId,
      tombstoneId: row.tombstoneId,
      blobId: row.blobId,
      manifestId: row.manifestId,
      referenceThroughWorkspaceSeq: row.referenceThroughSeq,
      requestedCreatedAtEpochMillis: row.requestedCreatedAt,
      executeAfterEpochMillis: row.executeAfter,
    },
    disposition: tombstoneDisposition(row.status),
  };
}

async function loadTombstoneBoundary(
  env: Env,
  workspaceId: string,
  blobId: string,
  manifestId: string,
  throughWorkspaceSeq: number,
): Promise<BlobTombstoneCreationRow | null> {
  return first<BlobTombstoneCreationRow>(env.DB, `
    SELECT tombstone_id AS tombstoneId, blob_id AS blobId, manifest_id AS manifestId,
           reference_through_seq AS referenceThroughSeq,
           requested_created_at AS requestedCreatedAt, execute_after AS executeAfter, status
    FROM blob_tombstones
    WHERE workspace_id = ?1 AND blob_id = ?2 AND manifest_id = ?3
      AND reference_through_seq = ?4
  `, workspaceId, blobId, manifestId, throughWorkspaceSeq);
}

export async function createBlobTombstone(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  const { value } = await readJson<JsonObject>(request, LIMITS.jsonBody);
  exactKeys(value, [
    "protocolVersion", "schemaVersion", "tombstoneId", "blobId", "manifestId",
    "throughWorkspaceSeq", "createdAtEpochMillis",
  ], "blob_tombstone");
  requireBodyV2(env, value);
  const requestBlobId = requireUuid(requireString(value, "blobId", 36, 36), "blob_id");
  if (requestBlobId !== blobId) throw new ApiError(400, "blob_tombstone_path_mismatch");
  const tombstoneId = requireUuid(
    requireString(value, "tombstoneId", 36, 36),
    "tombstone_id",
  );
  const manifestId = requireUuid(
    requireString(value, "manifestId", 36, 36),
    "manifest_id",
  );
  const throughWorkspaceSeq = requireInteger(
    value,
    "throughWorkspaceSeq",
    0,
    Number.MAX_SAFE_INTEGER,
  );
  const requestedCreatedAt = requireInteger(
    value,
    "createdAtEpochMillis",
    0,
    Number.MAX_SAFE_INTEGER,
  );
  const existing = await loadTombstoneBoundary(
    env,
    principal.workspaceId,
    blobId,
    manifestId,
    throughWorkspaceSeq,
  );
  if (existing) {
    // Random ids and requested timestamps are provisional. Every device adopts this winner for
    // the same immutable removal boundary before signing acknowledgements.
    return tombstoneCreationResponse(env, principal.workspaceId, existing);
  }
  const active = await first<{ tombstoneId: string; referenceThroughSeq: number }>(env.DB, `
    SELECT tombstone_id AS tombstoneId, reference_through_seq AS referenceThroughSeq
    FROM blob_tombstones
    WHERE workspace_id = ?1 AND blob_id = ?2 AND status IN ('waiting', 'deleting')
  `, principal.workspaceId, blobId);
  if (active) {
    throw new ApiError(409, "blob_tombstone_active_boundary_conflict", undefined, {
      tombstoneId: active.tombstoneId,
      referenceThroughWorkspaceSeq: active.referenceThroughSeq,
    });
  }
  const manifest = await loadCommittedManifest(env, principal.workspaceId, blobId);
  if (!manifest) throw new ApiError(404, "blob_manifest_not_found");
  if (manifest.status !== "committed") throw new ApiError(409, "blob_not_live");
  if (manifest.manifestId !== manifestId) throw new ApiError(409, "blob_manifest_identity_mismatch");
  if (manifest.activeKeyEpoch !== principal.keyEpoch ||
      manifest.currentEnvelopeEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_key_epoch_stale");
  }
  const workspace = await first<{ headSeq: number }>(env.DB, `
    SELECT head_seq AS headSeq FROM workspaces WHERE workspace_id = ?1
  `, principal.workspaceId);
  if (!workspace || throughWorkspaceSeq > workspace.headSeq) {
    throw new ApiError(409, "blob_tombstone_watermark_invalid", "The reference tombstone is not committed", {
      headSeq: workspace?.headSeq ?? 0,
    });
  }
  const executeAfter = now + configuredSeconds(
    env.BLOB_GC_SAFETY_SECONDS,
    86_400,
    "blob_gc_safety",
  ) * 1000;
  try {
    await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_tombstones(
      tombstone_id, workspace_id, blob_id, manifest_id, manifest_ciphertext_sha256,
      reference_through_seq, evidence_checkpoint_id, evidence_checkpoint_sha256,
      key_epoch, created_by_device_id, status, execute_after, requested_created_at, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, NULL, NULL, ?7, ?8, 'waiting', ?9, ?10, ?11)`)
      .bind(tombstoneId, principal.workspaceId, blobId, manifestId,
        manifest.manifestCiphertextSha256, throughWorkspaceSeq, principal.keyEpoch,
        principal.deviceId, executeAfter, requestedCreatedAt, now)]);
  } catch (error) {
    const raced = await loadTombstoneBoundary(
      env,
      principal.workspaceId,
      blobId,
      manifestId,
      throughWorkspaceSeq,
    );
    if (raced) return tombstoneCreationResponse(env, principal.workspaceId, raced);
    throw error;
  }
  return tombstoneCreationResponse(env, principal.workspaceId, {
    tombstoneId,
    blobId,
    manifestId,
    referenceThroughSeq: throughWorkspaceSeq,
    requestedCreatedAt,
    executeAfter,
    status: "waiting",
  });
}

export function blobTombstoneAckSignatureMessage(
  env: Pick<Env, "INSTANCE_ID">,
  workspaceId: string,
  blobId: string,
  tombstoneId: string,
  evidence: CheckpointEvidence,
  validatorDeviceId: string,
): Uint8Array {
  return versionedDomainSeparatedMessage(
    2,
    "blob-tombstone-ack",
    utf8Bytes(canonicalJson({
      protocolVersion: 2,
      schemaVersion: 2,
      instanceId: env.INSTANCE_ID.toLowerCase(),
      workspaceId,
      blobId,
      tombstoneId,
      checkpointId: evidence.checkpointId,
      checkpointCiphertextSha256Base64Url:
        evidence.checkpointCiphertextSha256Base64Url,
      throughWorkspaceSeq: evidence.throughWorkspaceSeq,
      validatorDeviceId,
    })),
  );
}

export function blobTombstoneRevivalSignatureMessage(
  env: Pick<Env, "INSTANCE_ID">,
  workspaceId: string,
  blobId: string,
  manifestId: string,
  tombstoneId: string,
  evidence: CheckpointEvidence,
  validatorDeviceId: string,
): Uint8Array {
  return versionedDomainSeparatedMessage(
    2,
    "blob-tombstone-revival",
    utf8Bytes(canonicalJson({
      protocolVersion: 2,
      schemaVersion: 2,
      instanceId: env.INSTANCE_ID.toLowerCase(),
      workspaceId,
      blobId,
      manifestId,
      tombstoneId,
      checkpointId: evidence.checkpointId,
      checkpointCiphertextSha256Base64Url:
        evidence.checkpointCiphertextSha256Base64Url,
      throughWorkspaceSeq: evidence.throughWorkspaceSeq,
      validatorDeviceId,
    })),
  );
}

export async function acknowledgeBlobTombstone(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
  now = Date.now(),
): Promise<void> {
  requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  const { value } = await readJson<JsonObject>(request, LIMITS.jsonBody);
  exactKeys(value, [
    "protocolVersion", "schemaVersion", "tombstoneId", "checkpointId",
    "checkpointCiphertextSha256Base64Url", "throughWorkspaceSeq", "signatureBase64Url",
  ], "blob_tombstone_ack");
  requireBodyV2(env, value);
  const tombstoneId = requireUuid(
    requireString(value, "tombstoneId", 36, 36),
    "tombstone_id",
  );
  const evidence: CheckpointEvidence = {
    checkpointId: requireUuid(
      requireString(value, "checkpointId", 36, 36),
      "checkpoint_id",
    ),
    checkpointCiphertextSha256Base64Url: requireSha256Base64Url(
      requireString(value, "checkpointCiphertextSha256Base64Url", 1, 128),
      "checkpoint_ciphertext_sha256",
    ),
    throughWorkspaceSeq: requireInteger(
      value,
      "throughWorkspaceSeq",
      0,
      Number.MAX_SAFE_INTEGER,
    ),
  };
  const signature = requireString(value, "signatureBase64Url", 1, 128);
  if (decodeBase64Url(signature, "blob_tombstone_ack_signature").byteLength !== 64) {
    throw new ApiError(400, "invalid_blob_tombstone_ack_signature");
  }
  const tombstone = await first<{
    referenceThroughSeq: number;
    keyEpoch: number;
    status: string;
    activeKeyEpoch: number;
  }>(env.DB, `
    SELECT tombstone.reference_through_seq AS referenceThroughSeq,
           tombstone.key_epoch AS keyEpoch, tombstone.status,
           workspace.active_key_epoch AS activeKeyEpoch
    FROM blob_tombstones tombstone
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    WHERE tombstone.workspace_id = ?1 AND tombstone.blob_id = ?2
      AND tombstone.tombstone_id = ?3
  `, principal.workspaceId, blobId, tombstoneId);
  if (!tombstone) throw new ApiError(404, "blob_tombstone_not_found");
  if (tombstone.status !== "waiting") throw new ApiError(409, "blob_tombstone_not_acknowledgeable");
  if (tombstone.activeKeyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_key_epoch_stale");
  }
  await requireStableCheckpointEvidence(
    env,
    principal.workspaceId,
    evidence,
    principal.keyEpoch,
    tombstone.referenceThroughSeq,
  );
  const message = blobTombstoneAckSignatureMessage(
    env,
    principal.workspaceId,
    blobId,
    tombstoneId,
    evidence,
    principal.deviceId,
  );
  if (!await verifyEd25519(principal.signingPublicKey, signature, message)) {
    throw new ApiError(401, "blob_tombstone_ack_signature_invalid");
  }
  const existing = await first<{
    checkpointId: string;
    checkpointSha256: string;
    throughSeq: number;
    signature: string;
  }>(env.DB, `
    SELECT evidence_checkpoint_id AS checkpointId,
           evidence_checkpoint_sha256 AS checkpointSha256,
           through_seq AS throughSeq, device_signature AS signature
    FROM blob_tombstone_acks
    WHERE tombstone_id = ?1 AND validator_device_id = ?2
  `, tombstoneId, principal.deviceId);
  if (existing) {
    if (existing.checkpointId === evidence.checkpointId &&
        existing.checkpointSha256 === evidence.checkpointCiphertextSha256Base64Url &&
        existing.throughSeq === evidence.throughWorkspaceSeq &&
        existing.signature === signature) {
      return;
    }
    try {
      await atomicBatch(env.DB, [env.DB.prepare(`UPDATE blob_tombstone_acks
        SET evidence_checkpoint_id = ?3, evidence_checkpoint_sha256 = ?4,
            through_seq = ?5, device_signature = ?6, created_at = ?7
        WHERE tombstone_id = ?1 AND validator_device_id = ?2`)
        .bind(tombstoneId, principal.deviceId, evidence.checkpointId,
          evidence.checkpointCiphertextSha256Base64Url, evidence.throughWorkspaceSeq,
          signature, now)]);
    } catch (error) {
      const raced = await first<{
        checkpointId: string;
        checkpointSha256: string;
        throughSeq: number;
        signature: string;
      }>(env.DB, `
        SELECT evidence_checkpoint_id AS checkpointId,
               evidence_checkpoint_sha256 AS checkpointSha256,
               through_seq AS throughSeq, device_signature AS signature
        FROM blob_tombstone_acks
        WHERE tombstone_id = ?1 AND validator_device_id = ?2
      `, tombstoneId, principal.deviceId);
      if (raced?.checkpointId === evidence.checkpointId &&
          raced.checkpointSha256 === evidence.checkpointCiphertextSha256Base64Url &&
          raced.throughSeq === evidence.throughWorkspaceSeq && raced.signature === signature) {
        return;
      }
      throw error;
    }
    const refreshed = await first<{
      checkpointId: string;
      checkpointSha256: string;
      throughSeq: number;
      signature: string;
    }>(env.DB, `
      SELECT evidence_checkpoint_id AS checkpointId,
             evidence_checkpoint_sha256 AS checkpointSha256,
             through_seq AS throughSeq, device_signature AS signature
      FROM blob_tombstone_acks
      WHERE tombstone_id = ?1 AND validator_device_id = ?2
    `, tombstoneId, principal.deviceId);
    if (refreshed?.checkpointId !== evidence.checkpointId ||
        refreshed.checkpointSha256 !== evidence.checkpointCiphertextSha256Base64Url ||
        refreshed.throughSeq !== evidence.throughWorkspaceSeq ||
        refreshed.signature !== signature) {
      throw new ApiError(503, "blob_tombstone_ack_refresh_not_persisted");
    }
    return;
  }
  try {
    await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_tombstone_acks(
      tombstone_id, workspace_id, blob_id, validator_device_id,
      evidence_checkpoint_id, evidence_checkpoint_sha256, through_seq,
      device_signature, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`)
      .bind(tombstoneId, principal.workspaceId, blobId, principal.deviceId,
        evidence.checkpointId, evidence.checkpointCiphertextSha256Base64Url,
        evidence.throughWorkspaceSeq, signature, now)]);
  } catch (error) {
    const raced = await first<{ signature: string }>(env.DB, `
      SELECT device_signature AS signature FROM blob_tombstone_acks
      WHERE tombstone_id = ?1 AND validator_device_id = ?2
        AND evidence_checkpoint_id = ?3 AND evidence_checkpoint_sha256 = ?4
        AND through_seq = ?5
    `, tombstoneId, principal.deviceId, evidence.checkpointId,
    evidence.checkpointCiphertextSha256Base64Url, evidence.throughWorkspaceSeq);
    if (raced?.signature === signature) return;
    throw error;
  }
}

interface BlobTombstoneRevivalFacts {
  manifestId: string;
  referenceThroughSeq: number;
  status: BlobTombstoneStatus;
  cancelledAt: number | null;
  activeKeyEpoch: number;
}

interface BlobTombstoneRevivalRow {
  manifestId: string;
  checkpointId: string;
  checkpointSha256: string;
  throughSeq: number;
  signature: string;
  outcome: "cancelled" | "reupload_required";
}

async function loadBlobTombstoneRevivalFacts(
  env: Env,
  workspaceId: string,
  blobId: string,
  tombstoneId: string,
): Promise<BlobTombstoneRevivalFacts | null> {
  return first<BlobTombstoneRevivalFacts>(env.DB, `
    SELECT tombstone.manifest_id AS manifestId,
           tombstone.reference_through_seq AS referenceThroughSeq,
           tombstone.status, tombstone.cancelled_at AS cancelledAt,
           workspace.active_key_epoch AS activeKeyEpoch
    FROM blob_tombstones tombstone
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    WHERE tombstone.workspace_id = ?1 AND tombstone.blob_id = ?2
      AND tombstone.tombstone_id = ?3
  `, workspaceId, blobId, tombstoneId);
}

function blobTombstoneRevivalResponse(
  tombstoneId: string,
  blobId: string,
  manifestId: string,
  outcome: "cancelled" | "reupload_required",
  cancelledAt: number | null,
): Record<string, unknown> {
  if (outcome === "cancelled" && cancelledAt === null) {
    throw new ApiError(503, "blob_tombstone_revival_not_persisted");
  }
  return {
    protocolVersion: 2,
    schemaVersion: 2,
    tombstoneId,
    blobId,
    manifestId,
    disposition: outcome,
    cancelledAtEpochMillis: outcome === "cancelled" ? cancelledAt : null,
  };
}

export async function reviveBlobReference(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  blobIdInput: string,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  requireBodyPlaneAvailable(env);
  const blobId = requireUuid(blobIdInput, "blob_id");
  const { value } = await readJson<JsonObject>(request, LIMITS.jsonBody);
  exactKeys(value, [
    "protocolVersion", "schemaVersion", "tombstoneId", "blobId", "manifestId",
    "checkpointId", "checkpointCiphertextSha256Base64Url", "throughWorkspaceSeq",
    "signatureBase64Url",
  ], "blob_tombstone_revival");
  requireBodyV2(env, value);
  const requestBlobId = requireUuid(requireString(value, "blobId", 36, 36), "blob_id");
  if (requestBlobId !== blobId) throw new ApiError(400, "blob_revival_path_mismatch");
  const tombstoneId = requireUuid(
    requireString(value, "tombstoneId", 36, 36),
    "tombstone_id",
  );
  const manifestId = requireUuid(
    requireString(value, "manifestId", 36, 36),
    "manifest_id",
  );
  const evidence: CheckpointEvidence = {
    checkpointId: requireUuid(
      requireString(value, "checkpointId", 36, 36),
      "checkpoint_id",
    ),
    checkpointCiphertextSha256Base64Url: requireSha256Base64Url(
      requireString(value, "checkpointCiphertextSha256Base64Url", 1, 128),
      "checkpoint_ciphertext_sha256",
    ),
    throughWorkspaceSeq: requireInteger(
      value,
      "throughWorkspaceSeq",
      0,
      Number.MAX_SAFE_INTEGER,
    ),
  };
  const signature = requireString(value, "signatureBase64Url", 1, 128);
  if (decodeBase64Url(signature, "blob_tombstone_revival_signature").byteLength !== 64) {
    throw new ApiError(400, "invalid_blob_tombstone_revival_signature");
  }
  let facts = await loadBlobTombstoneRevivalFacts(
    env,
    principal.workspaceId,
    blobId,
    tombstoneId,
  );
  if (!facts) throw new ApiError(404, "blob_tombstone_not_found");
  if (facts.manifestId !== manifestId) {
    throw new ApiError(409, "blob_manifest_identity_mismatch");
  }
  if (facts.activeKeyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_key_epoch_stale");
  }
  if (evidence.throughWorkspaceSeq <= facts.referenceThroughSeq) {
    throw new ApiError(409, "blob_revival_watermark_not_newer");
  }
  await requireStableCheckpointEvidence(
    env,
    principal.workspaceId,
    evidence,
    principal.keyEpoch,
    facts.referenceThroughSeq + 1,
  );
  const message = blobTombstoneRevivalSignatureMessage(
    env,
    principal.workspaceId,
    blobId,
    manifestId,
    tombstoneId,
    evidence,
    principal.deviceId,
  );
  if (!await verifyEd25519(principal.signingPublicKey, signature, message)) {
    throw new ApiError(401, "blob_tombstone_revival_signature_invalid");
  }
  const existing = await first<BlobTombstoneRevivalRow>(env.DB, `
    SELECT manifest_id AS manifestId, evidence_checkpoint_id AS checkpointId,
           evidence_checkpoint_sha256 AS checkpointSha256, through_seq AS throughSeq,
           device_signature AS signature, outcome
    FROM blob_tombstone_revivals
    WHERE tombstone_id = ?1 AND validator_device_id = ?2
  `, tombstoneId, principal.deviceId);
  if (existing) {
    if (existing.manifestId !== manifestId || existing.checkpointId !== evidence.checkpointId ||
        existing.checkpointSha256 !== evidence.checkpointCiphertextSha256Base64Url ||
        existing.throughSeq !== evidence.throughWorkspaceSeq || existing.signature !== signature) {
      throw new ApiError(409, "blob_tombstone_revival_conflict");
    }
    return blobTombstoneRevivalResponse(
      tombstoneId,
      blobId,
      manifestId,
      existing.outcome,
      facts.cancelledAt,
    );
  }

  for (let attempt = 0; attempt < 2; attempt++) {
    const outcome = facts.status === "waiting" || facts.status === "cancelled"
      ? "cancelled"
      : "reupload_required";
    try {
      await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_tombstone_revivals(
        tombstone_id, workspace_id, blob_id, manifest_id, validator_device_id,
        evidence_checkpoint_id, evidence_checkpoint_sha256, through_seq,
        device_signature, outcome, created_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)`)
        .bind(tombstoneId, principal.workspaceId, blobId, manifestId, principal.deviceId,
          evidence.checkpointId, evidence.checkpointCiphertextSha256Base64Url,
          evidence.throughWorkspaceSeq, signature, outcome, now)]);
      const persisted = await loadBlobTombstoneRevivalFacts(
        env,
        principal.workspaceId,
        blobId,
        tombstoneId,
      );
      if (!persisted) throw new ApiError(503, "blob_tombstone_revival_not_persisted");
      return blobTombstoneRevivalResponse(
        tombstoneId,
        blobId,
        manifestId,
        outcome,
        persisted.cancelledAt,
      );
    } catch (error) {
      const raced = await first<BlobTombstoneRevivalRow>(env.DB, `
        SELECT manifest_id AS manifestId, evidence_checkpoint_id AS checkpointId,
               evidence_checkpoint_sha256 AS checkpointSha256, through_seq AS throughSeq,
               device_signature AS signature, outcome
        FROM blob_tombstone_revivals
        WHERE tombstone_id = ?1 AND validator_device_id = ?2
      `, tombstoneId, principal.deviceId);
      if (raced && raced.manifestId === manifestId &&
          raced.checkpointId === evidence.checkpointId &&
          raced.checkpointSha256 === evidence.checkpointCiphertextSha256Base64Url &&
          raced.throughSeq === evidence.throughWorkspaceSeq && raced.signature === signature) {
        const persisted = await loadBlobTombstoneRevivalFacts(
          env,
          principal.workspaceId,
          blobId,
          tombstoneId,
        );
        if (!persisted) throw error;
        return blobTombstoneRevivalResponse(
          tombstoneId,
          blobId,
          manifestId,
          raced.outcome,
          persisted.cancelledAt,
        );
      }
      if (attempt === 1) throw error;
      facts = await loadBlobTombstoneRevivalFacts(
        env,
        principal.workspaceId,
        blobId,
        tombstoneId,
      );
      if (!facts) throw error;
    }
  }
  throw new ApiError(503, "blob_tombstone_revival_not_persisted");
}

interface GcReceiptRow {
  receiptId: string;
  blobId: string;
  deletedObjectCount: number;
  deletedCiphertextBytes: number;
  completedAt: number;
}

async function loadGcReceipt(
  env: Env,
  workspaceId: string,
  tombstoneId: string,
): Promise<GcReceiptRow | null> {
  return first<GcReceiptRow>(env.DB, `
    SELECT receipt.claim_id AS receiptId, receipt.blob_id AS blobId,
           receipt.deleted_object_count AS deletedObjectCount,
           receipt.deleted_ciphertext_bytes AS deletedCiphertextBytes,
           receipt.completed_at AS completedAt
    FROM blob_gc_receipts receipt
    WHERE receipt.workspace_id = ?1 AND receipt.tombstone_id = ?2
  `, workspaceId, tombstoneId);
}

function gcReceiptResponse(receipt: GcReceiptRow): Record<string, unknown> {
  return {
    receiptId: receipt.receiptId,
    blobId: receipt.blobId,
    deletedObjectCount: receipt.deletedObjectCount,
    deletedCiphertextBytes: receipt.deletedCiphertextBytes,
    completedAtEpochMillis: receipt.completedAt,
  };
}

export async function garbageCollectBlob(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  now = Date.now(),
): Promise<Record<string, unknown>> {
  requireWriter(principal);
  const bucket = requireBodyPlaneAvailable(env);
  const { value } = await readJson<JsonObject>(request, LIMITS.jsonBody);
  exactKeys(value, [
    "protocolVersion", "schemaVersion", "blobId", "tombstoneId",
  ], "blob_gc");
  requireBodyV2(env, value);
  const blobId = requireUuid(requireString(value, "blobId", 36, 36), "blob_id");
  const tombstoneId = requireUuid(
    requireString(value, "tombstoneId", 36, 36),
    "tombstone_id",
  );
  const completed = await loadGcReceipt(env, principal.workspaceId, tombstoneId);
  if (completed) {
    if (completed.blobId !== blobId) throw new ApiError(409, "blob_gc_identity_mismatch");
    return gcReceiptResponse(completed);
  }
  const facts = await first<{
    manifestId: string;
    manifestHash: string;
    manifestR2Key: string;
    manifestStatus: string;
    sessionId: string;
    chunkCount: number;
    totalBytes: number;
    referenceThroughSeq: number;
    executeAfter: number;
    tombstoneStatus: string;
    gcBeforeSeq: number;
    activeKeyEpoch: number;
  }>(env.DB, `
    SELECT manifest.manifest_id AS manifestId,
           manifest.manifest_ciphertext_sha256 AS manifestHash,
           manifest.manifest_r2_key AS manifestR2Key, manifest.status AS manifestStatus,
           manifest.session_id AS sessionId, manifest.chunk_count AS chunkCount,
           manifest.total_chunk_bytes + manifest.manifest_byte_size AS totalBytes,
           tombstone.reference_through_seq AS referenceThroughSeq,
           tombstone.execute_after AS executeAfter, tombstone.status AS tombstoneStatus,
           workspace.gc_before_seq AS gcBeforeSeq,
           workspace.active_key_epoch AS activeKeyEpoch
    FROM blob_tombstones tombstone
    JOIN encrypted_blob_manifests manifest
      ON manifest.workspace_id = tombstone.workspace_id AND manifest.blob_id = tombstone.blob_id
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    WHERE tombstone.workspace_id = ?1 AND tombstone.blob_id = ?2
      AND tombstone.tombstone_id = ?3
      AND manifest.manifest_id = tombstone.manifest_id
      AND manifest.manifest_ciphertext_sha256 = tombstone.manifest_ciphertext_sha256
  `, principal.workspaceId, blobId, tombstoneId);
  if (!facts) throw new ApiError(404, "blob_tombstone_not_found");
  if (!["waiting", "deleting"].includes(facts.tombstoneStatus) ||
      !["tombstoned", "deleting"].includes(facts.manifestStatus)) {
    throw new ApiError(409, "blob_gc_not_pending");
  }
  if (facts.activeKeyEpoch !== principal.keyEpoch) {
    throw new ApiError(409, "blob_key_epoch_stale");
  }
  if (facts.executeAfter > now) {
    throw new ApiError(409, "blob_gc_safety_window_pending", "The server safety window has not elapsed", {
      executeAfterEpochMillis: facts.executeAfter,
    });
  }
  if (facts.gcBeforeSeq < facts.referenceThroughSeq) {
    throw new ApiError(409, "blob_gc_recovery_boundary_pending", "The retained recovery boundary still references this event range", {
      gcBeforeSeq: facts.gcBeforeSeq,
      requiredThroughWorkspaceSeq: facts.referenceThroughSeq,
    });
  }
  const missingAcks = await first<{ count: number }>(env.DB, `
    SELECT COUNT(*) AS count
    FROM devices device
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership ON membership.user_id = device.user_id
    WHERE membership.workspace_id = ?1 AND membership.status = 'active'
      AND user.status = 'active' AND device.status = 'active'
      AND NOT EXISTS (
        SELECT 1 FROM blob_tombstone_acks ack
        JOIN checkpoints checkpoint
          ON checkpoint.workspace_id = ack.workspace_id
         AND checkpoint.checkpoint_id = ack.evidence_checkpoint_id
         AND checkpoint.ciphertext_sha256 = ack.evidence_checkpoint_sha256
        WHERE ack.tombstone_id = ?2 AND ack.validator_device_id = device.device_id
          AND ack.workspace_id = ?1 AND ack.blob_id = ?3
          AND ack.through_seq >= ?4 AND checkpoint.through_seq = ack.through_seq
          AND checkpoint.schema_version = 2 AND checkpoint.status = 'stable'
          AND checkpoint.key_epoch = ?5
      )
  `, principal.workspaceId, tombstoneId, blobId, facts.referenceThroughSeq,
  facts.activeKeyEpoch);
  if (!missingAcks || missingAcks.count !== 0) {
    throw new ApiError(409, "blob_gc_device_ack_pending", "Every active device must acknowledge a retained stable checkpoint", {
      missingDeviceCount: missingAcks?.count ?? 1,
    });
  }
  const chunkKeys = await all<{ r2Key: string }>(env.DB, `
    SELECT r2_key AS r2Key FROM blob_upload_session_chunks
    WHERE session_id = ?1 ORDER BY chunk_index
  `, facts.sessionId);
  if (chunkKeys.length !== facts.chunkCount) throw new ApiError(503, "blob_gc_object_plan_corrupt");
  const objectKeys = [...new Set([
    facts.manifestR2Key,
    ...chunkKeys.map((chunk) => chunk.r2Key),
  ])].sort();
  if (objectKeys.length !== facts.chunkCount + 1) {
    throw new ApiError(503, "blob_gc_object_plan_corrupt");
  }
  const objectListSha256 = await sha256Base64Url(
    utf8Bytes(canonicalJson(objectKeys)),
  );
  let claim = await first<{
    claimId: string;
    objectListSha256: string;
    objectCount: number;
    status: string;
  }>(env.DB, `
    SELECT claim_id AS claimId, object_list_sha256 AS objectListSha256,
           object_count AS objectCount, status
    FROM blob_gc_claims WHERE tombstone_id = ?1
  `, tombstoneId);
  if (!claim) {
    const claimId = randomUuid();
    try {
      await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_gc_claims(
        claim_id, tombstone_id, workspace_id, blob_id, object_list_sha256,
        object_count, status, claimed_by_device_id, capability_id,
        device_auth_epoch, membership_auth_epoch, key_epoch, authorized_role, claimed_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'deleting', ?7, ?8, ?9, ?10, ?11, ?12, ?13)`)
        .bind(claimId, tombstoneId, principal.workspaceId, blobId,
          objectListSha256, objectKeys.length, principal.deviceId, principal.capabilityId,
          principal.deviceAuthEpoch, principal.membershipAuthEpoch, principal.keyEpoch,
          principal.role, now)]);
      claim = { claimId, objectListSha256, objectCount: objectKeys.length, status: "deleting" };
    } catch (error) {
      claim = await first<{
        claimId: string;
        objectListSha256: string;
        objectCount: number;
        status: string;
      }>(env.DB, `
        SELECT claim_id AS claimId, object_list_sha256 AS objectListSha256,
               object_count AS objectCount, status
        FROM blob_gc_claims WHERE tombstone_id = ?1
      `, tombstoneId);
      if (!claim) throw error;
    }
  }
  if (claim.objectListSha256 !== objectListSha256 || claim.objectCount !== objectKeys.length) {
    throw new ApiError(409, "blob_gc_claim_conflict");
  }
  for (const key of objectKeys) await bucket.delete(key);
  let remaining = 0;
  for (let offset = 0; offset < objectKeys.length; offset += 32) {
    const heads = await Promise.all(objectKeys.slice(offset, offset + 32).map((key) => bucket.head(key)));
    remaining += heads.filter(Boolean).length;
  }
  if (remaining !== 0) {
    throw new ApiError(503, "blob_gc_r2_deletion_incomplete", "R2 deletion is retryable", {
      remainingObjectCount: remaining,
    });
  }
  try {
    await atomicBatch(env.DB, [env.DB.prepare(`INSERT INTO blob_gc_receipts(
      claim_id, tombstone_id, workspace_id, blob_id, manifest_ciphertext_sha256,
      object_list_sha256, deleted_object_count, deleted_ciphertext_bytes,
      verified_remaining_count, completed_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?9)`)
      .bind(claim.claimId, tombstoneId, principal.workspaceId, blobId,
        facts.manifestHash, objectListSha256, objectKeys.length, facts.totalBytes, now)]);
  } catch (error) {
    const raced = await loadGcReceipt(env, principal.workspaceId, tombstoneId);
    if (raced) return gcReceiptResponse(raced);
    throw error;
  }
  const receipt = await loadGcReceipt(env, principal.workspaceId, tombstoneId);
  if (!receipt) throw new ApiError(503, "blob_gc_receipt_not_persisted");
  return gcReceiptResponse(receipt);
}
