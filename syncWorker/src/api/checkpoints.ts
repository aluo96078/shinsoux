import {
  ApiError,
  readBytes,
  readJson,
  requireInteger,
  requireString,
  utf8Bytes,
} from "../http.ts";
import type { CapabilityPrincipal, Env } from "../runtime.ts";
import {
  bytesToHex,
  canonicalJson,
  decodeBase64Url,
  domainSeparatedMessage,
  encodeBase64Url,
  randomUuid,
  sha256Base64Url,
  verifyEd25519,
} from "../crypto/base.ts";
import {
  checkpointObjectKey,
  envelopeSignatureMessage,
  LIMITS,
  parseCheckpointHeader,
  requireUuid,
  validateCheckpointReplayAck,
} from "../protocol.ts";
import { affectedRows, all, atomicBatch, first, returning, run } from "../storage/d1.ts";
import {
  CANCEL_ROTATION_BLOCKED_CHECKPOINT_LEASE_SQL,
  CLEAR_REJECTED_CHECKPOINT_POINTER_SQL,
  COMMIT_CHECKPOINT_SQL,
  CREATE_CHECKPOINT_LEASE_SQL,
  INSERT_CHECKPOINT_ACK_SQL,
  MARK_CHECKPOINT_UPLOADED_SQL,
  PROMOTE_CHECKPOINT_SQL,
  REJECT_CHECKPOINT_SQL,
} from "../storage/sql.ts";
import { requireWritableEnvelopeVersions } from "../versions.ts";

const LEASE_TTL_MS = 10 * 60_000;

interface LeaseRow {
  leaseId: string;
  workspaceId: string;
  checkpointId: string;
  ciphertextSha256: string;
  throughSeq: number;
  keyEpoch: number;
  uploaderDeviceId: string;
  capabilityId: string;
  reservedBytes: number;
  actualByteSize: number | null;
  r2Key: string | null;
  status: string;
  expiresAt: number;
}

async function loadLease(
  env: Env,
  workspaceId: string,
  checkpointId: string,
  hash: string,
): Promise<LeaseRow | null> {
  return first<LeaseRow>(env.DB, `
    SELECT lease_id AS leaseId, workspace_id AS workspaceId, checkpoint_id AS checkpointId,
           ciphertext_sha256 AS ciphertextSha256, through_seq AS throughSeq,
           key_epoch AS keyEpoch, uploader_device_id AS uploaderDeviceId,
           capability_id AS capabilityId, reserved_bytes AS reservedBytes,
           actual_byte_size AS actualByteSize, r2_key AS r2Key,
           status, expires_at AS expiresAt
    FROM checkpoint_leases
    WHERE workspace_id = ?1 AND checkpoint_id = ?2 AND ciphertext_sha256 = ?3
  `, workspaceId, checkpointId, hash);
}

async function cleanupExpiredLeases(env: Env, workspaceId: string, now: number): Promise<void> {
  const expired = await all<LeaseRow>(env.DB, `
    SELECT lease_id AS leaseId, workspace_id AS workspaceId, checkpoint_id AS checkpointId,
           ciphertext_sha256 AS ciphertextSha256, through_seq AS throughSeq,
           key_epoch AS keyEpoch, uploader_device_id AS uploaderDeviceId,
           capability_id AS capabilityId, reserved_bytes AS reservedBytes,
           actual_byte_size AS actualByteSize, r2_key AS r2Key,
           status, expires_at AS expiresAt
    FROM checkpoint_leases
    WHERE workspace_id = ?1 AND status IN ('active', 'uploaded') AND expires_at <= ?2
  `, workspaceId, now);
  for (const lease of expired) {
    const derivedKey = checkpointObjectKey(
      lease.workspaceId,
      lease.throughSeq,
      lease.checkpointId,
      bytesToHex(decodeBase64Url(lease.ciphertextSha256, "ciphertext_sha256")),
    );
    try {
      await env.CHECKPOINTS.delete(lease.r2Key ?? derivedKey);
      await run(env.DB, `
        UPDATE checkpoint_leases SET status = 'expired'
        WHERE lease_id = ?1 AND status IN ('active', 'uploaded') AND expires_at <= ?2
      `, lease.leaseId, now);
    } catch {
      // Keep the reservation and active lease until orphan cleanup succeeds.
    }
  }
}

