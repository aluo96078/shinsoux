import {
  ApiError,
  readJson,
  requireInteger,
  requireObject,
  requireString,
  utf8Bytes,
} from "../http.ts";
import type { Env } from "../runtime.ts";
import {
  canonicalJson,
  domainSeparatedMessage,
  hashSecret,
  publicTokenCommitment,
  randomToken,
  randomUuid,
  requireHighEntropySecret,
  sha256Base64Url,
  timingSafeSecretEqual,
  verifyEd25519,
} from "../crypto/base.ts";
import { requirePublicKey, requireUuid } from "../protocol.ts";
import { all, atomicBatch, first, run } from "../storage/d1.ts";
import {
  authChallengeMessage,
  authenticateAccess,
  issueAccessToken,
  issueWorkspaceCapability,
  requireControlSignature,
} from "../auth/service.ts";
import { loadUserDevices } from "./device-directory.ts";

const JSON_LIMIT = 256 * 1024;
const FIVE_MINUTES = 5 * 60_000;

interface DeviceInput {
  deviceId: string;
  displayName: string;
  platform: string;
  signingPublicKey: string;
  wrappingPublicKey: string;
  deviceToken: string;
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

interface InitialKeyInput {
  keyCommitment: string;
  deviceWrappedKey: string;
  deviceEnvelopeSignature: string;
  recoverySigningPublicKey: string;
  recoveryWrappingPublicKey: string;
  recoveryWrappedKey: string;
  recoveryDeviceTrustSignature: string;
}

function parseInitialKeys(value: unknown): InitialKeyInput {
  const object = requireObject(value, "initialKeys");
  const keyCommitment = requireString(object, "keyCommitment", 43, 128);
  if (keyCommitment.length < 43) throw new ApiError(400, "invalid_key_commitment");
  return {
    keyCommitment,
    deviceWrappedKey: requireString(object, "deviceWrappedKey", 32, 16 * 1024),
    deviceEnvelopeSignature: requireString(object, "deviceEnvelopeSignature", 80, 256),
    recoverySigningPublicKey: requirePublicKey(object.recoverySigningPublicKey, "recovery_signing_public_key"),
    recoveryWrappingPublicKey: requirePublicKey(object.recoveryWrappingPublicKey, "recovery_wrapping_public_key"),
    recoveryWrappedKey: requireString(object, "recoveryWrappedKey", 32, 16 * 1024),
    recoveryDeviceTrustSignature: requireString(object, "recoveryDeviceTrustSignature", 80, 256),
  };
}

async function verifyInitialClaimSignature(
  instanceId: string,
  userId: string,
  workspaceId: string,
  device: DeviceInput,
  keys: InitialKeyInput,
  deviceTokenHash: string,
  claimSignature: string,
): Promise<string> {
  if (keys.recoverySigningPublicKey === device.signingPublicKey) {
    throw new ApiError(400, "recovery_device_signing_key_reuse");
  }
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
  const recoveryTrustValid = await verifyEd25519(
    keys.recoverySigningPublicKey,
    keys.recoveryDeviceTrustSignature,
    domainSeparatedMessage("initial-device-recovery-trust", utf8Bytes(recoveryTrustManifest)),
  );
  if (!recoveryTrustValid) throw new ApiError(401, "recovery_device_trust_signature_invalid");
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
  const valid = await verifyEd25519(
    device.signingPublicKey,
    claimSignature,
    domainSeparatedMessage("initial-workspace-claim", utf8Bytes(manifest)),
  );
  if (!valid) throw new ApiError(401, "claim_signature_invalid");
  return manifest;
}

export async function setupClaim(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, JSON_LIMIT);
  const bootstrapSecret = requireString(value, "bootstrapSecret", 32, 512);
  requireHighEntropySecret(bootstrapSecret, "bootstrap_secret");
  if (!env.BOOTSTRAP_SECRET || !(await timingSafeSecretEqual(bootstrapSecret, env.BOOTSTRAP_SECRET))) {
    throw new ApiError(401, "bootstrap_secret_invalid");
  }
  const existing = await first<{ initialized_at: number }>(env.DB, "SELECT initialized_at FROM installation LIMIT 1");
  if (existing) throw new ApiError(409, "instance_already_initialized");

  const instanceId = requireUuid(env.INSTANCE_ID, "instance_id");
  const userId = requireUuid(value.userId, "user_id");
  const workspaceId = requireUuid(value.workspaceId, "workspace_id");
  const displayName = requireString(value, "displayName", 1, 120);
  const device = parseDevice(value.device);
  const keys = parseInitialKeys(value.initialKeys);
  const claimSignature = requireString(value, "claimSignature", 80, 256);
  const deviceTokenHash = await publicTokenCommitment(device.deviceToken, "device_token");
  const attestationManifest = await verifyInitialClaimSignature(
    instanceId, userId, workspaceId, device, keys, deviceTokenHash, claimSignature,
  );
  const initialRotationId = `initial:${workspaceId}`;

  await atomicBatch(env.DB, [
    env.DB.prepare(`INSERT INTO installation(instance_id, schema_version, initialized_at, signup_mode)
      VALUES (?1, 1, ?2, 'invite_only')`).bind(instanceId, now),
    env.DB.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
      VALUES (?1, ?2, 'admin', 'active', 'private-default', ?3)`).bind(userId, displayName, now),
    env.DB.prepare(`INSERT INTO devices(
      device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
      token_hash, status, auth_epoch, created_at, last_seen_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'active', 1, ?8, ?8)`).bind(
      device.deviceId, userId, device.displayName, device.platform, device.signingPublicKey,
      device.wrappingPublicKey, deviceTokenHash, now,
    ),
    env.DB.prepare(`INSERT INTO workspaces(
      workspace_id, owner_user_id, status, active_key_epoch, created_at
    ) VALUES (?1, ?2, 'active', 1, ?3)`).bind(workspaceId, userId, now),
    env.DB.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
      VALUES (?1, ?2, 'owner', 'active', 1)`).bind(workspaceId, userId),
    env.DB.prepare(`INSERT INTO recovery_credentials(
      user_id, signing_public_key, wrapping_public_key, auth_epoch, rotated_at
    ) VALUES (?1, ?2, ?3, 1, ?4)`).bind(
      userId, keys.recoverySigningPublicKey, keys.recoveryWrappingPublicKey, now,
    ),
    env.DB.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?1, 1, ?2, ?3, ?4, ?5, ?2, ?6, ?7)`).bind(
      workspaceId, device.deviceId, initialRotationId, keys.keyCommitment,
      keys.deviceWrappedKey, keys.deviceEnvelopeSignature, now,
    ),
    env.DB.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?1, 1, ?2, ?3, ?4, ?5)`).bind(
      workspaceId, initialRotationId, keys.keyCommitment, keys.recoveryWrappedKey, now,
    ),
    env.DB.prepare(`INSERT INTO device_key_attestations(
      device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
      signature_domain, manifest_json, signature, created_at
    ) VALUES (?1, ?2, 'initial', ?1, ?3, 'initial-workspace-claim', ?4, ?5, ?6)`).bind(
      device.deviceId, workspaceId, device.signingPublicKey,
      attestationManifest, claimSignature, now,
    ),
  ]);
  return { instanceId, userId, workspaceId, deviceId: device.deviceId, keyEpoch: 1 };
}

