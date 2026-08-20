import {
  ApiError,
  integerParam,
  readJson,
  requireString,
} from "../http.ts";
import type { CapabilityPrincipal, Env } from "../runtime.ts";
import {
  decodeBase64Url,
  encodeBase64Url,
  sha256Base64Url,
  verifyEd25519,
} from "../crypto/base.ts";
import { decodeCanonicalMap, type CborValue } from "../crypto/cbor.ts";
import {
  classifyDeviceSequence,
  envelopeSignatureMessage,
  LIMITS,
  parseEventHeader,
} from "../protocol.ts";
import { all, blobToBytes, enforceRateWindow, first, returning } from "../storage/d1.ts";
import { APPEND_EVENT_SQL } from "../storage/sql.ts";
import { loadWorkspaceDeviceDirectory } from "./device-directory.ts";
import { requireWritableEnvelopeVersions } from "../versions.ts";

interface ParsedEvent {
  headerCbor: Uint8Array;
  ciphertext: Uint8Array;
  ciphertextSha256: string;
  signature: string;
  header: ReturnType<typeof parseEventHeader>;
}

async function parseAndVerifyEvent(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
): Promise<ParsedEvent> {
  const { value } = await readJson(request, 64 * 1024);
  const headerCbor = decodeBase64Url(requireString(value, "headerCbor", 1, 16 * 1024), "header_cbor");
  const ciphertext = decodeBase64Url(
    requireString(value, "ciphertext", 1, 48 * 1024),
    "ciphertext",
  );
  if (ciphertext.byteLength === 0 || ciphertext.byteLength > LIMITS.eventCiphertext) {
    throw new ApiError(413, "event_too_large");
  }
  const ciphertextSha256 = requireString(value, "ciphertextSha256", 43, 43);
  const hashBytes = decodeBase64Url(ciphertextSha256, "ciphertext_sha256");
  if (hashBytes.byteLength !== 32 || await sha256Base64Url(ciphertext) !== ciphertextSha256) {
    throw new ApiError(400, "ciphertext_hash_mismatch");
  }
  const signature = requireString(value, "signature", 80, 128);
  const header = parseEventHeader(headerCbor);
  requireWritableEnvelopeVersions(env, header.protocolVersion, header.schemaVersion);
  if (header.instanceId !== env.INSTANCE_ID.toLowerCase() ||
      header.workspaceId !== principal.workspaceId ||
      header.deviceId !== principal.deviceId) {
    throw new ApiError(403, "event_tenant_binding_mismatch");
  }
  const valid = await verifyEd25519(
    principal.signingPublicKey,
    signature,
    envelopeSignatureMessage("event", headerCbor, hashBytes),
  );
  if (!valid) throw new ApiError(401, "event_signature_invalid");
  return { headerCbor, ciphertext, ciphertextSha256, signature, header };
}

interface ReceiptRow {
  ciphertextSha256: string;
  workspaceSeq: number;
  eventId: string;
}

interface StreamRow {
  committedDeviceSeq: number;
}

async function idempotencyDecision(
  env: Env,
  principal: CapabilityPrincipal,
  event: ParsedEvent,
) {
  const [stream, receipt] = await Promise.all([
    first<StreamRow>(env.DB, `
      SELECT committed_device_seq AS committedDeviceSeq
      FROM device_stream_state WHERE workspace_id = ?1 AND device_id = ?2
    `, principal.workspaceId, principal.deviceId),
    first<ReceiptRow>(env.DB, `
      SELECT ciphertext_sha256 AS ciphertextSha256, workspace_seq AS workspaceSeq,
             event_id AS eventId
      FROM event_receipts
      WHERE workspace_id = ?1 AND device_id = ?2 AND device_seq = ?3
    `, principal.workspaceId, principal.deviceId, event.header.deviceSeq),
  ]);
  return {
    receipt,
    decision: classifyDeviceSequence(
      stream?.committedDeviceSeq ?? 0,
      event.header.deviceSeq,
      receipt,
      event.ciphertextSha256,
    ),
  };
}