async function cleanupRejectedCheckpoints(env: Env, workspaceId: string): Promise<void> {
  const rejected = await all<{ checkpointId: string; r2Key: string }>(env.DB, `
    SELECT checkpoint_id AS checkpointId, r2_key AS r2Key FROM checkpoints
    WHERE workspace_id = ?1 AND status = 'rejected'
  `, workspaceId);
  for (const checkpoint of rejected) {
    try {
      await env.CHECKPOINTS.delete(checkpoint.r2Key);
      await run(env.DB, `UPDATE checkpoints SET status = 'deleted'
        WHERE workspace_id = ?1 AND checkpoint_id = ?2 AND status = 'rejected'`,
      workspaceId, checkpoint.checkpointId);
    } catch {
      // Rejected objects are retryable and never referenced by a stable pointer.
    }
  }
}

/**
 * Cancels an old-epoch lease only after D1 has durably entered the rotation-required state.
 * The status transition releases its quota reservation through the migration trigger; an uploaded
 * R2 object is no longer authoritative and is removed on a best-effort basis.
 */
async function cancelRotationBlockedLease(env: Env, lease: LeaseRow): Promise<boolean> {
  const cancelled = await returning<{ r2Key?: string | null }>(
    env.DB,
    CANCEL_ROTATION_BLOCKED_CHECKPOINT_LEASE_SQL,
    lease.leaseId,
  );
  if (!cancelled) return false;
  const r2Key = cancelled.r2Key ?? lease.r2Key;
  if (r2Key) {
    try {
      await env.CHECKPOINTS.delete(r2Key);
    } catch {
      // The cancelled lease cannot be committed. A transient R2 orphan is safe and retryable by
      // normal bucket lifecycle/operations cleanup without retaining workspace quota.
    }
  }
  return true;
}

export async function createCheckpointLease(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  now = Date.now(),
): Promise<unknown> {
  if (principal.role === "reader") throw new ApiError(403, "workspace_write_forbidden");
  const { value } = await readJson(request, 16 * 1024);
  const checkpointId = requireUuid(value.checkpointId, "checkpoint_id");
  const ciphertextSha256 = requireString(value, "ciphertextSha256", 43, 43);
  if (decodeBase64Url(ciphertextSha256, "ciphertext_sha256").byteLength !== 32) {
    throw new ApiError(400, "invalid_ciphertext_sha256");
  }
  const expectedByteSize = requireInteger(
    value,
    "expectedByteSize",
    1,
    LIMITS.checkpointCiphertext,
  );
  const throughSeq = requireInteger(value, "throughWorkspaceSeq", 0, Number.MAX_SAFE_INTEGER);
  await cleanupExpiredLeases(env, principal.workspaceId, now);
  await cleanupRejectedCheckpoints(env, principal.workspaceId);
  const leaseId = randomUuid();
  const expiresAt = now + LEASE_TTL_MS;
  const row = await returning<{
    leaseId?: string;
    lease_id?: string;
    throughSeq?: number;
    through_seq?: number;
    keyEpoch?: number;
    key_epoch?: number;
    expiresAt?: number;
    expires_at?: number;
  }>(
    env.DB,
    CREATE_CHECKPOINT_LEASE_SQL,
    leaseId,
    principal.workspaceId,
    checkpointId,
    ciphertextSha256,
    expectedByteSize,
    expiresAt,
    now,
    principal.capabilityId,
    throughSeq,
  );
  if (!row) {
    const workspace = await first<{
      headSeq: number;
      candidateCheckpointId: string | null;
      committedBytes: number;
      reservedBytes: number;
      maximumBytes: number;
      rotationRequired: number;
    }>(env.DB, `
      SELECT w.head_seq AS headSeq, w.candidate_checkpoint_id AS candidateCheckpointId,
             w.committed_bytes AS committedBytes, w.reserved_bytes AS reservedBytes,
             q.max_workspace_bytes AS maximumBytes,
             w.rotation_required AS rotationRequired
      FROM workspaces w JOIN users u ON u.user_id = w.owner_user_id
      JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
      WHERE w.workspace_id = ?1
    `, principal.workspaceId);
    if (workspace?.rotationRequired === 1) {
      throw new ApiError(409, "checkpoint_rotation_required");
    }
    if (workspace?.headSeq !== throughSeq) {
      throw new ApiError(409, "checkpoint_watermark_changed", "Rebuild from the returned head", {
        headSeq: workspace?.headSeq,
      });
    }
    if (workspace?.candidateCheckpointId) throw new ApiError(409, "checkpoint_candidate_pending");
    if (workspace && workspace.committedBytes + workspace.reservedBytes + expectedByteSize > workspace.maximumBytes) {
      throw new ApiError(507, "workspace_quota_exceeded");
    }
    throw new ApiError(409, "checkpoint_lease_unavailable");
  }
  return {
    leaseId: row.leaseId ?? row.lease_id,
    checkpointId,
    ciphertextSha256,
    throughWorkspaceSeq: row.throughSeq ?? row.through_seq,
    keyEpoch: row.keyEpoch ?? row.key_epoch,
    expiresAt: row.expiresAt ?? row.expires_at,
  };
}

