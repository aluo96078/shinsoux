import {
  ApiError,
  readJson,
  requireInteger,
  requireObject,
  requireString,
  utf8Bytes,
} from "../http.ts";
import type { CapabilityPrincipal, Env } from "../runtime.ts";
import {
  canonicalJson,
  decodeBase64Url,
  domainSeparatedMessage,
  randomUuid,
  sha256Base64Url,
  verifyEd25519,
} from "../crypto/base.ts";
import { decodeCanonicalMap, type CborValue } from "../crypto/cbor.ts";
import { requireUuid, validateRecipientSet } from "../protocol.ts";
import { all, atomicBatch, first, returning, run } from "../storage/d1.ts";
import { CREATE_ROTATION_LEASE_SQL } from "../storage/sql.ts";

const ROTATION_LEASE_MS = 5 * 60_000;

interface RotationRow {
  rotationId: string;
  workspaceId: string;
  fromEpoch: number;
  toEpoch: number;
  proposerDeviceId: string;
  proposerDeviceAuthEpoch: number;
  membershipAuthEpoch: number;
  status: string;
  leaseExpiresAt: number;
  keyCommitment: string | null;
}

interface RecipientRow {
  deviceId: string;
  authEpoch: number;
  wrappingPublicKey: string;
}

function mapString(map: Record<string, CborValue>, key: string): string {
  const value = map[key];
  if (typeof value !== "string") throw new ApiError(400, `invalid_rotation_manifest_${key}`);
  return value;
}

function mapInteger(map: Record<string, CborValue>, key: string): number {
  const value = map[key];
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new ApiError(400, `invalid_rotation_manifest_${key}`);
  }
  return value as number;
}

export async function createRotationLease(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  now = Date.now(),
): Promise<unknown> {
  if (principal.role === "reader") throw new ApiError(403, "workspace_write_forbidden");
  const { value } = await readJson(request, 16 * 1024);
  const rotationId = requireUuid(value.rotationId, "rotation_id");
  const expectedFromEpoch = requireInteger(value, "fromEpoch", 1, 1_000_000);
  const leaseSignature = requireString(value, "signature", 80, 256);
  const leaseManifest = canonicalJson({
    workspaceId: principal.workspaceId,
    rotationId,
    fromEpoch: expectedFromEpoch,
    proposerDeviceId: principal.deviceId,
  });
  if (!(await verifyEd25519(
    principal.signingPublicKey,
    leaseSignature,
    domainSeparatedMessage("rotation-lease", utf8Bytes(leaseManifest)),
  ))) throw new ApiError(401, "rotation_lease_signature_invalid");

  await run(env.DB, `UPDATE key_rotations SET status = 'expired'
    WHERE workspace_id = ?1 AND status = 'leased' AND lease_expires_at <= ?2`,
  principal.workspaceId, now);
  await run(env.DB, `UPDATE workspaces SET pending_rotation_id = NULL
    WHERE workspace_id = ?1 AND pending_rotation_id IN (
      SELECT rotation_id FROM key_rotations WHERE workspace_id = ?1 AND status = 'expired'
    )`, principal.workspaceId);
  const expiresAt = now + ROTATION_LEASE_MS;
  const row = await returning<{
    rotationId?: string;
    rotation_id?: string;
    fromEpoch?: number;
    from_epoch?: number;
    toEpoch?: number;
    to_epoch?: number;
    leaseExpiresAt?: number;
    lease_expires_at?: number;
  }>(
    env.DB,
    CREATE_ROTATION_LEASE_SQL,
    rotationId,
    principal.workspaceId,
    expiresAt,
    now,
    principal.deviceId,
  );
  if (!row) throw new ApiError(409, "rotation_lease_unavailable");
  const fromEpoch = Number(row.fromEpoch ?? row.from_epoch);
  if (fromEpoch !== expectedFromEpoch) {
    await run(env.DB, "UPDATE key_rotations SET status = 'aborted' WHERE rotation_id = ?1", rotationId);
    await run(env.DB, `UPDATE workspaces SET pending_rotation_id = NULL
      WHERE workspace_id = ?1 AND pending_rotation_id = ?2`, principal.workspaceId, rotationId);
    throw new ApiError(409, "stale_key_epoch", "Rotation lease used a stale epoch", { expectedKeyEpoch: fromEpoch });
  }
  const recipients = await all<RecipientRow>(env.DB, `
    SELECT device_id AS deviceId, device_auth_epoch AS authEpoch,
           wrapping_public_key AS wrappingPublicKey
    FROM key_rotation_recipients WHERE rotation_id = ?1 ORDER BY device_id
  `, rotationId);
  const recovery = await first<{ wrappingPublicKey: string; authEpoch: number }>(env.DB, `
    SELECT r.wrapping_public_key AS wrappingPublicKey, r.auth_epoch AS authEpoch
    FROM recovery_credentials r JOIN workspaces w ON w.owner_user_id = r.user_id
    WHERE w.workspace_id = ?1
  `, principal.workspaceId);
  if (!recovery) throw new ApiError(409, "recovery_credential_missing");
  return {
    rotationId,
    workspaceId: principal.workspaceId,
    fromEpoch,
    toEpoch: Number(row.toEpoch ?? row.to_epoch),
    proposerDeviceId: principal.deviceId,
    proposerDeviceAuthEpoch: principal.deviceAuthEpoch,
    membershipAuthEpoch: principal.membershipAuthEpoch,
    recipients,
    recovery,
    expiresAt: Number(row.leaseExpiresAt ?? row.lease_expires_at),
  };
}