export interface EventReceipt {
  duplicate: boolean;
  eventId: string;
  deviceSeq: number;
  workspaceSeq: number;
  headSeq: number;
  ciphertextSha256: string;
}

/** Permanent receipt lookup used when an append result was lost or an epoch rotated mid-flight. */
export async function lookupEventReceipt(
  env: Env,
  principal: CapabilityPrincipal,
  deviceSeq: number,
): Promise<EventReceipt> {
  if (!Number.isSafeInteger(deviceSeq) || deviceSeq <= 0) {
    throw new ApiError(400, "invalid_device_seq");
  }
  const receipt = await first<{
    eventId: string;
    workspaceSeq: number;
    ciphertextSha256: string;
    headSeq: number;
  }>(env.DB, `
    SELECT receipt.event_id AS eventId, receipt.workspace_seq AS workspaceSeq,
           receipt.ciphertext_sha256 AS ciphertextSha256, workspace.head_seq AS headSeq
    FROM event_receipts receipt
    JOIN workspaces workspace ON workspace.workspace_id = receipt.workspace_id
    WHERE receipt.workspace_id = ?1 AND receipt.device_id = ?2 AND receipt.device_seq = ?3
  `, principal.workspaceId, principal.deviceId, deviceSeq);
  if (!receipt) throw new ApiError(404, "event_receipt_not_found");
  return {
    duplicate: true,
    eventId: receipt.eventId,
    deviceSeq,
    workspaceSeq: receipt.workspaceSeq,
    headSeq: receipt.headSeq,
    ciphertextSha256: receipt.ciphertextSha256,
  };
}