export async function uploadCheckpoint(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  checkpointIdInput: string,
  now = Date.now(),
): Promise<unknown> {
  if (principal.role === "reader") throw new ApiError(403, "workspace_write_forbidden");
  const checkpointId = requireUuid(checkpointIdInput, "checkpoint_id");
  const headerEncoded = request.headers.get("X-Shinsou-Header-Cbor") ?? "";
  const ciphertextSha256 = request.headers.get("X-Shinsou-Ciphertext-Sha256") ?? "";
  const signature = request.headers.get("X-Shinsou-Device-Signature") ?? "";
  const headerCbor = decodeBase64Url(headerEncoded, "header_cbor");
  const hashBytes = decodeBase64Url(ciphertextSha256, "ciphertext_sha256");
  if (hashBytes.byteLength !== 32) throw new ApiError(400, "invalid_ciphertext_sha256");
  const ciphertext = await readBytes(request, LIMITS.checkpointCiphertext);
  if (!ciphertext.byteLength) throw new ApiError(400, "empty_checkpoint");
  if (await sha256Base64Url(ciphertext) !== ciphertextSha256) {
    throw new ApiError(400, "ciphertext_hash_mismatch");
  }
  const header = parseCheckpointHeader(headerCbor);
  requireWritableEnvelopeVersions(env, header.protocolVersion, header.schemaVersion);
  if (header.instanceId !== env.INSTANCE_ID.toLowerCase() ||
      header.workspaceId !== principal.workspaceId ||
      header.checkpointId !== checkpointId ||
      header.deviceId !== principal.deviceId) {
    throw new ApiError(403, "checkpoint_tenant_binding_mismatch");
  }
  if (!(await verifyEd25519(
    principal.signingPublicKey,
    signature,
    envelopeSignatureMessage("checkpoint", headerCbor, hashBytes),
  ))) throw new ApiError(401, "checkpoint_signature_invalid");
  const lease = await loadLease(env, principal.workspaceId, checkpointId, ciphertextSha256);
  if (!lease || lease.uploaderDeviceId !== principal.deviceId ||
      lease.expiresAt <= now ||
      !["active", "uploaded"].includes(lease.status)) {
    throw new ApiError(409, "checkpoint_lease_invalid");
  }
  if (await cancelRotationBlockedLease(env, lease)) {
    throw new ApiError(409, "checkpoint_rotation_required");
  }
  if (header.throughWorkspaceSeq !== lease.throughSeq || header.keyEpoch !== lease.keyEpoch) {
    throw new ApiError(409, "checkpoint_lease_binding_mismatch");
  }
  if (ciphertext.byteLength > lease.reservedBytes) throw new ApiError(507, "checkpoint_reservation_exceeded");
  const stable = await first<{ ciphertextSha256: string }>(env.DB, `
    SELECT c.ciphertext_sha256 AS ciphertextSha256 FROM workspaces w
    JOIN checkpoints c ON c.workspace_id = w.workspace_id AND c.checkpoint_id = w.stable_checkpoint_id
    WHERE w.workspace_id = ?1
  `, principal.workspaceId);
  if (header.previousStableCheckpointHash !== (stable?.ciphertextSha256 ?? "")) {
    throw new ApiError(409, "checkpoint_chain_changed");
  }
  const r2Key = checkpointObjectKey(
    principal.workspaceId,
    lease.throughSeq,
    checkpointId,
    bytesToHex(hashBytes),
  );
  if (lease.status === "uploaded") {
    if (lease.r2Key !== r2Key || lease.actualByteSize !== ciphertext.byteLength) {
      throw new ApiError(409, "checkpoint_upload_conflict");
    }
    return { checkpointId, ciphertextSha256, byteSize: ciphertext.byteLength, uploaded: true, duplicate: true };
  }
  const put = await env.CHECKPOINTS.put(r2Key, ciphertext, {
    onlyIf: { etagDoesNotMatch: "*" },
    httpMetadata: { contentType: "application/octet-stream", cacheControl: "private, no-store" },
    customMetadata: {
      workspaceId: principal.workspaceId,
      checkpointId,
      throughSeq: String(lease.throughSeq),
      keyEpoch: String(lease.keyEpoch),
      ciphertextSha256,
      headerCbor: headerEncoded,
      deviceSignature: signature,
    },
  });
  if (!put) {
    const existing = await env.CHECKPOINTS.head(r2Key);
    if (!existing || existing.size !== ciphertext.byteLength ||
        existing.customMetadata?.ciphertextSha256 !== ciphertextSha256) {
      throw new ApiError(409, "checkpoint_object_conflict");
    }
  }
  const updated = await returning<{ leaseId?: string }>(
    env.DB,
    MARK_CHECKPOINT_UPLOADED_SQL,
    principal.workspaceId, checkpointId, ciphertextSha256, ciphertext.byteLength,
    r2Key, now, principal.deviceId,
  );
  if (!updated) {
    if (put) await env.CHECKPOINTS.delete(r2Key);
    if (await cancelRotationBlockedLease(env, lease)) {
      throw new ApiError(409, "checkpoint_rotation_required");
    }
    throw new ApiError(409, "checkpoint_lease_expired_during_upload");
  }
  return { checkpointId, ciphertextSha256, byteSize: ciphertext.byteLength, uploaded: true, duplicate: false };
}