export async function createInvite(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { raw, value } = await readJson(request, 16 * 1024);
  const access = await authenticateAccess(request, env, now);
  await requireControlSignature(request, raw, access, env, now);
  if (access.instanceRole !== "admin") throw new ApiError(403, "admin_required");
  const ttlSeconds = value.ttlSeconds === undefined
    ? 86_400
    : requireInteger(value, "ttlSeconds", 300, 7 * 86_400);
  const displayNameHint = typeof value.displayNameHint === "string"
    ? value.displayNameHint.slice(0, 120)
    : null;
  const userCount = await first<{ count: number }>(env.DB, `
    SELECT COUNT(*) AS count FROM users WHERE status <> 'deleted'
  `);
  const maximum = await first<{ maximum: number }>(env.DB, `
    SELECT max_users AS maximum FROM quota_profiles WHERE quota_profile_id = 'private-default'
  `);
  if ((userCount?.count ?? 0) >= (maximum?.maximum ?? 25)) throw new ApiError(507, "user_quota_exceeded");

  const inviteId = randomUuid();
  const inviteSecret = randomToken();
  const secretHash = await hashSecret(inviteSecret, env.TOKEN_PEPPER, "invite");
  const expiresAt = now + ttlSeconds * 1000;
  const created = await env.DB.prepare(`INSERT INTO invites(
    invite_id, created_by_device_id, secret_hash, display_name_hint, expires_at, status, created_at
  )
  SELECT ?1, d.device_id, ?3, ?4, ?5, 'active', ?6
  FROM access_tokens a JOIN devices d ON d.device_id = a.device_id
  JOIN users u ON u.user_id = d.user_id
  WHERE a.access_token_id = ?7 AND d.device_id = ?2
    AND a.revoked_at IS NULL AND a.expires_at > ?6
    AND a.device_auth_epoch = d.auth_epoch
    AND d.status = 'active' AND u.status = 'active' AND u.instance_role = 'admin'
  `).bind(inviteId, access.deviceId, secretHash, displayNameHint, expiresAt, now, access.accessTokenId).run();
  if (!created.success || (created.meta?.changes ?? 0) !== 1) {
    throw new ApiError(401, "access_token_invalidated");
  }
  return { inviteId, inviteSecret, expiresAt, path: `/v1/invites/redeem` };
}

export async function redeemInvite(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, JSON_LIMIT);
  const inviteSecret = requireString(value, "inviteSecret", 32, 512);
  requireHighEntropySecret(inviteSecret, "invite_secret");
  const secretHash = await hashSecret(inviteSecret, env.TOKEN_PEPPER, "invite");
  const invite = await first<{ inviteId: string }>(env.DB, `
    SELECT invite_id AS inviteId FROM invites
    WHERE secret_hash = ?1 AND status = 'active' AND expires_at > ?2
  `, secretHash, now);
  if (!invite) throw new ApiError(410, "invite_invalid_or_expired");

  const userId = requireUuid(value.userId, "user_id");
  const workspaceId = requireUuid(value.workspaceId, "workspace_id");
  const displayName = requireString(value, "displayName", 1, 120);
  const device = parseDevice(value.device);
  const keys = parseInitialKeys(value.initialKeys);
  const claimSignature = requireString(value, "claimSignature", 80, 256);
  const instanceId = requireUuid(env.INSTANCE_ID, "instance_id");
  const deviceTokenHash = await publicTokenCommitment(device.deviceToken, "device_token");
  const attestationManifest = await verifyInitialClaimSignature(
    instanceId, userId, workspaceId, device, keys, deviceTokenHash, claimSignature,
  );
  const initialRotationId = `initial:${workspaceId}`;

  await atomicBatch(env.DB, [
    env.DB.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
      VALUES (?1, ?2, 'user', 'active', 'private-default', ?3)`).bind(userId, displayName, now),
    env.DB.prepare(`INSERT INTO devices(
      device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
      token_hash, status, auth_epoch, created_at, last_seen_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'active', 1, ?8, ?8)`).bind(
      device.deviceId, userId, device.displayName, device.platform, device.signingPublicKey,
      device.wrappingPublicKey, deviceTokenHash, now,
    ),
    env.DB.prepare(`INSERT INTO workspaces(workspace_id, owner_user_id, status, active_key_epoch, created_at)
      VALUES (?1, ?2, 'active', 1, ?3)`).bind(workspaceId, userId, now),
    env.DB.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
      VALUES (?1, ?2, 'owner', 'active', 1)`).bind(workspaceId, userId),
    env.DB.prepare(`INSERT INTO recovery_credentials(
      user_id, signing_public_key, wrapping_public_key, auth_epoch, rotated_at
    ) VALUES (?1, ?2, ?3, 1, ?4)`).bind(
      userId, keys.recoverySigningPublicKey, keys.recoveryWrappingPublicKey, now,
    ),
    env.DB.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?1, 1, ?2, ?3, ?4, ?5, ?2, ?6, ?7)`).bind(
      workspaceId, device.deviceId, initialRotationId, keys.keyCommitment,
      keys.deviceWrappedKey, keys.deviceEnvelopeSignature, now,
    ),
    env.DB.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?1, 1, ?2, ?3, ?4, ?5)`).bind(
      workspaceId, initialRotationId, keys.keyCommitment, keys.recoveryWrappedKey, now,
    ),
    env.DB.prepare(`INSERT INTO device_key_attestations(
      device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
      signature_domain, manifest_json, signature, created_at
    ) VALUES (?1, ?2, 'initial', ?1, ?3, 'initial-workspace-claim', ?4, ?5, ?6)`).bind(
      device.deviceId, workspaceId, device.signingPublicKey,
      attestationManifest, claimSignature, now,
    ),
    env.DB.prepare(`INSERT INTO invite_redemptions(invite_id, user_id, redeemed_at)
      VALUES (?1, ?2, ?3)`).bind(invite.inviteId, userId, now),
  ]);
  return { instanceId, userId, workspaceId, deviceId: device.deviceId, keyEpoch: 1 };
}

export async function createAuthChallenge(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, 8 * 1024);
  const deviceId = requireUuid(value.deviceId, "device_id");
  const device = await first<{ deviceId: string }>(env.DB, `
    SELECT device_id AS deviceId FROM devices WHERE device_id = ?1 AND status = 'active'
  `, deviceId);
  if (!device) throw new ApiError(401, "device_authentication_failed");
  const challengeId = randomUuid();
  const challenge = randomToken();
  const challengeHash = await hashSecret(challenge, env.TOKEN_PEPPER, "auth-challenge");
  const expiresAt = now + 2 * 60_000;
  await run(env.DB, `INSERT INTO auth_challenges(
    challenge_id, device_id, challenge_hash, expires_at, created_at
  ) VALUES (?1, ?2, ?3, ?4, ?5)`, challengeId, deviceId, challengeHash, expiresAt, now);
  return { challengeId, challenge, expiresAt };
}

export async function exchangeAuthToken(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, 16 * 1024);
  const deviceId = requireUuid(value.deviceId, "device_id");
  const challengeId = requireUuid(value.challengeId, "challenge_id");
  const challenge = requireString(value, "challenge", 32, 256);
  const deviceToken = requireString(value, "deviceToken", 32, 256);
  const signature = requireString(value, "signature", 80, 256);
  const row = await first<{
    challengeHash: string;
    tokenHash: string;
    signingPublicKey: string;
    authEpoch: number;
  }>(env.DB, `
    SELECT c.challenge_hash AS challengeHash, d.token_hash AS tokenHash,
           d.signing_public_key AS signingPublicKey, d.auth_epoch AS authEpoch
    FROM auth_challenges c JOIN devices d ON d.device_id = c.device_id
    WHERE c.challenge_id = ?1 AND c.device_id = ?2 AND c.consumed_at IS NULL
      AND c.expires_at > ?3 AND d.status = 'active'
  `, challengeId, deviceId, now);
  if (!row) throw new ApiError(401, "device_authentication_failed");
  const [challengeHash, tokenHash] = await Promise.all([
    hashSecret(challenge, env.TOKEN_PEPPER, "auth-challenge"),
    publicTokenCommitment(deviceToken, "device_token"),
  ]);
  if (challengeHash !== row.challengeHash || tokenHash !== row.tokenHash ||
      !(await verifyEd25519(row.signingPublicKey, signature, authChallengeMessage(challengeId, challenge, deviceId)))) {
    throw new ApiError(401, "device_authentication_failed");
  }
  const consumed = await env.DB.prepare(`
    UPDATE auth_challenges SET consumed_at = ?3
    WHERE challenge_id = ?1 AND device_id = ?2 AND consumed_at IS NULL AND expires_at > ?3
    RETURNING challenge_id
  `).bind(challengeId, deviceId, now).all();
  if (!consumed.success || !consumed.results?.length) throw new ApiError(409, "challenge_replayed");
  await run(env.DB, "UPDATE devices SET last_seen_at = ?2 WHERE device_id = ?1", deviceId, now);
  return issueAccessToken(env, { deviceId, authEpoch: row.authEpoch }, now);
}