function parseRecipientAuthEpochs(value: CborValue): Record<string, number> {
  if (!value || value instanceof Uint8Array || Array.isArray(value) || typeof value !== "object") {
    throw new ApiError(400, "invalid_rotation_manifest_recipientAuthEpochs");
  }
  const epochs: Record<string, number> = {};
  for (const [deviceIdInput, epoch] of Object.entries(value)) {
    const deviceId = requireUuid(deviceIdInput, "rotation_recipient_device_id");
    if (!Number.isSafeInteger(epoch) || (epoch as number) <= 0) {
      throw new ApiError(400, "invalid_rotation_recipient_auth_epoch");
    }
    epochs[deviceId] = epoch as number;
  }
  return epochs;
}

function parseEnvelopeHashes(value: CborValue): Record<string, string> {
  if (!value || value instanceof Uint8Array || Array.isArray(value) || typeof value !== "object") {
    throw new ApiError(400, "invalid_rotation_manifest_recipientEnvelopeHashes");
  }
  const hashes: Record<string, string> = {};
  for (const [deviceIdInput, hash] of Object.entries(value)) {
    const deviceId = requireUuid(deviceIdInput, "rotation_recipient_device_id");
    if (typeof hash !== "string" || decodeBase64Url(hash, "envelope_hash").byteLength !== 32) {
      throw new ApiError(400, "invalid_rotation_envelope_hash");
    }
    hashes[deviceId] = hash;
  }
  return hashes;
}

interface RotationEnvelope {
  deviceId: string;
  wrappedKey: string;
}

function parseRotationEnvelopes(value: unknown): RotationEnvelope[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 100) {
    throw new ApiError(400, "invalid_device_envelopes");
  }
  return value.map((entry) => {
    const object = requireObject(entry, "device_envelope");
    return {
      deviceId: requireUuid(object.deviceId, "recipient_device_id"),
      wrappedKey: requireString(object, "wrappedKey", 32, 16 * 1024),
    };
  });
}

async function loadRotation(env: Env, workspaceId: string, rotationId: string): Promise<RotationRow | null> {
  return first<RotationRow>(env.DB, `
    SELECT rotation_id AS rotationId, workspace_id AS workspaceId,
           from_epoch AS fromEpoch, to_epoch AS toEpoch,
           proposer_device_id AS proposerDeviceId,
           proposer_device_auth_epoch AS proposerDeviceAuthEpoch,
           membership_auth_epoch AS membershipAuthEpoch, status,
           lease_expires_at AS leaseExpiresAt, key_commitment AS keyCommitment
    FROM key_rotations WHERE workspace_id = ?1 AND rotation_id = ?2
  `, workspaceId, rotationId);
}