export async function commitCheckpoint(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  checkpointIdInput: string,
  now = Date.now(),
): Promise<unknown> {
  if (principal.role === "reader") throw new ApiError(403, "workspace_write_forbidden");
  const checkpointId = requireUuid(checkpointIdInput, "checkpoint_id");
  const { value } = await readJson(request, 8 * 1024);
  const ciphertextSha256 = requireString(value, "ciphertextSha256", 43, 43);
  const lease = await loadLease(env, principal.workspaceId, checkpointId, ciphertextSha256);
  if (!lease || lease.status !== "uploaded" || lease.expiresAt <= now || !lease.r2Key) {
    throw new ApiError(409, "checkpoint_upload_not_ready");
  }
  if (await cancelRotationBlockedLease(env, lease)) {
    throw new ApiError(409, "checkpoint_rotation_required");
  }
  const object = await env.CHECKPOINTS.head(lease.r2Key);
  if (!object || object.size !== lease.actualByteSize ||
      object.customMetadata?.workspaceId !== principal.workspaceId ||
      object.customMetadata?.checkpointId !== checkpointId ||
      object.customMetadata?.ciphertextSha256 !== ciphertextSha256) {
    throw new ApiError(409, "checkpoint_object_invalid");
  }
  const headerEncoded = object.customMetadata?.headerCbor ?? "";
  const signature = object.customMetadata?.deviceSignature ?? "";
  const headerCbor = decodeBase64Url(headerEncoded, "header_cbor");
  const header = parseCheckpointHeader(headerCbor);
  requireWritableEnvelopeVersions(env, header.protocolVersion, header.schemaVersion);
  const inserted = await returning<Record<string, unknown>>(
    env.DB,
    COMMIT_CHECKPOINT_SQL,
    principal.workspaceId,
    checkpointId,
    ciphertextSha256,
    headerCbor,
    header.envelopeVersion,
    header.schemaVersion,
    header.cipherSuite,
    header.nonce,
    header.previousStableCheckpointHash || null,
    signature,
    now,
    principal.capabilityId,
  );
  if (!inserted) {
    if (await cancelRotationBlockedLease(env, lease)) {
      throw new ApiError(409, "checkpoint_rotation_required");
    }
    throw new ApiError(409, "checkpoint_commit_conflict");
  }
  const candidate = await loadCandidate(env, principal.workspaceId, checkpointId, ciphertextSha256);
  if (!candidate) throw new ApiError(409, "checkpoint_candidate_not_found_after_commit");
  return {
    checkpointId,
    ciphertextSha256,
    throughWorkspaceSeq: candidate.throughSeq,
    keyEpoch: candidate.keyEpoch,
    uploaderDeviceId: candidate.uploaderDeviceId,
    createdAt: candidate.createdAt,
    previousStableCheckpointId: candidate.previousStableCheckpointId,
    previousStableThroughWorkspaceSeq: candidate.previousStableThroughSeq,
    previousStableCheckpointHash: candidate.previousStableSha256,
    status: "candidate",
  };
}