export async function createCapability(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, 8 * 1024);
  const workspaceId = requireUuid(value.workspaceId, "workspace_id");
  const access = await authenticateAccess(request, env, now);
  return issueWorkspaceCapability(env, access, workspaceId, now);
}

export async function createPairing(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { raw, value } = await readJson(request, 16 * 1024);
  const access = await authenticateAccess(request, env, now);
  await requireControlSignature(request, raw, access, env, now);
  const workspaceId = requireUuid(value.workspaceId, "workspace_id");
  const membership = await first<{ allowed: number }>(env.DB, `
    SELECT 1 AS allowed FROM memberships
    WHERE workspace_id = ?1 AND user_id = ?2 AND status = 'active' AND role IN ('owner', 'editor')
  `, workspaceId, access.userId);
  if (!membership) throw new ApiError(403, "workspace_forbidden");
  const deviceUsage = await first<{ count: number; maximum: number }>(env.DB, `
    SELECT COUNT(d.device_id) AS count, q.max_devices_per_user AS maximum
    FROM users u JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
    LEFT JOIN devices d ON d.user_id = u.user_id AND d.status = 'active'
    WHERE u.user_id = ?1 AND u.status = 'active'
    GROUP BY q.max_devices_per_user
  `, access.userId);
  if (!deviceUsage) throw new ApiError(401, "access_token_invalidated");
  if (deviceUsage.count >= deviceUsage.maximum) throw new ApiError(507, "device_quota_exceeded");
  const pairingId = randomUuid();
  const pairingSecret = randomToken();
  const secretHash = await hashSecret(pairingSecret, env.TOKEN_PEPPER, "pairing");
  const transcriptNonce = randomToken();
  const expiresAt = now + FIVE_MINUTES;
  const created = await env.DB.prepare(`INSERT INTO pairing_sessions(
    pairing_id, workspace_id, sponsor_device_id, secret_hash, transcript_nonce,
    status, expires_at, created_at
  )
  SELECT ?1, w.workspace_id, d.device_id, ?4, ?5, 'open', ?6, ?7
  FROM access_tokens a JOIN devices d ON d.device_id = a.device_id
  JOIN memberships m ON m.user_id = d.user_id
  JOIN workspaces w ON w.workspace_id = m.workspace_id
  WHERE a.access_token_id = ?8 AND w.workspace_id = ?2 AND d.device_id = ?3
    AND a.revoked_at IS NULL AND a.expires_at > ?7 AND a.device_auth_epoch = d.auth_epoch
    AND d.status = 'active' AND m.status = 'active' AND m.role IN ('owner', 'editor')
    AND w.status = 'active'
  `).bind(
    pairingId, workspaceId, access.deviceId, secretHash, transcriptNonce, expiresAt, now,
    access.accessTokenId,
  ).run();
  if (!created.success || (created.meta?.changes ?? 0) !== 1) {
    throw new ApiError(401, "access_token_invalidated");
  }
  return { pairingId, pairingSecret, transcriptNonce, expiresAt };
}

export async function submitPairingCandidate(
  request: Request,
  env: Env,
  expectedPairingId?: string,
  now = Date.now(),
): Promise<unknown> {
  const { value } = await readJson(request, 32 * 1024);
  const pairingId = requireUuid(value.pairingId, "pairing_id");
  if (expectedPairingId && pairingId !== expectedPairingId) {
    throw new ApiError(400, "pairing_path_body_mismatch");
  }
  const pairingSecret = requireString(value, "pairingSecret", 32, 256);
  requireHighEntropySecret(pairingSecret, "pairing_secret");
  const device = parseDevice(value.device);
  const secretHash = await hashSecret(pairingSecret, env.TOKEN_PEPPER, "pairing");
  const tokenHash = await publicTokenCommitment(device.deviceToken, "device_token");
  const result = await env.DB.prepare(`
    UPDATE pairing_sessions SET
      status = 'candidate', candidate_device_id = ?3, candidate_display_name = ?4,
      candidate_platform = ?5, candidate_signing_public_key = ?6,
      candidate_wrapping_public_key = ?7, candidate_token_hash = ?8,
      candidate_submitted_at = ?9
    WHERE pairing_id = ?1 AND secret_hash = ?2 AND status = 'open' AND expires_at > ?9
  `).bind(
    pairingId, secretHash, device.deviceId, device.displayName, device.platform,
    device.signingPublicKey, device.wrappingPublicKey, tokenHash, now,
  ).run();
  if (!result.success || (result.meta?.changes ?? 0) !== 1) {
    throw new ApiError(410, "pairing_invalid_or_expired");
  }
  const session = await pairingView(env, pairingId, null, pairingSecret, now);
  return { pairingId, status: "candidate", shortCode: (session as Record<string, unknown>).shortCode };
}

async function pairingTranscript(row: Record<string, unknown>): Promise<string> {
  return canonicalJson({
    pairingId: row.pairingId,
    workspaceId: row.workspaceId,
    sponsorDeviceId: row.sponsorDeviceId,
    sponsorSigningPublicKey: row.sponsorSigningPublicKey,
    sponsorWrappingPublicKey: row.sponsorWrappingPublicKey,
    transcriptNonce: row.transcriptNonce,
    candidateDeviceId: row.candidateDeviceId,
    candidateDisplayName: row.candidateDisplayName,
    candidatePlatform: row.candidatePlatform,
    candidateSigningPublicKey: row.candidateSigningPublicKey,
    candidateWrappingPublicKey: row.candidateWrappingPublicKey,
    candidateTokenHash: row.candidateTokenHash,
    expiresAt: row.expiresAt,
  });
}

async function pairingShortCode(row: Record<string, unknown>): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", utf8Bytes(await pairingTranscript(row)));
  const view = new DataView(hash);
  return String(view.getUint32(0, false) % 1_000_000).padStart(6, "0");
}

async function loadPairing(env: Env, pairingId: string, now: number): Promise<Record<string, unknown>> {
  const row = await first<Record<string, unknown>>(env.DB, `
    SELECT p.pairing_id AS pairingId, p.workspace_id AS workspaceId,
           p.sponsor_device_id AS sponsorDeviceId,
           sponsor.signing_public_key AS sponsorSigningPublicKey,
           sponsor.wrapping_public_key AS sponsorWrappingPublicKey, p.secret_hash AS secretHash,
           p.transcript_nonce AS transcriptNonce, p.status, p.expires_at AS expiresAt,
           p.candidate_device_id AS candidateDeviceId,
           p.candidate_display_name AS candidateDisplayName,
           p.candidate_platform AS candidatePlatform,
           p.candidate_signing_public_key AS candidateSigningPublicKey,
           p.candidate_wrapping_public_key AS candidateWrappingPublicKey,
           p.candidate_token_hash AS candidateTokenHash, p.approved_at AS approvedAt
    FROM pairing_sessions p JOIN devices sponsor ON sponsor.device_id = p.sponsor_device_id
    WHERE p.pairing_id = ?1 AND p.expires_at > ?2
  `, pairingId, now);
  if (!row) throw new ApiError(410, "pairing_invalid_or_expired");
  return row;
}

