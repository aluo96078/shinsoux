import {
  ApiError,
  readJson,
  requireObject,
  requireString,
  utf8Bytes,
} from "../http.ts";
import type { Env } from "../runtime.ts";
import {
  canonicalJson,
  domainSeparatedMessage,
  publicTokenCommitment,
  requireHighEntropySecret,
  sha256Base64Url,
  timingSafeSecretEqual,
  verifyEd25519,
} from "../crypto/base.ts";
import { requirePublicKey, requireUuid } from "../protocol.ts";
import { atomicBatch, first } from "../storage/d1.ts";

const JSON_LIMIT = 256 * 1024;

interface DeviceInput {
  deviceId: string;
  displayName: string;
  platform: string;
  signingPublicKey: string;
  wrappingPublicKey: string;
  deviceToken: string;
}

interface InitialKeyInput {
  keyCommitment: string;
  deviceWrappedKey: string;
  deviceEnvelopeSignature: string;
  recoverySigningPublicKey: string;
  recoveryWrappingPublicKey: string;
  recoveryWrappedKey: string;
  recoveryDeviceTrustSignature: string;
}

interface ResetRow {
  resetId: string;
  workspaceId: string;
  ownerUserId: string;
  handoffSecretHash: string;
  handoffExpiresAt: number;
  status: string;
}

function parsePlatform(value: unknown): string {
  if (typeof value !== "string" || !["android", "ios", "macos", "windows", "other"].includes(value)) {
    throw new ApiError(400, "invalid_platform");
  }
  return value;
}

function parseDevice(value: unknown): DeviceInput {
  const object = requireObject(value, "device");
  const deviceToken = requireString(object, "deviceToken", 32, 256);
  requireHighEntropySecret(deviceToken, "device_token");
  return {
    deviceId: requireUuid(object.deviceId, "device_id"),
    displayName: requireString(object, "displayName", 1, 120),
    platform: parsePlatform(object.platform),
    signingPublicKey: requirePublicKey(object.signingPublicKey, "signing_public_key"),
    wrappingPublicKey: requirePublicKey(object.wrappingPublicKey, "wrapping_public_key"),
    deviceToken,
  };
}

function parseInitialKeys(value: unknown): InitialKeyInput {
  const object = requireObject(value, "initialKeys");
  return {
    keyCommitment: requireString(object, "keyCommitment", 43, 128),
    deviceWrappedKey: requireString(object, "deviceWrappedKey", 32, 16 * 1024),
    deviceEnvelopeSignature: requireString(object, "deviceEnvelopeSignature", 80, 256),
    recoverySigningPublicKey: requirePublicKey(object.recoverySigningPublicKey, "recovery_signing_public_key"),
    recoveryWrappingPublicKey: requirePublicKey(object.recoveryWrappingPublicKey, "recovery_wrapping_public_key"),
    recoveryWrappedKey: requireString(object, "recoveryWrappedKey", 32, 16 * 1024),
    recoveryDeviceTrustSignature: requireString(object, "recoveryDeviceTrustSignature", 80, 256),
  };
}

async function verifyInitialClaim(
  instanceId: string,
  userId: string,
  workspaceId: string,
  device: DeviceInput,
  keys: InitialKeyInput,
  deviceTokenHash: string,
  claimSignature: string,
): Promise<string> {
  const recoveryTrustManifest = canonicalJson({
    instanceId,
    userId,
    workspaceId,
    deviceId: device.deviceId,
    signingPublicKey: device.signingPublicKey,
    wrappingPublicKey: device.wrappingPublicKey,
    recoverySigningPublicKey: keys.recoverySigningPublicKey,
    recoveryWrappingPublicKey: keys.recoveryWrappingPublicKey,
  });
  if (!(await verifyEd25519(
    keys.recoverySigningPublicKey,
    keys.recoveryDeviceTrustSignature,
    domainSeparatedMessage("initial-device-recovery-trust", utf8Bytes(recoveryTrustManifest)),
  ))) throw new ApiError(401, "recovery_device_trust_signature_invalid");

  // An emergency reset deliberately starts a fresh cryptographic generation. It reuses the
  // audited initial-attestation format so existing clients can trust the new device/Recovery root,
  // while the one-time operator handoff is the authority that permits replacing the old lineage.
  const manifest = canonicalJson({
    instanceId,
    userId,
    workspaceId,
    deviceId: device.deviceId,
    signingPublicKey: device.signingPublicKey,
    wrappingPublicKey: device.wrappingPublicKey,
    deviceTokenHash,
    keyEpoch: 1,
    keyCommitment: keys.keyCommitment,
    deviceWrappedKeyHash: await sha256Base64Url(utf8Bytes(keys.deviceWrappedKey)),
    recoverySigningPublicKey: keys.recoverySigningPublicKey,
    recoveryWrappingPublicKey: keys.recoveryWrappingPublicKey,
    recoveryWrappedKeyHash: await sha256Base64Url(utf8Bytes(keys.recoveryWrappedKey)),
    recoveryDeviceTrustSignature: keys.recoveryDeviceTrustSignature,
  });
  if (!(await verifyEd25519(
    device.signingPublicKey,
    claimSignature,
    domainSeparatedMessage("initial-workspace-claim", utf8Bytes(manifest)),
  ))) throw new ApiError(401, "claim_signature_invalid");
  return manifest;
}