export async function appendEvent(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  now = Date.now(),
): Promise<EventReceipt> {
  if (principal.role === "reader") throw new ApiError(403, "workspace_write_forbidden");
  const event = await parseAndVerifyEvent(request, env, principal);
  await Promise.all([
    enforceRateWindow(
      env.DB,
      "event_rate_windows",
      "scope_key",
      `device:${principal.deviceId}`,
      now,
      LIMITS.eventDevicePerMinute,
    ),
    enforceRateWindow(
      env.DB,
      "event_rate_windows",
      "scope_key",
      `workspace:${principal.workspaceId}`,
      now,
      LIMITS.eventWorkspacePerMinute,
    ),
  ]);

  const { decision, receipt } = await idempotencyDecision(env, principal, event);
  if (decision.kind === "duplicate") {
    const workspace = await first<{ headSeq: number }>(env.DB, `
      SELECT head_seq AS headSeq FROM workspaces WHERE workspace_id = ?1
    `, principal.workspaceId);
    return {
      duplicate: true,
      eventId: receipt?.eventId ?? event.header.eventId,
      deviceSeq: event.header.deviceSeq,
      workspaceSeq: decision.workspaceSeq,
      headSeq: workspace?.headSeq ?? decision.workspaceSeq,
      ciphertextSha256: event.ciphertextSha256,
    };
  }
  if (decision.kind === "replay_or_corruption") {
    throw new ApiError(409, "replay_or_corruption");
  }
  if (decision.kind === "sequence_gap") {
    throw new ApiError(409, "device_sequence_gap", "A prior sealed event is missing", {
      expectedDeviceSeq: decision.expectedDeviceSeq,
    });
  }

  let inserted: { workspaceSeq?: number; workspace_seq?: number } | null;
  try {
    inserted = await returning<{ workspaceSeq?: number; workspace_seq?: number }>(
      env.DB,
      APPEND_EVENT_SQL,
      principal.workspaceId,
      event.header.eventId,
      principal.deviceId,
      event.header.deviceSeq,
      event.header.keyEpoch,
      event.headerCbor,
      event.ciphertext,
      event.ciphertextSha256,
      event.signature,
      event.ciphertext.byteLength,
      now,
      principal.capabilityId,
    );
  } catch (error) {
    const raced = await idempotencyDecision(env, principal, event);
    if (raced.decision.kind === "duplicate") {
      return {
        duplicate: true,
        eventId: raced.receipt?.eventId ?? event.header.eventId,
        deviceSeq: event.header.deviceSeq,
        workspaceSeq: raced.decision.workspaceSeq,
        headSeq: raced.decision.workspaceSeq,
        ciphertextSha256: event.ciphertextSha256,
      };
    }
    const existingEvent = await first<{ present: number }>(env.DB, `
      SELECT 1 AS present FROM event_receipts
      WHERE workspace_id = ?1 AND event_id = ?2
    `, principal.workspaceId, event.header.eventId);
    if (existingEvent) throw new ApiError(409, "event_id_conflict");
    throw error;
  }
  if (!inserted) {
    const state = await first<{
      activeKeyEpoch: number;
      committedBytes: number;
      reservedBytes: number;
      maxBytes: number;
      rotationRequired: number;
      deviceStatus: string;
      membershipStatus: string;
      capabilityValid: number;
      committedDeviceSeq: number | null;
    }>(env.DB, `
      SELECT w.active_key_epoch AS activeKeyEpoch, w.committed_bytes AS committedBytes,
             w.reserved_bytes AS reservedBytes, q.max_workspace_bytes AS maxBytes,
             w.rotation_required AS rotationRequired,
             d.status AS deviceStatus, m.status AS membershipStatus,
             CASE WHEN c.revoked_at IS NULL AND c.expires_at > ?4
                       AND c.device_auth_epoch = d.auth_epoch
                       AND c.membership_auth_epoch = m.auth_epoch THEN 1 ELSE 0 END AS capabilityValid,
             s.committed_device_seq AS committedDeviceSeq
      FROM workspaces w JOIN workspace_capabilities c ON c.capability_id = ?3
      JOIN devices d ON d.device_id = c.device_id
      JOIN users u ON u.user_id = d.user_id
      JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
      JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
      LEFT JOIN device_stream_state s ON s.workspace_id = w.workspace_id AND s.device_id = d.device_id
      WHERE w.workspace_id = ?1 AND d.device_id = ?2
    `, principal.workspaceId, principal.deviceId, principal.capabilityId, now);
    if (!state || state.deviceStatus !== "active" || state.membershipStatus !== "active" || !state.capabilityValid) {
      throw new ApiError(401, "capability_invalidated");
    }
    const raced = await idempotencyDecision(env, principal, event);
    if (raced.decision.kind === "duplicate") {
      return {
        duplicate: true,
        eventId: raced.receipt?.eventId ?? event.header.eventId,
        deviceSeq: event.header.deviceSeq,
        workspaceSeq: raced.decision.workspaceSeq,
        headSeq: raced.decision.workspaceSeq,
        ciphertextSha256: event.ciphertextSha256,
      };
    }
    if (state.rotationRequired === 1) {
      throw new ApiError(409, "key_rotation_required", "The event was not committed", {
        expectedKeyEpoch: state.activeKeyEpoch,
      });
    }
    if (event.header.keyEpoch !== state.activeKeyEpoch) {
      throw new ApiError(409, "stale_key_epoch", "The event was not committed", {
        expectedKeyEpoch: state.activeKeyEpoch,
      });
    }
    if (state.committedBytes + state.reservedBytes + event.ciphertext.byteLength > state.maxBytes) {
      throw new ApiError(507, "workspace_quota_exceeded");
    }
    throw new ApiError(409, "append_conflict_retry");
  }
  const workspaceSeq = Number(inserted.workspaceSeq ?? inserted.workspace_seq);
  return {
    duplicate: false,
    eventId: event.header.eventId,
    deviceSeq: event.header.deviceSeq,
    workspaceSeq,
    headSeq: workspaceSeq,
    ciphertextSha256: event.ciphertextSha256,
  };
}

interface EventRow {
  workspaceSeq: number;
  eventId: string;
  deviceId: string;
  deviceSeq: number;
  keyEpoch: number;
  headerCbor: unknown;
  ciphertext: unknown;
  ciphertextSha256: string;
  signature: string;
  createdAt: number;
}