export async function commitRotation(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  rotationIdInput: string,
  now = Date.now(),
): Promise<unknown> {
  if (principal.role === "reader") throw new ApiError(403, "workspace_write_forbidden");
  const rotationId = requireUuid(rotationIdInput, "rotation_id");
  const { value } = await readJson(request, 512 * 1024);
  const manifestEncoded = requireString(value, "manifestCbor", 1, 256 * 1024);
  const manifestCbor = decodeBase64Url(manifestEncoded, "manifest_cbor");
  const manifest = decodeCanonicalMap(manifestCbor);
  const expectedKeys = [
    "rotationId", "workspaceId", "fromEpoch", "toEpoch", "proposerDeviceId",
    "proposerDeviceAuthEpoch", "membershipAuthEpoch", "keyCommitment",
    "recipientEnvelopeHashes", "recipientAuthEpochs", "recoveryEnvelopeHash",
    "recoveryAuthEpoch", "expiresAt",
  ].sort();
  const actualKeys = Object.keys(manifest).sort();
  if (expectedKeys.length !== actualKeys.length || expectedKeys.some((key, index) => key !== actualKeys[index])) {
    throw new ApiError(400, "unexpected_rotation_manifest_fields");
  }
  const signature = requireString(value, "manifestSignature", 80, 256);
  const rotation = await loadRotation(env, principal.workspaceId, rotationId);
  if (!rotation || rotation.status !== "leased" || rotation.leaseExpiresAt <= now ||
      rotation.proposerDeviceId !== principal.deviceId) {
    throw new ApiError(409, "rotation_lease_invalid");
  }
  const keyCommitment = mapString(manifest, "keyCommitment");
  if (decodeBase64Url(keyCommitment, "key_commitment").byteLength !== 32) {
    throw new ApiError(400, "invalid_key_commitment");
  }
  if (requireUuid(mapString(manifest, "rotationId"), "manifest_rotation_id") !== rotationId ||
      requireUuid(mapString(manifest, "workspaceId"), "manifest_workspace_id") !== principal.workspaceId ||
      requireUuid(mapString(manifest, "proposerDeviceId"), "manifest_proposer_device_id") !== principal.deviceId ||
      mapInteger(manifest, "fromEpoch") !== rotation.fromEpoch ||
      mapInteger(manifest, "toEpoch") !== rotation.toEpoch ||
      mapInteger(manifest, "proposerDeviceAuthEpoch") !== rotation.proposerDeviceAuthEpoch ||
      mapInteger(manifest, "membershipAuthEpoch") !== rotation.membershipAuthEpoch ||
      mapInteger(manifest, "expiresAt") !== rotation.leaseExpiresAt) {
    throw new ApiError(409, "rotation_manifest_binding_mismatch");
  }
  if (!(await verifyEd25519(
    principal.signingPublicKey,
    signature,
    domainSeparatedMessage("rotation-manifest", manifestCbor),
  ))) throw new ApiError(401, "rotation_manifest_signature_invalid");
  const expectedRecipients = await all<RecipientRow>(env.DB, `
    SELECT device_id AS deviceId, device_auth_epoch AS authEpoch,
           wrapping_public_key AS wrappingPublicKey
    FROM key_rotation_recipients WHERE rotation_id = ?1 ORDER BY device_id
  `, rotationId);
  const currentRecipients = await all<{ deviceId: string; authEpoch: number }>(env.DB, `
    SELECT d.device_id AS deviceId, d.auth_epoch AS authEpoch
    FROM memberships m JOIN devices d ON d.user_id = m.user_id
    WHERE m.workspace_id = ?1 AND m.status = 'active' AND d.status = 'active'
    ORDER BY d.device_id
  `, principal.workspaceId);
  validateRecipientSet(expectedRecipients, currentRecipients);
  const envelopes = parseRotationEnvelopes(value.deviceEnvelopes);
  const envelopeHashes = parseEnvelopeHashes(manifest.recipientEnvelopeHashes);
  const recipientAuthEpochs = parseRecipientAuthEpochs(manifest.recipientAuthEpochs);
  const submittedRecipients = envelopes.map((entry) => ({
    deviceId: entry.deviceId,
    authEpoch: expectedRecipients.find((candidate) => candidate.deviceId === entry.deviceId)?.authEpoch ?? -1,
  }));
  validateRecipientSet(expectedRecipients, submittedRecipients);
  for (const envelope of envelopes) {
    const hash = await sha256Base64Url(utf8Bytes(envelope.wrappedKey));
    if (envelopeHashes[envelope.deviceId] !== hash) throw new ApiError(400, "rotation_envelope_hash_mismatch");
  }
  if (Object.keys(envelopeHashes).length !== envelopes.length) {
    throw new ApiError(400, "rotation_envelope_set_mismatch");
  }
  if (Object.keys(recipientAuthEpochs).length !== expectedRecipients.length ||
      expectedRecipients.some((recipient) => recipientAuthEpochs[recipient.deviceId] !== recipient.authEpoch)) {
    throw new ApiError(409, "rotation_recipient_auth_epoch_mismatch");
  }
  const recovery = await first<{ authEpoch: number }>(env.DB, `
    SELECT r.auth_epoch AS authEpoch FROM recovery_credentials r
    JOIN workspaces w ON w.owner_user_id = r.user_id
    WHERE w.workspace_id = ?1
  `, principal.workspaceId);
  if (!recovery || mapInteger(manifest, "recoveryAuthEpoch") !== recovery.authEpoch) {
    throw new ApiError(409, "rotation_recovery_auth_epoch_mismatch");
  }
  const recoveryWrappedKey = requireString(value, "recoveryWrappedKey", 32, 16 * 1024);
  const recoveryHash = await sha256Base64Url(utf8Bytes(recoveryWrappedKey));
  if (mapString(manifest, "recoveryEnvelopeHash") !== recoveryHash) {
    throw new ApiError(400, "rotation_recovery_envelope_hash_mismatch");
  }
  const statements = [
    env.DB.prepare(`UPDATE key_rotations SET
      key_commitment = ?2, manifest_cbor = ?3, manifest_signature = ?4
      WHERE rotation_id = ?1 AND status = 'leased' AND lease_expires_at > ?5
        AND proposer_device_id = ?6
    `).bind(rotationId, keyCommitment, manifestCbor, signature, now, principal.deviceId),
    ...envelopes.map((envelope) => env.DB.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`).bind(
      principal.workspaceId, rotation.toEpoch, envelope.deviceId, rotationId,
      keyCommitment, envelope.wrappedKey, principal.deviceId, signature, now,
    )),
    env.DB.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)`).bind(
      principal.workspaceId, rotation.toEpoch, rotationId, keyCommitment, recoveryWrappedKey, now,
    ),
    env.DB.prepare(`INSERT INTO rotation_commits(rotation_id, committed_at)
      VALUES (?1, ?2)`).bind(rotationId, now),
  ];
  await atomicBatch(env.DB, statements);
  return {
    rotationId,
    workspaceId: principal.workspaceId,
    fromEpoch: rotation.fromEpoch,
    activeKeyEpoch: rotation.toEpoch,
    keyCommitment,
    status: "committed",
  };
}