async function pairingKeyRequirements(env: Env, workspaceId: string): Promise<unknown> {
  const workspace = await first<{ headSeq: number; activeKeyEpoch: number }>(env.DB, `
    SELECT head_seq AS headSeq, active_key_epoch AS activeKeyEpoch
    FROM workspaces WHERE workspace_id = ?1 AND status = 'active'
  `, workspaceId);
  if (!workspace) throw new ApiError(404, "workspace_not_found");
  const requiredEpochs = await all<{ keyEpoch: number }>(env.DB, `
    SELECT DISTINCT key_epoch AS keyEpoch FROM (
      SELECT active_key_epoch AS key_epoch FROM workspaces WHERE workspace_id = ?1
      UNION SELECT key_epoch FROM checkpoints WHERE workspace_id = ?1 AND status = 'stable'
      UNION SELECT key_epoch FROM events WHERE workspace_id = ?1
    ) ORDER BY key_epoch
  `, workspaceId);
  const retainedStableCheckpoints = await all<{
    checkpointId: string;
    throughWorkspaceSeq: number;
    keyEpoch: number;
    ciphertextSha256: string;
  }>(env.DB, `
    SELECT checkpoint_id AS checkpointId, through_seq AS throughWorkspaceSeq,
           key_epoch AS keyEpoch, ciphertext_sha256 AS ciphertextSha256
    FROM checkpoints
    WHERE workspace_id = ?1 AND status = 'stable'
    ORDER BY through_seq DESC, promoted_at DESC, created_at DESC LIMIT 3
  `, workspaceId);
  const recoveryBase = retainedStableCheckpoints.length > 1
    ? retainedStableCheckpoints[1]
    : retainedStableCheckpoints[0] ?? null;
  return {
    requiredKeyEpochs: requiredEpochs.map((entry) => entry.keyEpoch),
    activeKeyEpoch: workspace.activeKeyEpoch,
    headSeq: workspace.headSeq,
    retainedStableCheckpoints,
    recoveryBaseCheckpointId: recoveryBase?.checkpointId ?? null,
    recoveryBaseThroughWorkspaceSeq: recoveryBase?.throughWorkspaceSeq ?? 0,
  };
}

export async function pairingView(
  env: Env,
  pairingId: string,
  sponsorDeviceId: string | null,
  pairingSecret: string | null,
  now = Date.now(),
): Promise<unknown> {
  const row = await loadPairing(env, pairingId, now);
  if (sponsorDeviceId) {
    if (row.sponsorDeviceId !== sponsorDeviceId) throw new ApiError(403, "pairing_forbidden");
  } else {
    if (!pairingSecret) throw new ApiError(401, "pairing_secret_required");
    const hash = await hashSecret(pairingSecret, env.TOKEN_PEPPER, "pairing");
    if (hash !== row.secretHash) throw new ApiError(401, "pairing_secret_invalid");
  }
  const response: Record<string, unknown> = {
    pairingId: row.pairingId,
    workspaceId: row.workspaceId,
    sponsorDeviceId: row.sponsorDeviceId,
    sponsorSigningPublicKey: row.sponsorSigningPublicKey,
    sponsorWrappingPublicKey: row.sponsorWrappingPublicKey,
    transcriptNonce: row.transcriptNonce,
    status: row.status,
    expiresAt: row.expiresAt,
  };
  if (row.candidateDeviceId) {
    Object.assign(response, {
      candidate: {
        deviceId: row.candidateDeviceId,
        displayName: row.candidateDisplayName,
        platform: row.candidatePlatform,
        signingPublicKey: row.candidateSigningPublicKey,
        wrappingPublicKey: row.candidateWrappingPublicKey,
        tokenCommitment: row.candidateTokenHash,
      },
      shortCode: await pairingShortCode(row),
    });
  }
  if (sponsorDeviceId) {
    response.keyRequirements = await pairingKeyRequirements(env, String(row.workspaceId));
  }
  if (!sponsorDeviceId && row.status === "approved") {
    const activation = await first<Record<string, unknown>>(env.DB, `
      SELECT sponsor.user_id AS userId, p.workspace_id AS workspaceId,
             candidate.device_id AS deviceId, candidate.auth_epoch AS deviceAuthEpoch,
             membership.auth_epoch AS membershipAuthEpoch,
             workspace.active_key_epoch AS activeKeyEpoch
      FROM pairing_sessions p
      JOIN devices sponsor ON sponsor.device_id = p.sponsor_device_id
      JOIN devices candidate ON candidate.device_id = p.candidate_device_id
      JOIN memberships membership
        ON membership.workspace_id = p.workspace_id AND membership.user_id = sponsor.user_id
      JOIN workspaces workspace ON workspace.workspace_id = p.workspace_id
      WHERE p.pairing_id = ?1 AND p.status = 'approved'
    `, pairingId);
    if (!activation) throw new ApiError(503, "pairing_activation_incomplete");
    const keyEnvelopes = await all(env.DB, `
      SELECT key_epoch AS keyEpoch, key_commitment AS keyCommitment,
             wrapped_key AS wrappedKey, wrapped_by_device_id AS wrappedByDeviceId,
             signature
      FROM workspace_device_keys
      WHERE workspace_id = ?1 AND device_id = ?2 ORDER BY key_epoch
    `, row.workspaceId, row.candidateDeviceId);
    const approval = await first(env.DB, `
      SELECT attestor_device_id AS attestorDeviceId, attestor_public_key AS attestorPublicKey,
             signature_domain AS signatureDomain, manifest_json AS manifestJson,
             signature
      FROM device_key_attestations
      WHERE device_id = ?1 AND workspace_id = ?2 AND attestation_type = 'pairing'
    `, row.candidateDeviceId, row.workspaceId);
    if (!approval) throw new ApiError(503, "pairing_attestation_incomplete");
    response.activation = { ...activation, keyEnvelopes, approval };
  }
  return response;
}

export async function getPairing(request: Request, env: Env, pairingId: string, now = Date.now()): Promise<unknown> {
  const pairingSecret = request.headers.get("X-Shinsou-Pairing-Secret");
  if (pairingSecret) return pairingView(env, pairingId, null, pairingSecret, now);
  const access = await authenticateAccess(request, env, now);
  return pairingView(env, pairingId, access.deviceId, null, now);
}

interface PairEnvelope {
  keyEpoch: number;
  keyCommitment: string;
  wrappedKey: string;
  signature: string;
}

function parsePairEnvelopes(value: unknown): PairEnvelope[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 64) {
    throw new ApiError(400, "invalid_key_envelopes");
  }
  return value.map((entry) => {
    const object = requireObject(entry, "key_envelope");
    return {
      keyEpoch: requireInteger(object, "keyEpoch", 1, 1_000_000),
      keyCommitment: requireString(object, "keyCommitment", 43, 128),
      wrappedKey: requireString(object, "wrappedKey", 32, 16 * 1024),
      signature: requireString(object, "signature", 80, 256),
    };
  });
}