interface CandidateRow {
  workspaceId: string;
  checkpointId: string;
  ciphertextSha256: string;
  throughSeq: number;
  keyEpoch: number;
  uploaderDeviceId: string;
  r2Key: string;
  createdAt: number;
  previousStableSha256: string | null;
  previousStableCheckpointId: string | null;
  previousStableThroughSeq: number;
}

async function loadCandidate(
  env: Env,
  workspaceId: string,
  checkpointId: string,
  hash: string,
): Promise<CandidateRow | null> {
  return first<CandidateRow>(env.DB, `
    SELECT c.workspace_id AS workspaceId, c.checkpoint_id AS checkpointId,
           c.ciphertext_sha256 AS ciphertextSha256, c.through_seq AS throughSeq,
           c.key_epoch AS keyEpoch, c.uploader_device_id AS uploaderDeviceId,
           c.r2_key AS r2Key, c.created_at AS createdAt,
           c.previous_stable_sha256 AS previousStableSha256,
           previous.checkpoint_id AS previousStableCheckpointId,
           COALESCE(previous.through_seq, 0) AS previousStableThroughSeq
    FROM checkpoints c
    JOIN workspaces w ON w.workspace_id = c.workspace_id AND w.candidate_checkpoint_id = c.checkpoint_id
    LEFT JOIN checkpoints previous ON previous.workspace_id = c.workspace_id
      AND previous.ciphertext_sha256 = c.previous_stable_sha256 AND previous.status = 'stable'
    WHERE c.workspace_id = ?1 AND c.checkpoint_id = ?2
      AND c.ciphertext_sha256 = ?3 AND c.status = 'candidate'
    ORDER BY previous.promoted_at DESC LIMIT 1
  `, workspaceId, checkpointId, hash);
}

async function verifyR2ObjectHash(env: Env, candidate: CandidateRow): Promise<void> {
  const object = await env.CHECKPOINTS.get(candidate.r2Key);
  if (!object || object.customMetadata?.ciphertextSha256 !== candidate.ciphertextSha256) {
    throw new ApiError(409, "checkpoint_object_missing");
  }
  const bytes = new Uint8Array(await object.arrayBuffer());
  if (await sha256Base64Url(bytes) !== candidate.ciphertextSha256) {
    throw new ApiError(409, "checkpoint_object_corrupt");
  }
}

