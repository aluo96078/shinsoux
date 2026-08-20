import { ApiError, bearerToken, utf8Bytes } from "../http.ts";
import type { AccessPrincipal, CapabilityPrincipal, Env } from "../runtime.ts";
import {
  decodeBase64Url,
  domainSeparatedMessage,
  hashSecret,
  randomToken,
  randomUuid,
  sha256Base64Url,
  verifyEd25519,
} from "../crypto/base.ts";
import { first, returning } from "../storage/d1.ts";
import { CREATE_CAPABILITY_SQL } from "../storage/sql.ts";

const CONTROL_CLOCK_SKEW_MS = 5 * 60_000;

interface AccessRow {
  accessTokenId: string;
  deviceId: string;
  userId: string;
  instanceRole: "admin" | "user";
  deviceAuthEpoch: number;
  signingPublicKey: string;
  expiresAt: number;
}

interface CapabilityRow {
  capabilityId: string;
  workspaceId: string;
  deviceId: string;
  userId: string;
  role: "owner" | "editor" | "reader";
  deviceAuthEpoch: number;
  membershipAuthEpoch: number;
  keyEpoch: number;
  signingPublicKey: string;
  expiresAt: number;
}

export async function authenticateAccess(
  request: Request,
  env: Env,
  now = Date.now(),
): Promise<AccessPrincipal> {
  const token = bearerToken(request);
  const tokenHash = await hashSecret(token, env.TOKEN_PEPPER, "access-token");
  const row = await first<AccessRow>(env.DB, `
    SELECT a.access_token_id AS accessTokenId, d.device_id AS deviceId,
           d.user_id AS userId, u.instance_role AS instanceRole,
           d.auth_epoch AS deviceAuthEpoch, d.signing_public_key AS signingPublicKey,
           a.expires_at AS expiresAt
    FROM access_tokens a
    JOIN devices d ON d.device_id = a.device_id
    JOIN users u ON u.user_id = d.user_id
    WHERE a.token_hash = ?1 AND a.revoked_at IS NULL AND a.expires_at > ?2
      AND a.device_auth_epoch = d.auth_epoch
      AND d.status = 'active' AND u.status = 'active'
  `, tokenHash, now);
  if (!row) throw new ApiError(401, "access_token_invalid");
  return row;
}

export async function authenticateCapability(
  request: Request,
  env: Env,
  expectedWorkspaceId?: string,
  now = Date.now(),
): Promise<CapabilityPrincipal> {
  const token = bearerToken(request);
  const tokenHash = await hashSecret(token, env.TOKEN_PEPPER, "workspace-capability");
  const row = await first<CapabilityRow>(env.DB, `
    SELECT c.capability_id AS capabilityId, c.workspace_id AS workspaceId,
           d.device_id AS deviceId, d.user_id AS userId, m.role AS role,
           d.auth_epoch AS deviceAuthEpoch, m.auth_epoch AS membershipAuthEpoch,
           w.active_key_epoch AS keyEpoch, d.signing_public_key AS signingPublicKey,
           c.expires_at AS expiresAt
    FROM workspace_capabilities c
    JOIN workspaces w ON w.workspace_id = c.workspace_id
    JOIN devices d ON d.device_id = c.device_id
    JOIN users u ON u.user_id = d.user_id
    JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
    WHERE c.token_hash = ?1 AND c.revoked_at IS NULL AND c.expires_at > ?2
      AND c.device_auth_epoch = d.auth_epoch
      AND c.membership_auth_epoch = m.auth_epoch
      AND c.key_epoch = w.active_key_epoch
      AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
      AND w.status = 'active'
  `, tokenHash, now);
  if (!row) throw new ApiError(401, "capability_invalid");
  if (expectedWorkspaceId && row.workspaceId !== expectedWorkspaceId) {
    throw new ApiError(403, "workspace_forbidden");
  }
  return row;
}