/**
 * Consumes the operator-created handoff after D1 reset and exact-prefix R2 purge are both durable.
 * The high-entropy handoff token is the only public authority; bootstrap and old app credentials
 * are intentionally neither read nor accepted by this endpoint.
 */
export async function claimEmergencyWorkspaceHandoff(
  request: Request,
  env: Env,
  now = Date.now(),
): Promise<unknown> {
  const { value } = await readJson(request, JSON_LIMIT);
  const resetId = requireUuid(value.resetId, "reset_id");
  const workspaceId = requireUuid(value.workspaceId, "workspace_id");
  const userId = requireUuid(value.userId, "user_id");
  requireString(value, "displayName", 1, 120);
  const handoffSecret = requireString(value, "handoffSecret", 32, 512);
  requireHighEntropySecret(handoffSecret, "handoff_secret");
  const handoffSecretHash = await publicTokenCommitment(handoffSecret, "emergency_handoff");

  const reset = await first<ResetRow>(env.DB, `
    SELECT reset_id AS resetId, workspace_id AS workspaceId, owner_user_id AS ownerUserId,
           handoff_secret_hash AS handoffSecretHash, handoff_expires_at AS handoffExpiresAt,
           status
    FROM emergency_workspace_resets
    WHERE reset_id = ?1 AND workspace_id = ?2
  `, resetId, workspaceId);
  if (!reset || reset.ownerUserId !== userId || reset.status !== "handoff_ready" ||
      reset.handoffExpiresAt <= now ||
      !(await timingSafeSecretEqual(reset.handoffSecretHash, handoffSecretHash))) {
    throw new ApiError(410, "emergency_handoff_invalid_or_expired");
  }

  const instanceId = requireUuid(env.INSTANCE_ID, "instance_id");
  const device = parseDevice(value.device);
  const keys = parseInitialKeys(value.initialKeys);
  const claimSignature = requireString(value, "claimSignature", 80, 256);
  const deviceTokenHash = await publicTokenCommitment(device.deviceToken, "device_token");
  const attestationManifest = await verifyInitialClaim(
    instanceId,
    userId,
    workspaceId,
    device,
    keys,
    deviceTokenHash,
    claimSignature,
  );
  const rotationId = `emergency:${resetId}`;

  await atomicBatch(env.DB, [
    env.DB.prepare(`INSERT INTO devices(
      device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
      token_hash, status, auth_epoch, created_at, last_seen_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'active', 1, ?8, ?8)`).bind(
      device.deviceId,
      userId,
      device.displayName,
      device.platform,
      device.signingPublicKey,
      device.wrappingPublicKey,
      deviceTokenHash,
      now,
    ),
    env.DB.prepare(`INSERT INTO recovery_credentials(
      user_id, signing_public_key, wrapping_public_key, auth_epoch, rotated_at
    ) VALUES (?1, ?2, ?3, 1, ?4)`).bind(
      userId,
      keys.recoverySigningPublicKey,
      keys.recoveryWrappingPublicKey,
      now,
    ),
    env.DB.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?1, 1, ?2, ?3, ?4, ?5, ?2, ?6, ?7)`).bind(
      workspaceId,
      device.deviceId,
      rotationId,
      keys.keyCommitment,
      keys.deviceWrappedKey,
      keys.deviceEnvelopeSignature,
      now,
    ),
    env.DB.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?1, 1, ?2, ?3, ?4, ?5)`).bind(
      workspaceId,
      rotationId,
      keys.keyCommitment,
      keys.recoveryWrappedKey,
      now,
    ),
    env.DB.prepare(`INSERT INTO device_key_attestations(
      device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
      signature_domain, manifest_json, signature, created_at
    ) VALUES (?1, ?2, 'initial', ?1, ?3, 'initial-workspace-claim', ?4, ?5, ?6)`).bind(
      device.deviceId,
      workspaceId,
      device.signingPublicKey,
      attestationManifest,
      claimSignature,
      now,
    ),
    env.DB.prepare(`INSERT INTO emergency_workspace_handoffs(
      reset_id, workspace_id, owner_user_id, device_id,
      handoff_secret_hash, key_commitment, consumed_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)`).bind(
      resetId,
      workspaceId,
      userId,
      device.deviceId,
      handoffSecretHash,
      keys.keyCommitment,
      now,
    ),
  ]);

  return { instanceId, userId, workspaceId, deviceId: device.deviceId, keyEpoch: 1, resetId };
}