export async function catchUpEvents(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
): Promise<unknown> {
  const url = new URL(request.url);
  const after = integerParam(url.searchParams.get("after"), 0, 0, Number.MAX_SAFE_INTEGER, "after");
  const limit = integerParam(url.searchParams.get("limit"), 100, 1, LIMITS.catchUpPage, "limit");
  const workspace = await first<{
    headSeq: number;
    gcBeforeSeq: number;
    stableCheckpointSeq: number | null;
  }>(env.DB, `
    SELECT w.head_seq AS headSeq, w.gc_before_seq AS gcBeforeSeq,
           c.through_seq AS stableCheckpointSeq
    FROM workspaces w LEFT JOIN checkpoints c
      ON c.workspace_id = w.workspace_id AND c.checkpoint_id = w.stable_checkpoint_id
    JOIN memberships m ON m.workspace_id = w.workspace_id
    JOIN devices d ON d.user_id = m.user_id
    WHERE w.workspace_id = ?1 AND d.device_id = ?2
      AND w.status = 'active' AND m.status = 'active' AND d.status = 'active'
  `, principal.workspaceId, principal.deviceId);
  if (!workspace) throw new ApiError(403, "workspace_forbidden");
  if (after < workspace.gcBeforeSeq) {
    const retained = await loadRetainedStableCheckpoints(env, principal.workspaceId);
    throw new ApiError(410, "checkpoint_required", "The requested event cursor was garbage-collected", {
      gcBeforeSeq: workspace.gcBeforeSeq,
      headSeq: workspace.headSeq,
      retainedStableCheckpoints: retained,
    });
  }
  const requestedUntil = url.searchParams.get("until");
  const until = requestedUntil === null
    ? workspace.headSeq
    : Math.min(integerParam(requestedUntil, workspace.headSeq, after, Number.MAX_SAFE_INTEGER, "until"), workspace.headSeq);
  const rows = await all<EventRow>(env.DB, `
    SELECT workspace_seq AS workspaceSeq, event_id AS eventId, device_id AS deviceId,
           device_seq AS deviceSeq, key_epoch AS keyEpoch,
           authenticated_header_cbor AS headerCbor, ciphertext,
           ciphertext_sha256 AS ciphertextSha256, signature, created_at AS createdAt
    FROM events
    WHERE workspace_id = ?1 AND workspace_seq > ?2 AND workspace_seq <= ?3
    ORDER BY workspace_seq LIMIT ?4
  `, principal.workspaceId, after, until, limit);
  const events = rows.map((row) => ({
    workspaceSeq: row.workspaceSeq,
    eventId: row.eventId,
    deviceId: row.deviceId,
    deviceSeq: row.deviceSeq,
    keyEpoch: row.keyEpoch,
    headerCbor: encodeBase64Url(blobToBytes(row.headerCbor)),
    ciphertext: encodeBase64Url(blobToBytes(row.ciphertext)),
    ciphertextSha256: row.ciphertextSha256,
    signature: row.signature,
    createdAt: row.createdAt,
  }));
  const senderDeviceIds = new Set(rows.map((row) => row.deviceId));
  const senderDirectory = await loadWorkspaceDeviceDirectory(
    env,
    principal.workspaceId,
    senderDeviceIds,
  );
  const nextCursor = events.length ? events[events.length - 1].workspaceSeq : after;
  return {
    fromExclusive: after,
    untilInclusive: until,
    nextCursor,
    hasMore: nextCursor < until,
    headSeq: workspace.headSeq,
    stableCheckpointSeq: workspace.stableCheckpointSeq ?? 0,
    deviceDirectoryVersion: senderDirectory.version,
    deviceDirectoryHash: senderDirectory.hash,
    deviceDirectoryAllDeviceCount: senderDirectory.allDeviceCount,
    senderDevices: senderDirectory.devices,
    events,
  };
}