export async function requireControlSignature(
  request: Request,
  rawBody: Uint8Array,
  principal: AccessPrincipal,
  env: Env,
  now = Date.now(),
): Promise<void> {
  const timestampValue = request.headers.get("X-Shinsou-Timestamp") ?? "";
  const nonce = request.headers.get("X-Shinsou-Nonce") ?? "";
  const signature = request.headers.get("X-Shinsou-Signature") ?? "";
  if (!/^\d{13}$/.test(timestampValue)) throw new ApiError(401, "control_timestamp_required");
  const timestamp = Number(timestampValue);
  if (Math.abs(now - timestamp) > CONTROL_CLOCK_SKEW_MS) {
    throw new ApiError(401, "control_timestamp_expired");
  }
  const nonceBytes = decodeBase64Url(nonce, "control_nonce");
  if (nonceBytes.byteLength < 16 || nonceBytes.byteLength > 64) {
    throw new ApiError(401, "invalid_control_nonce");
  }
  const bodyHash = await sha256Base64Url(rawBody);
  const path = new URL(request.url).pathname;
  const message = domainSeparatedMessage(
    "control-request",
    utf8Bytes(`${request.method.toUpperCase()}\n${path}\n${timestampValue}\n${nonce}\n${bodyHash}\n${principal.deviceId}`),
  );
  if (!(await verifyEd25519(principal.signingPublicKey, signature, message))) {
    throw new ApiError(401, "control_signature_invalid");
  }
  const nonceHash = await sha256Base64Url(nonceBytes);
  try {
    const result = await env.DB.prepare(`
      INSERT INTO control_request_nonces(device_id, nonce_hash, expires_at, created_at)
      VALUES (?1, ?2, ?3, ?4)
    `).bind(principal.deviceId, nonceHash, now + CONTROL_CLOCK_SKEW_MS, now).run();
    if (!result.success) throw new Error("nonce insert failed");
  } catch {
    throw new ApiError(409, "control_request_replayed");
  }
}

export async function issueAccessToken(
  env: Env,
  device: { deviceId: string; authEpoch: number },
  now = Date.now(),
): Promise<{ accessToken: string; expiresAt: number }> {
  const accessToken = randomToken();
  const accessTokenId = randomUuid();
  const tokenHash = await hashSecret(accessToken, env.TOKEN_PEPPER, "access-token");
  const ttl = Math.min(3600, Math.max(60, Number(env.ACCESS_TOKEN_TTL_SECONDS ?? "600"))) * 1000;
  const expiresAt = now + ttl;
  await env.DB.prepare(`
    INSERT INTO access_tokens(
      access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
  `).bind(accessTokenId, device.deviceId, tokenHash, device.authEpoch, expiresAt, now).run();
  return { accessToken, expiresAt };
}

export async function issueWorkspaceCapability(
  env: Env,
  access: AccessPrincipal,
  workspaceId: string,
  now = Date.now(),
): Promise<{
  capability: string;
  capabilityId: string;
  workspaceId: string;
  deviceId: string;
  deviceAuthEpoch: number;
  membershipAuthEpoch: number;
  keyEpoch: number;
  expiresAt: number;
}> {
  const capability = randomToken();
  const capabilityId = randomUuid();
  const tokenHash = await hashSecret(capability, env.TOKEN_PEPPER, "workspace-capability");
  const ttl = Math.min(300, Math.max(30, Number(env.CAPABILITY_TTL_SECONDS ?? "300"))) * 1000;
  const expiresAt = now + ttl;
  const row = await returning<{
    capability_id?: string;
    capabilityId?: string;
    workspace_id?: string;
    workspaceId?: string;
    device_id?: string;
    deviceId?: string;
    device_auth_epoch?: number;
    deviceAuthEpoch?: number;
    membership_auth_epoch?: number;
    membershipAuthEpoch?: number;
    key_epoch?: number;
    keyEpoch?: number;
    expires_at?: number;
    expiresAt?: number;
  }>(
    env.DB,
    CREATE_CAPABILITY_SQL,
    capabilityId,
    workspaceId,
    access.deviceId,
    tokenHash,
    expiresAt,
    now,
    access.accessTokenId,
  );
  if (!row) throw new ApiError(403, "workspace_forbidden");
  return {
    capability,
    capabilityId,
    workspaceId: String(row.workspaceId ?? row.workspace_id),
    deviceId: String(row.deviceId ?? row.device_id),
    deviceAuthEpoch: Number(row.deviceAuthEpoch ?? row.device_auth_epoch),
    membershipAuthEpoch: Number(row.membershipAuthEpoch ?? row.membership_auth_epoch),
    keyEpoch: Number(row.keyEpoch ?? row.key_epoch),
    expiresAt: Number(row.expiresAt ?? row.expires_at),
  };
}

export function authChallengeMessage(
  challengeId: string,
  challenge: string,
  deviceId: string,
): Uint8Array {
  return domainSeparatedMessage(
    "auth-challenge",
    utf8Bytes(`${challengeId}\n${challenge}\n${deviceId}`),
  );
}