export async function approvePairing(request: Request, env: Env, pairingId: string, now = Date.now()): Promise<unknown> {
  const { raw, value } = await readJson(request, JSON_LIMIT);
  const access = await authenticateAccess(request, env, now);
  await requireControlSignature(request, raw, access, env, now);
  const row = await loadPairing(env, pairingId, now);
  if (row.sponsorDeviceId !== access.deviceId || row.status !== "candidate") {
    throw new ApiError(403, "pairing_forbidden");
  }
  if (value.approved === false) {
    const denied = await env.DB.prepare(`
      UPDATE pairing_sessions SET status = 'cancelled'
      WHERE pairing_id = ?1 AND sponsor_device_id = ?2 AND status = 'candidate' AND expires_at > ?3
    `).bind(pairingId, access.deviceId, now).run();
    if (!denied.success || (denied.meta?.changes ?? 0) !== 1) {
      throw new ApiError(409, "pairing_state_changed");
    }
    return { pairingId, status: "cancelled" };
  }
  const envelopes = parsePairEnvelopes(value.keyEnvelopes);
  const requiredEpochRows = await all<{ keyEpoch: number }>(env.DB, `
    SELECT DISTINCT key_epoch AS keyEpoch FROM (
      SELECT active_key_epoch AS key_epoch FROM workspaces WHERE workspace_id = ?1
      UNION SELECT key_epoch FROM checkpoints WHERE workspace_id = ?1 AND status = 'stable'
      UNION SELECT key_epoch FROM events WHERE workspace_id = ?1
    ) ORDER BY key_epoch
  `, row.workspaceId);
  const expected = requiredEpochRows.map((entry) => entry.keyEpoch);
  const actual = [...new Set(envelopes.map((entry) => entry.keyEpoch))].sort((a, b) => a - b);
  if (expected.length !== actual.length || expected.some((epoch, index) => epoch !== actual[index])) {
    throw new ApiError(409, "pairing_incomplete_keyring");
  }
  const envelopeBindings = await Promise.all(envelopes
    .sort((left, right) => left.keyEpoch - right.keyEpoch)
    .map(async (entry) => ({
      keyEpoch: entry.keyEpoch,
      keyCommitment: entry.keyCommitment,
      wrappedKeyHash: await sha256Base64Url(utf8Bytes(entry.wrappedKey)),
      envelopeSignature: entry.signature,
    })));
  const approvalManifest = canonicalJson({
    transcript: JSON.parse(await pairingTranscript(row)),
    candidateTokenHash: row.candidateTokenHash,
    envelopes: envelopeBindings,
    expiresAt: row.expiresAt,
  });
  const approvalSignature = requireString(value, "approvalSignature", 80, 256);
  if (!(await verifyEd25519(
    access.signingPublicKey,
    approvalSignature,
    domainSeparatedMessage("pairing-approval", utf8Bytes(approvalManifest)),
  ))) throw new ApiError(401, "pairing_approval_signature_invalid");

  const candidateDeviceId = String(row.candidateDeviceId);
  const statements = [
    env.DB.prepare(`INSERT INTO devices(
      device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
      token_hash, status, auth_epoch, created_at, last_seen_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'active', 1, ?8, ?8)`).bind(
      candidateDeviceId, access.userId, row.candidateDisplayName, row.candidatePlatform,
      row.candidateSigningPublicKey, row.candidateWrappingPublicKey, row.candidateTokenHash, now,
    ),
    ...envelopes.map((entry) => env.DB.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`).bind(
      row.workspaceId, entry.keyEpoch, candidateDeviceId, `pair:${pairingId}:${entry.keyEpoch}`,
      entry.keyCommitment, entry.wrappedKey, access.deviceId, entry.signature, now,
    )),
    env.DB.prepare(`UPDATE pairing_sessions SET approval_signature = ?2
      WHERE pairing_id = ?1`).bind(pairingId, approvalSignature),
    env.DB.prepare(`INSERT INTO device_key_attestations(
      device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
      signature_domain, manifest_json, signature, created_at
    ) VALUES (?1, ?2, 'pairing', ?3, ?4, 'pairing-approval', ?5, ?6, ?7)`).bind(
      candidateDeviceId, row.workspaceId, access.deviceId, access.signingPublicKey,
      approvalManifest, approvalSignature, now,
    ),
    env.DB.prepare(`INSERT INTO pairing_activations(
      pairing_id, candidate_device_id, envelope_count, activated_at
    ) VALUES (?1, ?2, ?3, ?4)`).bind(pairingId, candidateDeviceId, envelopes.length, now),
  ];
  await atomicBatch(env.DB, statements);
  return { pairingId, status: "approved", deviceId: candidateDeviceId, workspaceId: row.workspaceId };
}

export async function listDevices(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const access = await authenticateAccess(request, env, now);
  const devices = await loadUserDevices(env, access.userId);
  return { devices };
}

interface DeviceRevocationWorkspaceBinding {
  workspaceId: string;
  revokedAtKeyEpoch: number;
  directoryEpochAfterRevocation: number;
  currentActiveKeyEpoch: number;
  currentRotationRequired: boolean;
  coveringRotationId: string | null;
  coveringProposerDeviceId: string | null;
}

interface DeviceRevocationReceipt {
  revocationId: string;
  actorDeviceId: string;
  revokedDeviceId: string;
  committedAt: number;
  workspaceBindings: DeviceRevocationWorkspaceBinding[];
}

async function loadDeviceRevocationReceipt(
  env: Env,
  revocationId: string,
): Promise<DeviceRevocationReceipt | null> {
  const revocation = await first<{
    revocationId: string;
    actorDeviceId: string;
    revokedDeviceId: string;
    committedAt: number;
  }>(env.DB, `
    SELECT revocation_id AS revocationId, actor_device_id AS actorDeviceId,
           target_device_id AS revokedDeviceId, created_at AS committedAt
    FROM device_revocations WHERE revocation_id = ?1
  `, revocationId);
  if (!revocation) return null;
  const bindings = await all<{
    workspaceId: string;
    revokedAtKeyEpoch: number;
    directoryEpochAfterRevocation: number;
    currentActiveKeyEpoch: number;
    currentRotationRequired: number;
    coveringRotationId: string | null;
    coveringProposerDeviceId: string | null;
  }>(env.DB, `
    SELECT binding.workspace_id AS workspaceId,
           binding.revoked_at_key_epoch AS revokedAtKeyEpoch,
           binding.directory_epoch_after_revocation AS directoryEpochAfterRevocation,
           workspace.active_key_epoch AS currentActiveKeyEpoch,
           workspace.rotation_required AS currentRotationRequired,
           covering.rotation_id AS coveringRotationId,
           covering.proposer_device_id AS coveringProposerDeviceId
    FROM device_revocation_workspaces binding
    JOIN workspaces workspace ON workspace.workspace_id = binding.workspace_id
    LEFT JOIN key_rotations covering ON covering.rotation_id = (
      SELECT candidate.rotation_id FROM key_rotations candidate
      WHERE candidate.workspace_id = binding.workspace_id
        AND candidate.status = 'committed'
        AND candidate.from_epoch = binding.revoked_at_key_epoch
        AND candidate.to_epoch > binding.revoked_at_key_epoch
      ORDER BY candidate.to_epoch, candidate.committed_at, candidate.rotation_id LIMIT 1
    )
    WHERE binding.revocation_id = ?1
    ORDER BY binding.workspace_id
  `, revocationId);
  if (!bindings.length) throw new ApiError(503, "device_revocation_receipt_incomplete");
  return {
    ...revocation,
    workspaceBindings: bindings.map((binding) => ({
      ...binding,
      currentRotationRequired: binding.currentRotationRequired === 1,
    })),
  };
}

function requireExactDeviceRevocation(
  receipt: DeviceRevocationReceipt,
  actorDeviceId: string,
  targetDeviceId: string,
): DeviceRevocationReceipt {
  if (receipt.actorDeviceId !== actorDeviceId || receipt.revokedDeviceId !== targetDeviceId) {
    throw new ApiError(409, "device_revocation_id_conflict");
  }
  return receipt;
}

export async function revokeDevice(
  request: Request,
  env: Env,
  targetDeviceId: string,
  now = Date.now(),
): Promise<{ receipt: DeviceRevocationReceipt; newlyCommitted: boolean }> {
  const { raw, value } = await readJson(request, 8 * 1024);
  const access = await authenticateAccess(request, env, now);
  await requireControlSignature(request, raw, access, env, now);
  const target = requireUuid(targetDeviceId, "device_id");
  const revocationId = requireUuid(value.revocationId, "revocation_id");
  const existing = await loadDeviceRevocationReceipt(env, revocationId);
  if (existing) {
    return { receipt: requireExactDeviceRevocation(existing, access.deviceId, target), newlyCommitted: false };
  }
  const workspaces = await all<{ workspaceId: string }>(env.DB, `
    SELECT m.workspace_id AS workspaceId FROM devices d
    JOIN memberships m ON m.user_id = d.user_id AND m.status = 'active'
    WHERE d.device_id = ?1 AND d.user_id = ?2 AND d.status = 'active'
  `, target, access.userId);
  if (!workspaces.length) throw new ApiError(404, "device_not_found");
  try {
    await atomicBatch(env.DB, [
      env.DB.prepare(`INSERT INTO device_revocations(
        revocation_id, actor_device_id, target_device_id, created_at
      ) VALUES (?1, ?2, ?3, ?4)`).bind(revocationId, access.deviceId, target, now),
    ]);
  } catch (error) {
    // A concurrent retry may have committed after the initial lookup. Only the exact immutable
    // actor/target binding is idempotent; a reused id can never authorize a different revoke.
    const raced = await loadDeviceRevocationReceipt(env, revocationId);
    if (raced) {
      return { receipt: requireExactDeviceRevocation(raced, access.deviceId, target), newlyCommitted: false };
    }
    if (error instanceof ApiError) throw error;
    throw new ApiError(409, "device_revoke_failed");
  }
  const receipt = await loadDeviceRevocationReceipt(env, revocationId);
  if (!receipt) throw new ApiError(503, "device_revocation_receipt_missing");
  return { receipt: requireExactDeviceRevocation(receipt, access.deviceId, target), newlyCommitted: true };
}

export async function getDeviceRevocationReceipt(
  request: Request,
  env: Env,
  revocationIdValue: string,
  now = Date.now(),
): Promise<DeviceRevocationReceipt> {
  const access = await authenticateAccess(request, env, now);
  const revocationId = requireUuid(revocationIdValue, "revocation_id");
  const receipt = await loadDeviceRevocationReceipt(env, revocationId);
  // Do not reveal whether another device or tenant owns a guessed public operation id.
  if (!receipt || receipt.actorDeviceId !== access.deviceId) {
    throw new ApiError(404, "device_revocation_not_found");
  }
  return receipt;
}

export async function createRecoveryChallenge(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, 8 * 1024);
  const userId = requireUuid(value.userId, "user_id");
  const credential = await first<{ present: number }>(env.DB, `
    SELECT 1 AS present FROM recovery_credentials r JOIN users u ON u.user_id = r.user_id
    WHERE r.user_id = ?1 AND u.status = 'active'
  `, userId);
  if (!credential) throw new ApiError(404, "recovery_unavailable");
  const challengeId = randomUuid();
  const challenge = randomToken();
  const challengeHash = await hashSecret(challenge, env.TOKEN_PEPPER, "recovery-challenge");
  const expiresAt = now + FIVE_MINUTES;
  await run(env.DB, `INSERT INTO recovery_challenges(
    challenge_id, user_id, challenge_hash, expires_at, created_at
  ) VALUES (?1, ?2, ?3, ?4, ?5)`, challengeId, userId, challengeHash, expiresAt, now);
  const keyringRows = await all<{
    workspaceId: string;
    activeKeyEpoch: number;
    keyEpoch: number;
    keyCommitment: string | null;
    recoveryWrappedKey: string | null;
  }>(env.DB, `
    WITH user_workspaces AS (
      SELECT w.workspace_id AS workspace_id, w.active_key_epoch AS active_key_epoch
      FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
      WHERE m.user_id = ?1 AND m.status = 'active' AND w.status = 'active'
    ), required_epochs AS (
      SELECT workspace_id, active_key_epoch AS key_epoch FROM user_workspaces
      UNION
      SELECT c.workspace_id, c.key_epoch FROM checkpoints c
      JOIN user_workspaces uw ON uw.workspace_id = c.workspace_id
      WHERE c.status = 'stable'
      UNION
      SELECT e.workspace_id, e.key_epoch FROM events e
      JOIN user_workspaces uw ON uw.workspace_id = e.workspace_id
    )
    SELECT required.workspace_id AS workspaceId,
           uw.active_key_epoch AS activeKeyEpoch,
           required.key_epoch AS keyEpoch,
           recovery.key_commitment AS keyCommitment,
           recovery.wrapped_key AS recoveryWrappedKey
    FROM required_epochs required
    JOIN user_workspaces uw ON uw.workspace_id = required.workspace_id
    LEFT JOIN workspace_recovery_keys recovery
      ON recovery.workspace_id = required.workspace_id
      AND recovery.key_epoch = required.key_epoch
      AND recovery.rotation_id = (
        SELECT latest.rotation_id FROM workspace_recovery_keys latest
        WHERE latest.workspace_id = required.workspace_id
          AND latest.key_epoch = required.key_epoch
        ORDER BY latest.created_at DESC, latest.rotation_id DESC LIMIT 1
      )
    ORDER BY required.workspace_id, required.key_epoch
  `, userId);
  if (!keyringRows.length || keyringRows.some((row) => !row.keyCommitment || !row.recoveryWrappedKey)) {
    throw new ApiError(409, "recovery_keyring_incomplete");
  }
  const byWorkspace = new Map<string, typeof keyringRows>();
  for (const row of keyringRows) {
    const rows = byWorkspace.get(row.workspaceId) ?? [];
    rows.push(row);
    byWorkspace.set(row.workspaceId, rows);
  }
  const workspaces = [...byWorkspace.entries()].map(([workspaceId, rows]) => {
    const activeKeyEpoch = rows[0].activeKeyEpoch;
    if (rows.some((row) => row.activeKeyEpoch !== activeKeyEpoch || row.keyEpoch > activeKeyEpoch)) {
      throw new ApiError(409, "recovery_keyring_incomplete");
    }
    const active = rows.find((row) => row.keyEpoch === activeKeyEpoch);
    if (!active?.keyCommitment || !active.recoveryWrappedKey) {
      throw new ApiError(409, "recovery_keyring_incomplete");
    }
    return {
      workspaceId,
      keyEpoch: activeKeyEpoch,
      keyCommitment: active.keyCommitment,
      recoveryWrappedKey: active.recoveryWrappedKey,
      retainedKeyEnvelopes: rows
        .filter((row) => row.keyEpoch !== activeKeyEpoch)
        .map((row) => ({
          keyEpoch: row.keyEpoch,
          keyCommitment: row.keyCommitment,
          recoveryWrappedKey: row.recoveryWrappedKey,
        })),
    };
  });
  return { challengeId, challenge, expiresAt, workspaces };
}

interface RecoveryEnvelope {
  workspaceId: string;
  keyEpoch: number;
  keyCommitment: string;
  deviceWrappedKey: string;
  deviceEnvelopeSignature: string;
  recoveryKeyEnvelopes: Array<{
    keyEpoch: number;
    keyCommitment: string;
    recoveryWrappedKey: string;
  }>;
}

function parseRecoveryEnvelopes(value: unknown): RecoveryEnvelope[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 25) {
    throw new ApiError(400, "invalid_workspace_envelopes");
  }
  return value.map((entry) => {
    const object = requireObject(entry, "workspace_envelope");
    const recoveryKeyEnvelopesValue = object.recoveryKeyEnvelopes;
    if (!Array.isArray(recoveryKeyEnvelopesValue) || recoveryKeyEnvelopesValue.length === 0 ||
        recoveryKeyEnvelopesValue.length > 100) {
      throw new ApiError(400, "invalid_recovery_key_envelopes");
    }
    const recoveryKeyEnvelopes = recoveryKeyEnvelopesValue.map((candidate) => {
      const recovery = requireObject(candidate, "recovery_key_envelope");
      return {
        keyEpoch: requireInteger(recovery, "keyEpoch", 1, 1_000_000),
        keyCommitment: requireString(recovery, "keyCommitment", 43, 128),
        recoveryWrappedKey: requireString(recovery, "recoveryWrappedKey", 32, 16 * 1024),
      };
    });
    if (new Set(recoveryKeyEnvelopes.map((entry) => entry.keyEpoch)).size !== recoveryKeyEnvelopes.length) {
      throw new ApiError(400, "duplicate_recovery_key_epoch");
    }
    return {
      workspaceId: requireUuid(object.workspaceId, "workspace_id"),
      keyEpoch: requireInteger(object, "keyEpoch", 1, 1_000_000),
      keyCommitment: requireString(object, "keyCommitment", 43, 128),
      deviceWrappedKey: requireString(object, "deviceWrappedKey", 32, 16 * 1024),
      deviceEnvelopeSignature: requireString(object, "deviceEnvelopeSignature", 80, 256),
      recoveryKeyEnvelopes,
    };
  });
}

export async function claimRecovery(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { value } = await readJson(request, JSON_LIMIT);
  const manifest = requireObject(value.manifest, "manifest");
  const instanceId = requireUuid(manifest.instanceId, "instance_id");
  if (instanceId !== requireUuid(env.INSTANCE_ID, "instance_id")) {
    throw new ApiError(400, "recovery_instance_mismatch");
  }
  const userId = requireUuid(manifest.userId, "user_id");
  const challengeId = requireUuid(manifest.challengeId, "challenge_id");
  const challenge = requireString(manifest, "challenge", 32, 256);
  const device = parseDevice(manifest.device);
  const previousRecoverySigningPublicKey = requirePublicKey(
    manifest.previousRecoverySigningPublicKey,
    "previous_recovery_signing_public_key",
  );
  const newRecoverySigningPublicKey = requirePublicKey(
    manifest.newRecoverySigningPublicKey,
    "new_recovery_signing_public_key",
  );
  const newRecoveryWrappingPublicKey = requirePublicKey(
    manifest.newRecoveryWrappingPublicKey,
    "new_recovery_wrapping_public_key",
  );
  const replacementRecoveryTrustSignature = requireString(
    manifest,
    "replacementRecoveryTrustSignature",
    80,
    256,
  );
  if (newRecoverySigningPublicKey === previousRecoverySigningPublicKey ||
      newRecoverySigningPublicKey === device.signingPublicKey) {
    throw new ApiError(400, "recovery_signing_key_not_rotated");
  }
  const envelopes = parseRecoveryEnvelopes(manifest.workspaceEnvelopes);
  const signature = requireString(value, "signature", 80, 256);
  const row = await first<{
    challengeHash: string;
    signingPublicKey: string;
  }>(env.DB, `
    SELECT c.challenge_hash AS challengeHash, r.signing_public_key AS signingPublicKey
    FROM recovery_challenges c JOIN recovery_credentials r ON r.user_id = c.user_id
    WHERE c.challenge_id = ?1 AND c.user_id = ?2 AND c.consumed_at IS NULL AND c.expires_at > ?3
  `, challengeId, userId, now);
  if (!row) throw new ApiError(410, "recovery_challenge_invalid");
  const challengeHash = await hashSecret(challenge, env.TOKEN_PEPPER, "recovery-challenge");
  const deviceTokenHash = await publicTokenCommitment(device.deviceToken, "device_token");
  const publicChallengeCommitment = await publicTokenCommitment(challenge, "recovery_challenge");
  const recoveryLineageManifest = canonicalJson({
    instanceId,
    userId,
    challengeId,
    deviceId: device.deviceId,
    deviceSigningPublicKey: device.signingPublicKey,
    deviceWrappingPublicKey: device.wrappingPublicKey,
    previousRecoverySigningPublicKey,
    newRecoverySigningPublicKey,
    newRecoveryWrappingPublicKey,
  });
  const attestedEnvelopes = await Promise.all([...envelopes]
    .sort((left, right) => left.workspaceId.localeCompare(right.workspaceId))
    .map(async (entry) => ({
      workspaceId: entry.workspaceId,
      keyEpoch: entry.keyEpoch,
      keyCommitment: entry.keyCommitment,
      deviceWrappedKeyHash: await sha256Base64Url(utf8Bytes(entry.deviceWrappedKey)),
      deviceEnvelopeSignature: entry.deviceEnvelopeSignature,
      recoveryKeyEnvelopes: await Promise.all([...entry.recoveryKeyEnvelopes]
        .sort((left, right) => left.keyEpoch - right.keyEpoch)
        .map(async (recovery) => ({
          keyEpoch: recovery.keyEpoch,
          keyCommitment: recovery.keyCommitment,
          recoveryWrappedKeyHash: await sha256Base64Url(utf8Bytes(recovery.recoveryWrappedKey)),
        }))),
    })));
  const attestationManifest = canonicalJson({
    instanceId,
    userId,
    challengeId,
    challengeCommitment: publicChallengeCommitment,
    device: {
      deviceId: device.deviceId,
      displayName: device.displayName,
      platform: device.platform,
      signingPublicKey: device.signingPublicKey,
      wrappingPublicKey: device.wrappingPublicKey,
      deviceTokenHash,
    },
    previousRecoverySigningPublicKey,
    newRecoverySigningPublicKey,
    newRecoveryWrappingPublicKey,
    replacementRecoveryTrustSignature,
    workspaceEnvelopes: attestedEnvelopes,
  });
  if (previousRecoverySigningPublicKey !== row.signingPublicKey) {
    throw new ApiError(401, "recovery_predecessor_mismatch");
  }
  const [claimSignatureValid, lineageSignatureValid] = await Promise.all([
    verifyEd25519(
      row.signingPublicKey,
      signature,
      domainSeparatedMessage("recovery-claim", utf8Bytes(attestationManifest)),
    ),
    verifyEd25519(
      newRecoverySigningPublicKey,
      replacementRecoveryTrustSignature,
      domainSeparatedMessage("recovery-lineage", utf8Bytes(recoveryLineageManifest)),
    ),
  ]);
  if (challengeHash !== row.challengeHash || !claimSignatureValid) {
    throw new ApiError(401, "recovery_signature_invalid");
  }
  if (!lineageSignatureValid) throw new ApiError(401, "recovery_lineage_signature_invalid");

  const authoritative = await all<{
    workspaceId: string;
    activeKeyEpoch: number;
    keyEpoch: number;
    keyCommitment: string;
    membershipAuthEpoch: number;
  }>(env.DB, `
    WITH user_workspaces AS (
      SELECT w.workspace_id AS workspace_id, w.active_key_epoch AS active_key_epoch,
             m.auth_epoch AS membership_auth_epoch
      FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
      WHERE m.user_id = ?1 AND m.status = 'active' AND w.status = 'active'
    ), required_epochs AS (
      SELECT workspace_id, active_key_epoch AS key_epoch FROM user_workspaces
      UNION
      SELECT c.workspace_id, c.key_epoch FROM checkpoints c
      JOIN user_workspaces uw ON uw.workspace_id = c.workspace_id
      WHERE c.status = 'stable'
      UNION
      SELECT e.workspace_id, e.key_epoch FROM events e
      JOIN user_workspaces uw ON uw.workspace_id = e.workspace_id
    )
    SELECT required.workspace_id AS workspaceId,
           uw.active_key_epoch AS activeKeyEpoch,
           required.key_epoch AS keyEpoch,
           rk.key_commitment AS keyCommitment,
           uw.membership_auth_epoch AS membershipAuthEpoch
    FROM required_epochs required
    JOIN user_workspaces uw ON uw.workspace_id = required.workspace_id
    JOIN workspace_recovery_keys rk ON rk.workspace_id = required.workspace_id
      AND rk.key_epoch = required.key_epoch
      AND rk.rotation_id = (
        SELECT latest.rotation_id FROM workspace_recovery_keys latest
        WHERE latest.workspace_id = required.workspace_id AND latest.key_epoch = required.key_epoch
        ORDER BY latest.created_at DESC, latest.rotation_id DESC LIMIT 1
      )
    ORDER BY required.workspace_id, required.key_epoch
  `, userId);
  const submitted = [...envelopes].sort((a, b) => a.workspaceId.localeCompare(b.workspaceId));
  const authoritativeByWorkspace = new Map<string, typeof authoritative>();
  for (const entry of authoritative) {
    const rows = authoritativeByWorkspace.get(entry.workspaceId) ?? [];
    rows.push(entry);
    authoritativeByWorkspace.set(entry.workspaceId, rows);
  }
  if (new Set(submitted.map((entry) => entry.workspaceId)).size !== submitted.length ||
      authoritativeByWorkspace.size !== submitted.length || submitted.some((entry) => {
        const required = authoritativeByWorkspace.get(entry.workspaceId);
        if (!required?.length) return true;
        const active = required.find((candidate) => candidate.keyEpoch === candidate.activeKeyEpoch);
        if (!active || entry.keyEpoch !== active.activeKeyEpoch || entry.keyCommitment !== active.keyCommitment) return true;
        const replacement = [...entry.recoveryKeyEnvelopes].sort((a, b) => a.keyEpoch - b.keyEpoch);
        return required.length !== replacement.length || required.some((candidate, index) =>
          candidate.keyEpoch !== replacement[index]?.keyEpoch ||
          candidate.keyCommitment !== replacement[index]?.keyCommitment);
      })) {
    throw new ApiError(409, "recovery_keyring_incomplete");
  }
  const activeAuthoritative = [...authoritativeByWorkspace.values()].map((required) =>
    required.find((entry) => entry.keyEpoch === entry.activeKeyEpoch)!
  ).sort((a, b) => a.workspaceId.localeCompare(b.workspaceId));
  const claimId = randomUuid();
  const statements = [
    env.DB.prepare(`INSERT INTO devices(
      device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
      token_hash, status, auth_epoch, created_at, last_seen_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'active', 1, ?8, ?8)`).bind(
      device.deviceId, userId, device.displayName, device.platform, device.signingPublicKey,
      device.wrappingPublicKey, deviceTokenHash, now,
    ),
    ...envelopes.map((entry) => {
      const rotationId = `recovery:${claimId}:${entry.workspaceId}`;
      return env.DB.prepare(`INSERT INTO workspace_device_keys(
          workspace_id, key_epoch, device_id, rotation_id, key_commitment,
          wrapped_key, wrapped_by_device_id, signature, created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?3, ?7, ?8)`).bind(
        entry.workspaceId, entry.keyEpoch, device.deviceId, rotationId, entry.keyCommitment,
        entry.deviceWrappedKey, entry.deviceEnvelopeSignature, now,
      );
    }),
    ...envelopes.flatMap((entry) => entry.recoveryKeyEnvelopes.map((recovery) =>
      env.DB.prepare(`INSERT INTO recovery_claim_key_envelopes(
        claim_id, workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
      ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)`).bind(
        claimId, entry.workspaceId, recovery.keyEpoch,
        `recovery:${claimId}:${entry.workspaceId}:${recovery.keyEpoch}`,
        recovery.keyCommitment, recovery.recoveryWrappedKey, now,
      )
    )),
    env.DB.prepare(`INSERT INTO device_key_attestations(
      device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
      signature_domain, manifest_json, signature, created_at
    ) VALUES (?1, ?2, 'recovery', NULL, ?3, 'recovery-claim', ?4, ?5, ?6)`).bind(
      device.deviceId, submitted[0].workspaceId, row.signingPublicKey,
      attestationManifest, signature, now,
    ),
    env.DB.prepare(`INSERT INTO recovery_claims(
      claim_id, challenge_id, user_id, new_device_id, workspace_envelope_count, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)`).bind(
      claimId, challengeId, userId, device.deviceId, envelopes.length, now,
    ),
    env.DB.prepare(`UPDATE recovery_credentials SET
      signing_public_key = ?2, wrapping_public_key = ?3,
      auth_epoch = auth_epoch + 1, rotated_at = ?4
      WHERE user_id = ?1`).bind(
      userId, newRecoverySigningPublicKey, newRecoveryWrappingPublicKey, now,
    ),
    ...activeAuthoritative.map((entry) => env.DB.prepare(`INSERT INTO recovery_claim_workspaces(
      claim_id, workspace_id, device_auth_epoch, membership_auth_epoch, active_key_epoch
    ) VALUES (?1, ?2, 1, ?3, ?4)`).bind(
      claimId, entry.workspaceId, entry.membershipAuthEpoch, entry.activeKeyEpoch,
    )),
  ];
  await atomicBatch(env.DB, statements);
  return {
    claimId,
    userId,
    deviceId: device.deviceId,
    newRecoverySigningPublicKey,
    newRecoveryWrappingPublicKey,
    rotationRequiredWorkspaces: submitted.map((entry) => entry.workspaceId),
    workspaceBindings: activeAuthoritative.map((entry) => ({
      workspaceId: entry.workspaceId,
      deviceAuthEpoch: 1,
      membershipAuthEpoch: entry.membershipAuthEpoch,
      activeKeyEpoch: entry.activeKeyEpoch,
    })),
  };
}

/**
 * Replays the exact durable receipt after the recovery POST committed but its response was lost.
 * Authentication uses the newly registered device, so an uncommitted claim cannot be mistaken for
 * success and the old Recovery Kit is never used to overwrite staged replacement credentials.
 */
export async function reconcileRecoveryClaim(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const access = await authenticateAccess(request, env, now);
  const claim = await first<{
    claimId: string;
    userId: string;
    deviceId: string;
    workspaceEnvelopeCount: number;
    newRecoverySigningPublicKey: string;
    newRecoveryWrappingPublicKey: string;
  }>(env.DB, `
    SELECT c.claim_id AS claimId, c.user_id AS userId, c.new_device_id AS deviceId,
           c.workspace_envelope_count AS workspaceEnvelopeCount,
           r.signing_public_key AS newRecoverySigningPublicKey,
           r.wrapping_public_key AS newRecoveryWrappingPublicKey
    FROM recovery_claims c
    JOIN recovery_credentials r ON r.user_id = c.user_id
    WHERE c.new_device_id = ?1 AND c.user_id = ?2
  `, access.deviceId, access.userId);
  if (!claim) throw new ApiError(404, "recovery_claim_not_found");

  const workspaceBindings = await all<{
    workspaceId: string;
    deviceAuthEpoch: number;
    membershipAuthEpoch: number;
    activeKeyEpoch: number;
  }>(env.DB, `
    SELECT workspace_id AS workspaceId, device_auth_epoch AS deviceAuthEpoch,
           membership_auth_epoch AS membershipAuthEpoch, active_key_epoch AS activeKeyEpoch
    FROM recovery_claim_workspaces
    WHERE claim_id = ?1
    ORDER BY workspace_id
  `, claim.claimId);
  if (workspaceBindings.length !== claim.workspaceEnvelopeCount) {
    throw new ApiError(409, "recovery_claim_receipt_incomplete");
  }
  return {
    claimId: claim.claimId,
    userId: claim.userId,
    deviceId: claim.deviceId,
    newRecoverySigningPublicKey: claim.newRecoverySigningPublicKey,
    newRecoveryWrappingPublicKey: claim.newRecoveryWrappingPublicKey,
    rotationRequiredWorkspaces: workspaceBindings.map((entry) => entry.workspaceId),
    workspaceBindings,
  };
}