async function scheduleGc(env: Env, workspaceId: string, now: number): Promise<void> {
  const stable = await all<{ throughSeq: number }>(env.DB, `
    SELECT through_seq AS throughSeq FROM checkpoints
    WHERE workspace_id = ?1 AND status = 'stable'
    ORDER BY through_seq DESC, promoted_at DESC LIMIT 2
  `, workspaceId);
  if (stable.length < 2) return;
  const recoveryBaseSeq = stable[1].throughSeq;
  const safetySeconds = Math.max(3600, Number(env.CHECKPOINT_GC_SAFETY_SECONDS ?? "86400"));
  await run(env.DB, `
    INSERT INTO workspace_gc_state(workspace_id, target_seq, execute_after, status, updated_at)
    VALUES (?1, ?2, ?3, 'waiting', ?4)
    ON CONFLICT(workspace_id) DO UPDATE SET
      target_seq = MAX(workspace_gc_state.target_seq, excluded.target_seq),
      execute_after = excluded.execute_after,
      status = 'waiting', updated_at = excluded.updated_at
  `, workspaceId, recoveryBaseSeq, now + safetySeconds * 1000, now);
}

export async function advanceCheckpointGc(env: Env, workspaceId: string, now = Date.now()): Promise<void> {
  try {
    await run(env.DB, `
      INSERT INTO event_gc_runs(workspace_id, target_seq, executed_at)
      SELECT workspace_id, target_seq, ?2 FROM workspace_gc_state
      WHERE workspace_id = ?1 AND status = 'waiting' AND execute_after <= ?2
    `, workspaceId, now);
  } catch {
    return;
  }
  const workspace = await first<{ gcBeforeSeq: number }>(env.DB, `
    SELECT gc_before_seq AS gcBeforeSeq FROM workspaces WHERE workspace_id = ?1
  `, workspaceId);
  if (!workspace) return;
  const obsolete = await all<{ checkpointId: string; r2Key: string; byteSize: number }>(env.DB, `
    SELECT checkpoint_id AS checkpointId, r2_key AS r2Key, byte_size AS byteSize
    FROM checkpoints
    WHERE workspace_id = ?1 AND status = 'stable' AND through_seq <= ?2
      AND checkpoint_id NOT IN (
        SELECT retained.checkpoint_id FROM checkpoints retained
        WHERE retained.workspace_id = ?1 AND retained.status = 'stable'
        ORDER BY retained.through_seq DESC, retained.promoted_at DESC LIMIT 3
      )
    ORDER BY through_seq DESC, promoted_at DESC
  `, workspaceId, workspace.gcBeforeSeq);
  for (const checkpoint of obsolete) {
    await run(env.DB, `UPDATE checkpoints SET status = 'gc_pending'
      WHERE workspace_id = ?1 AND checkpoint_id = ?2 AND status = 'stable'`,
    workspaceId, checkpoint.checkpointId);
    try {
      await env.CHECKPOINTS.delete(checkpoint.r2Key);
      await run(env.DB, `
        UPDATE checkpoints SET status = 'deleted'
        WHERE workspace_id = ?1 AND checkpoint_id = ?2 AND status = 'gc_pending'
      `, workspaceId, checkpoint.checkpointId);
    } catch {
      // gc_pending is intentionally retryable; no retained pointer references this row.
    }
  }
}