export async function acknowledgeRotation(
  request: Request,
  env: Env,
  principal: CapabilityPrincipal,
  rotationIdInput: string,
  now = Date.now(),
): Promise<unknown> {
  const rotationId = requireUuid(rotationIdInput, "rotation_id");
  const { value } = await readJson(request, 16 * 1024);
  const keyCommitment = requireString(value, "keyCommitment", 43, 128);
  const signature = requireString(value, "signature", 80, 256);
  const rotation = await loadRotation(env, principal.workspaceId, rotationId);
  if (!rotation || rotation.status !== "committed" || rotation.keyCommitment !== keyCommitment ||
      principal.keyEpoch !== rotation.toEpoch) {
    throw new ApiError(409, "rotation_not_acknowledgeable");
  }
  const recipient = await first<{ present: number }>(env.DB, `
    SELECT 1 AS present FROM key_rotation_recipients
    WHERE rotation_id = ?1 AND device_id = ?2
  `, rotationId, principal.deviceId);
  if (!recipient) throw new ApiError(403, "rotation_recipient_required");
  const manifest = canonicalJson({
    workspaceId: principal.workspaceId,
    rotationId,
    deviceId: principal.deviceId,
    keyEpoch: rotation.toEpoch,
    keyCommitment,
  });
  if (!(await verifyEd25519(
    principal.signingPublicKey,
    signature,
    domainSeparatedMessage("rotation-ack", utf8Bytes(manifest)),
  ))) throw new ApiError(401, "rotation_ack_signature_invalid");
  await run(env.DB, `INSERT INTO key_rotation_acks(
    rotation_id, device_id, key_commitment, signature, created_at
  ) VALUES (?1, ?2, ?3, ?4, ?5)
  ON CONFLICT(rotation_id, device_id) DO NOTHING`,
  rotationId, principal.deviceId, keyCommitment, signature, now);
  return { rotationId, deviceId: principal.deviceId, acknowledged: true };
}