export async function loadRetainedStableCheckpoints(env: Env, workspaceId: string): Promise<unknown[]> {
  return all(env.DB, `
    SELECT checkpoint_id AS checkpointId, through_seq AS throughWorkspaceSeq,
           key_epoch AS keyEpoch, ciphertext_sha256 AS ciphertextSha256,
           byte_size AS byteSize, previous_stable_sha256 AS previousStableCheckpointHash,
           schema_version AS schemaVersion, envelope_version AS envelopeVersion,
           cipher_suite AS cipherSuite, nonce, created_at AS createdAt,
           '/v1/workspaces/' || workspace_id || '/checkpoints/' || checkpoint_id AS downloadPath
    FROM checkpoints WHERE workspace_id = ?1 AND status = 'stable'
    ORDER BY through_seq DESC, promoted_at DESC LIMIT 3
  `, workspaceId);
}

export async function loadCheckpointCandidate(env: Env, workspaceId: string): Promise<unknown | null> {
  return first(env.DB, `
    SELECT candidate.checkpoint_id AS checkpointId,
           candidate.through_seq AS throughWorkspaceSeq,
           candidate.key_epoch AS keyEpoch,
           candidate.ciphertext_sha256 AS ciphertextSha256,
           candidate.uploader_device_id AS uploaderDeviceId,
           candidate.created_at AS createdAt,
           previous.checkpoint_id AS previousStableCheckpointId,
           COALESCE(previous.through_seq, 0) AS previousStableThroughWorkspaceSeq,
           candidate.previous_stable_sha256 AS previousStableCheckpointHash,
           'candidate' AS status
    FROM workspaces workspace
    JOIN checkpoints candidate
      ON candidate.workspace_id = workspace.workspace_id
     AND candidate.checkpoint_id = workspace.candidate_checkpoint_id
     AND candidate.status = 'candidate'
    LEFT JOIN checkpoints previous
      ON previous.workspace_id = candidate.workspace_id
     AND previous.ciphertext_sha256 = candidate.previous_stable_sha256
     AND previous.status = 'stable'
    WHERE workspace.workspace_id = ?1
    ORDER BY previous.promoted_at DESC LIMIT 1
  `, workspaceId);
}

interface DeviceKeyEnvelopeRow {
  keyEpoch: number;
  rotationId: string;
  keyCommitment: string;
  wrappedKey: string;
  wrappedByDeviceId: string;
  signature: string;
  rotationStatus: string | null;
  rotationManifestCbor: unknown | null;
  rotationManifestSignature: string | null;
  proposerDeviceId: string | null;
  proposerSigningPublicKey: string | null;
}

function manifestStringMap(value: CborValue, field: string): Record<string, string> {
  if (!value || value instanceof Uint8Array || Array.isArray(value) || typeof value !== "object") {
    throw new ApiError(503, `rotation_evidence_${field}_invalid`);
  }
  const result: Record<string, string> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (typeof entry !== "string") throw new ApiError(503, `rotation_evidence_${field}_invalid`);
    result[key] = entry;
  }
  return result;
}

function manifestIntegerMap(value: CborValue, field: string): Record<string, number> {
  if (!value || value instanceof Uint8Array || Array.isArray(value) || typeof value !== "object") {
    throw new ApiError(503, `rotation_evidence_${field}_invalid`);
  }
  const result: Record<string, number> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (!Number.isSafeInteger(entry) || (entry as number) <= 0) {
      throw new ApiError(503, `rotation_evidence_${field}_invalid`);
    }
    result[key] = entry as number;
  }
  return result;
}