export async function acknowledgeCheckpoint(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  checkpointIdInput: string,
  now?: number,
): Promise<unknown> {
  const checkpointId = requireUuid(checkpointIdInput, "checkpoint_id");
  const { value } = await readJson(request, 32 * 1024);
  const ciphertextSha256 = requireString(value, "ciphertextSha256", 43, 43);
  const validationVersion = requireInteger(value, "validationVersion", 1, 1);
  const replayFromSeq = requireInteger(value, "replayFromSeq", 0, Number.MAX_SAFE_INTEGER);
  const replayedThroughSeq = requireInteger(value, "replayedThroughSeq", 0, Number.MAX_SAFE_INTEGER);
  const replayedEventCount = requireInteger(value, "replayedEventCount", 0, Number.MAX_SAFE_INTEGER);
  const previousStableCheckpointId = value.previousStableCheckpointId == null
    ? null
    : requireUuid(value.previousStableCheckpointId, "previous_stable_checkpoint_id");
  const previousStableSha256 = value.previousStableSha256 == null
    ? null
    : requireString(value, "previousStableSha256", 43, 43);
  const valid = value.valid === undefined ? true : value.valid === true;
  if (value.valid !== undefined && typeof value.valid !== "boolean") throw new ApiError(400, "invalid_valid");
  const signature = requireString(value, "signature", 80, 256);
  const candidate = await loadCandidate(env, principal.workspaceId, checkpointId, ciphertextSha256);
  if (!candidate) throw new ApiError(404, "checkpoint_candidate_not_found");
  const authoritativeCount = await first<{ count: number }>(env.DB, `
    SELECT COUNT(*) AS count FROM events
    WHERE workspace_id = ?1 AND workspace_seq > ?2 AND workspace_seq <= ?3
  `, principal.workspaceId, candidate.previousStableThroughSeq, candidate.throughSeq);
  validateCheckpointReplayAck({
    checkpointId,
    checkpointHash: ciphertextSha256,
    throughSeq: candidate.throughSeq,
    expectedReplayFromSeq: candidate.previousStableThroughSeq,
    replayFromSeq,
    replayedThroughSeq,
    replayedEventCount,
    authoritativeEventCount: authoritativeCount?.count ?? 0,
    previousStableCheckpointId: candidate.previousStableCheckpointId,
    previousStableHash: candidate.previousStableSha256,
    assertedPreviousStableCheckpointId: previousStableCheckpointId,
    assertedPreviousStableHash: previousStableSha256,
  });
  const ackManifest = canonicalJson({
    workspaceId: principal.workspaceId,
    checkpointId,
    ciphertextSha256,
    keyEpoch: candidate.keyEpoch,
    validationVersion,
    replayFromSeq,
    replayedThroughSeq,
    replayedEventCount,
    previousStableCheckpointId,
    previousStableSha256,
    valid,
  });
  if (!(await verifyEd25519(
    principal.signingPublicKey,
    signature,
    domainSeparatedMessage("checkpoint-ack", utf8Bytes(ackManifest)),
  ))) throw new ApiError(401, "checkpoint_ack_signature_invalid");
  if (!valid) {
    // Production calls intentionally refresh the clock after parsing and signature verification.
    // Tests may pass a fixed timestamp to make the exact expiry boundary deterministic.
    const commitNow = now ?? Date.now();
    const rejection = await atomicBatch(env.DB, [
      env.DB.prepare(REJECT_CHECKPOINT_SQL).bind(
        principal.workspaceId, checkpointId, ciphertextSha256,
        principal.capabilityId, principal.deviceId, commitNow,
      ),
      env.DB.prepare(CLEAR_REJECTED_CHECKPOINT_POINTER_SQL).bind(
        principal.workspaceId, checkpointId, ciphertextSha256,
        principal.capabilityId, principal.deviceId, commitNow,
      ),
    ]);
    if (affectedRows(rejection[0]) < 1 || affectedRows(rejection[1]) < 1) {
      throw new ApiError(409, "checkpoint_ack_authorization_changed");
    }
    await cleanupRejectedCheckpoints(env, principal.workspaceId);
    return {
      checkpointId,
      ciphertextSha256,
      throughWorkspaceSeq: candidate.throughSeq,
      keyEpoch: candidate.keyEpoch,
      uploaderDeviceId: candidate.uploaderDeviceId,
      createdAt: candidate.createdAt,
      previousStableCheckpointId: candidate.previousStableCheckpointId,
      previousStableThroughWorkspaceSeq: candidate.previousStableThroughSeq,
      previousStableCheckpointHash: candidate.previousStableSha256,
      status: "rejected",
    };
  }
  // A signed, replay-bound invalid acknowledgement is specifically how a validator reports a
  // corrupt candidate object. Requiring that same object to hash correctly before rejection would
  // permanently strand the workspace candidate pointer. Successful promotion still verifies R2.
  await verifyR2ObjectHash(env, candidate);
  const activeDevices = await first<{ count: number }>(env.DB, `
    SELECT COUNT(*) AS count FROM devices d
    JOIN users u ON u.user_id = d.user_id
    JOIN memberships m ON m.user_id = d.user_id
    WHERE m.workspace_id = ?1 AND m.status = 'active'
      AND d.status = 'active' AND u.status = 'active'
  `, principal.workspaceId);
  // Do not reuse a request-entry timestamp after the potentially slow R2 hash verification.
  const commitNow = now ?? Date.now();
  if ((activeDevices?.count ?? 0) > 1 && principal.deviceId === candidate.uploaderDeviceId) {
    throw new ApiError(409, "independent_validator_required");
  }
  const graceMillis = Math.max(0, Number(env.CHECKPOINT_SELF_ACK_GRACE_SECONDS ?? "600")) * 1000;
  if ((activeDevices?.count ?? 0) === 1) {
    if (commitNow < candidate.createdAt + graceMillis) {
      throw new ApiError(409, "checkpoint_self_ack_grace", "Self-verification grace period has not elapsed", {
        retryAfterMillis: candidate.createdAt + graceMillis - commitNow,
      });
    }
  }
  const commit = await atomicBatch(env.DB, [
    env.DB.prepare(INSERT_CHECKPOINT_ACK_SQL).bind(
      principal.workspaceId, checkpointId, ciphertextSha256, principal.deviceId,
      validationVersion, replayFromSeq, replayedThroughSeq, replayedEventCount,
      previousStableCheckpointId, previousStableSha256, signature, commitNow,
      principal.capabilityId, graceMillis,
    ),
    env.DB.prepare(PROMOTE_CHECKPOINT_SQL).bind(
      principal.workspaceId, checkpointId, ciphertextSha256, commitNow,
      principal.capabilityId, principal.deviceId, graceMillis,
    ),
  ]);
  // D1 may include trigger side-effects in `meta.changes`; zero is the failed CAS signal.
  if (affectedRows(commit[1]) < 1) {
    throw new ApiError(409, "checkpoint_ack_authorization_changed");
  }
  await scheduleGc(env, principal.workspaceId, commitNow);
  await advanceCheckpointGc(env, principal.workspaceId, commitNow);
  return {
    checkpointId,
    ciphertextSha256,
    throughWorkspaceSeq: candidate.throughSeq,
    keyEpoch: candidate.keyEpoch,
    uploaderDeviceId: candidate.uploaderDeviceId,
    createdAt: candidate.createdAt,
    previousStableCheckpointId: candidate.previousStableCheckpointId,
    previousStableThroughWorkspaceSeq: candidate.previousStableThroughSeq,
    previousStableCheckpointHash: candidate.previousStableSha256,
    status: "stable",
  };
}

export async function downloadCheckpoint(
  env: Env,
  principal: CapabilityPrincipal,
  checkpointIdInput: string,
): Promise<Response> {
  const checkpointId = requireUuid(checkpointIdInput, "checkpoint_id");
  const checkpoint = await first<{
    r2Key: string;
    ciphertextSha256: string;
    byteSize: number;
    headerCbor: unknown;
    signature: string;
    status: string;
  }>(env.DB, `
    SELECT r2_key AS r2Key, ciphertext_sha256 AS ciphertextSha256,
           byte_size AS byteSize, authenticated_header_cbor AS headerCbor,
           device_signature AS signature, status
    FROM checkpoints WHERE workspace_id = ?1 AND checkpoint_id = ?2
      AND status IN ('candidate', 'stable')
  `, principal.workspaceId, checkpointId);
  if (!checkpoint) throw new ApiError(404, "checkpoint_not_found");
  const object = await env.CHECKPOINTS.get(checkpoint.r2Key);
  if (!object || object.size !== checkpoint.byteSize ||
      object.customMetadata?.ciphertextSha256 !== checkpoint.ciphertextSha256) {
    throw new ApiError(503, "checkpoint_object_unavailable");
  }
  return new Response(object.body, {
    status: 200,
    headers: {
      "Cache-Control": "private, no-store",
      "Content-Type": "application/octet-stream",
      "Content-Length": String(checkpoint.byteSize),
      "X-Content-Type-Options": "nosniff",
      "X-Shinsou-Ciphertext-Sha256": checkpoint.ciphertextSha256,
      "X-Shinsou-Header-Cbor": encodeBase64Url(
        checkpoint.headerCbor instanceof Uint8Array
          ? checkpoint.headerCbor
          : Uint8Array.from(checkpoint.headerCbor as number[]),
      ),
      "X-Shinsou-Device-Signature": checkpoint.signature,
    },
  });
}