/** Returns exact signed rotation evidence; a reconstructed manifest is never accepted. */
export async function loadDeviceKeyEnvelopes(
  env: Env,
  workspaceId: string,
  deviceId: string,
): Promise<unknown[]> {
  const workspace = await first<{ activeKeyEpoch: number }>(env.DB, `
    SELECT active_key_epoch AS activeKeyEpoch FROM workspaces
    WHERE workspace_id = ?1 AND status = 'active'
  `, workspaceId);
  if (!workspace) throw new ApiError(404, "workspace_not_found");
  const rows = await all<DeviceKeyEnvelopeRow>(env.DB, `
    SELECT keys.key_epoch AS keyEpoch, keys.rotation_id AS rotationId,
           keys.key_commitment AS keyCommitment, keys.wrapped_key AS wrappedKey,
           keys.wrapped_by_device_id AS wrappedByDeviceId, keys.signature,
           rotation.status AS rotationStatus, rotation.manifest_cbor AS rotationManifestCbor,
           rotation.manifest_signature AS rotationManifestSignature,
           rotation.proposer_device_id AS proposerDeviceId,
           proposer.signing_public_key AS proposerSigningPublicKey
    FROM workspace_device_keys keys
    LEFT JOIN key_rotations rotation
      ON rotation.workspace_id = keys.workspace_id AND rotation.rotation_id = keys.rotation_id
    LEFT JOIN devices proposer ON proposer.device_id = rotation.proposer_device_id
    WHERE keys.workspace_id = ?1 AND keys.device_id = ?2
    ORDER BY keys.key_epoch, keys.created_at DESC
  `, workspaceId, deviceId);
  const result: unknown[] = [];
  for (const row of rows) {
    const envelope = {
      keyEpoch: row.keyEpoch,
      rotationId: row.rotationId,
      keyCommitment: row.keyCommitment,
      wrappedKey: row.wrappedKey,
      wrappedByDeviceId: row.wrappedByDeviceId,
      signature: row.signature,
    };
    if (row.rotationStatus === null) {
      result.push(envelope);
      continue;
    }
    if (row.rotationStatus !== "committed" || row.rotationManifestCbor === null ||
        !row.rotationManifestSignature || !row.proposerDeviceId || !row.proposerSigningPublicKey ||
        row.rotationManifestSignature !== row.signature || row.proposerDeviceId !== row.wrappedByDeviceId) {
      throw new ApiError(503, "rotation_evidence_incomplete");
    }
    const manifestBytes = blobToBytes(row.rotationManifestCbor);
    const manifest = decodeCanonicalMap(manifestBytes);
    const expectedFields = [
      "rotationId", "workspaceId", "fromEpoch", "toEpoch", "proposerDeviceId",
      "proposerDeviceAuthEpoch", "membershipAuthEpoch", "keyCommitment",
      "recipientEnvelopeHashes", "recipientAuthEpochs", "recoveryEnvelopeHash",
      "recoveryAuthEpoch", "expiresAt",
    ].sort();
    const actualFields = Object.keys(manifest).sort();
    if (expectedFields.length !== actualFields.length ||
        expectedFields.some((field, index) => field !== actualFields[index]) ||
        manifest.rotationId !== row.rotationId || manifest.workspaceId !== workspaceId ||
        manifest.toEpoch !== row.keyEpoch || manifest.proposerDeviceId !== row.proposerDeviceId ||
        manifest.keyCommitment !== row.keyCommitment || row.keyEpoch > workspace.activeKeyEpoch) {
      throw new ApiError(503, "rotation_evidence_binding_invalid");
    }
    const recoveryEnvelopeHash = manifest.recoveryEnvelopeHash;
    if (typeof recoveryEnvelopeHash !== "string") {
      throw new ApiError(503, "rotation_evidence_recovery_hash_invalid");
    }
    const recipientEnvelopeHashes = manifestStringMap(
      manifest.recipientEnvelopeHashes,
      "recipient_envelope_hashes",
    );
    const recipientAuthEpochs = manifestIntegerMap(manifest.recipientAuthEpochs, "recipient_auth_epochs");
    const recipients = await all<{
      deviceId: string;
      authEpoch: number;
      wrappedKey: string | null;
      keyCommitment: string | null;
    }>(env.DB, `
      SELECT recipient.device_id AS deviceId, recipient.device_auth_epoch AS authEpoch,
             keys.wrapped_key AS wrappedKey, keys.key_commitment AS keyCommitment
      FROM key_rotation_recipients recipient
      LEFT JOIN workspace_device_keys keys
        ON keys.rotation_id = recipient.rotation_id AND keys.device_id = recipient.device_id
      WHERE recipient.rotation_id = ?1 ORDER BY recipient.device_id
    `, row.rotationId);
    if (!recipients.length || Object.keys(recipientEnvelopeHashes).length !== recipients.length ||
        Object.keys(recipientAuthEpochs).length !== recipients.length) {
      throw new ApiError(503, "rotation_evidence_recipient_set_incomplete");
    }
    for (const recipient of recipients) {
      if (!recipient.wrappedKey || recipient.keyCommitment !== row.keyCommitment ||
          recipientAuthEpochs[recipient.deviceId] !== recipient.authEpoch ||
          recipientEnvelopeHashes[recipient.deviceId] !==
            await sha256Base64Url(new TextEncoder().encode(recipient.wrappedKey))) {
        throw new ApiError(503, "rotation_evidence_recipient_binding_invalid");
      }
    }
    const recovery = await first<{ wrappedKey: string; keyCommitment: string }>(env.DB, `
      SELECT wrapped_key AS wrappedKey, key_commitment AS keyCommitment
      FROM workspace_recovery_keys WHERE rotation_id = ?1
    `, row.rotationId);
    if (!recovery || recovery.keyCommitment !== row.keyCommitment ||
        recoveryEnvelopeHash !== await sha256Base64Url(new TextEncoder().encode(recovery.wrappedKey))) {
      throw new ApiError(503, "rotation_evidence_recovery_binding_invalid");
    }
    result.push({
      ...envelope,
      rotationEvidence: {
        manifestCbor: encodeBase64Url(manifestBytes),
        manifestSignature: row.rotationManifestSignature,
        proposerDeviceId: row.proposerDeviceId,
        proposerSigningPublicKey: row.proposerSigningPublicKey,
        recipientEnvelopeHashes,
        recipientAuthEpochs,
        recoveryEnvelopeHash,
        status: row.rotationStatus,
      },
    });
  }
  return result;
}

export async function workspaceBootstrap(
  env: Env,
  principal: CapabilityPrincipal,
): Promise<unknown> {
  const workspace = await first<{
    headSeq: number;
    gcBeforeSeq: number;
    activeKeyEpoch: number;
    stableCheckpointId: string | null;
    rotationRequired: number;
  }>(env.DB, `
    SELECT head_seq AS headSeq, gc_before_seq AS gcBeforeSeq,
           active_key_epoch AS activeKeyEpoch, stable_checkpoint_id AS stableCheckpointId,
           rotation_required AS rotationRequired
    FROM workspaces WHERE workspace_id = ?1 AND status = 'active'
  `, principal.workspaceId);
  if (!workspace) throw new ApiError(404, "workspace_not_found");
  const retainedStableCheckpoints = await loadRetainedStableCheckpoints(env, principal.workspaceId);
  const candidateCheckpoint = await loadCheckpointCandidate(env, principal.workspaceId);
  const deviceDirectory = await loadWorkspaceDeviceDirectory(env, principal.workspaceId);
  const keyEpochRows = await all<{ keyEpoch: number }>(env.DB, `
    SELECT DISTINCT key_epoch AS keyEpoch FROM (
      SELECT active_key_epoch AS key_epoch FROM workspaces WHERE workspace_id = ?1
      UNION SELECT key_epoch FROM checkpoints WHERE workspace_id = ?1 AND status = 'stable'
      UNION SELECT key_epoch FROM events WHERE workspace_id = ?1
    ) ORDER BY key_epoch
  `, principal.workspaceId);
  const deviceKeyEnvelopes = await loadDeviceKeyEnvelopes(
    env,
    principal.workspaceId,
    principal.deviceId,
  );
  return {
    workspaceId: principal.workspaceId,
    ...workspace,
    rotationRequired: workspace.rotationRequired === 1,
    retainedStableCheckpoints,
    candidateCheckpoint,
    requiredKeyEpochs: keyEpochRows.map((row) => row.keyEpoch),
    deviceKeyEnvelopes,
    deviceDirectory,
  };
}
