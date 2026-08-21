import {
  ApiError,
  allowedOrigin,
  corsHeaders,
  emptyResponse,
  errorResponse,
  jsonResponse,
  securityHeaders,
} from "./http.ts";
import type { CapabilityPrincipal, Env, ExecutionContext } from "./runtime.ts";
import { hashSecret } from "./crypto/base.ts";
import { LIMITS, requireUuid } from "./protocol.ts";
import { authenticateCapability } from "./auth/service.ts";
import { enforceRateWindow } from "./storage/d1.ts";
import {
  approvePairing,
  claimRecovery,
  createAuthChallenge,
  createCapability,
  createInvite,
  createPairing,
  createRecoveryChallenge,
  exchangeAuthToken,
  getPairing,
  getDeviceRevocationReceipt,
  listDevices,
  reconcileRecoveryClaim,
  redeemInvite,
  revokeDevice,
  setupClaim,
  submitPairingCandidate,
} from "./api/identity.ts";
import { appendEvent, catchUpEvents, lookupEventReceipt, workspaceBootstrap } from "./api/events.ts";
import {
  acknowledgeCheckpoint,
  commitCheckpoint,
  createCheckpointLease,
  downloadCheckpoint,
  uploadCheckpoint,
} from "./api/checkpoints.ts";
import {
  acknowledgeRotation,
  commitRotation,
  createRotationLease,
} from "./api/rotations.ts";
import { WorkspaceHub } from "./durable-objects/WorkspaceHub.ts";
import { getAdminUsage, updateAdminQuota } from "./api/admin.ts";
import { claimEmergencyWorkspaceHandoff } from "./api/emergency-reset.ts";
import {
  acknowledgeBlobTombstone,
  commitBlobUpload,
  createBlobTombstone,
  createBlobUploadSession,
  downloadBlobChunk,
  downloadBlobManifest,
  garbageCollectBlob,
  getBlobEnvelope,
  getBlobUploadSession,
  rewrapBlobEnvelope,
  reviveBlobReference,
  uploadBlobChunk,
} from "./api/blobs.ts";
import { qrSvg } from "./qr.ts";
import { versionCapabilities } from "./versions.ts";

export { WorkspaceHub };

function realtime(env: Env): boolean {
  return (env.REALTIME_ENABLED ?? "true").toLowerCase() === "true";
}

function validateEnvironment(env: Env): void {
  requireUuid(env.INSTANCE_ID, "instance_id");
  if (!env.TOKEN_PEPPER || env.TOKEN_PEPPER.length < 32) throw new ApiError(503, "token_pepper_not_configured");
  if (!env.BOOTSTRAP_SECRET || env.BOOTSTRAP_SECRET.length < 32) throw new ApiError(503, "bootstrap_secret_not_configured");
  if (!env.RATE_LIMIT_SALT || env.RATE_LIMIT_SALT.length < 16) throw new ApiError(503, "rate_limit_salt_not_configured");
  versionCapabilities(env);
}

function internalHeaders(principal: CapabilityPrincipal, original?: Headers): Headers {
  const headers = new Headers(original);
  headers.set("X-Shinsou-Internal-Capability", principal.capabilityId);
  headers.set("X-Shinsou-Internal-Workspace", principal.workspaceId);
  headers.set("X-Shinsou-Internal-Device", principal.deviceId);
  headers.set("X-Shinsou-Internal-User", principal.userId);
  headers.set("X-Shinsou-Internal-Role", principal.role);
  headers.set("X-Shinsou-Internal-Device-Epoch", String(principal.deviceAuthEpoch));
  headers.set("X-Shinsou-Internal-Membership-Epoch", String(principal.membershipAuthEpoch));
  headers.set("X-Shinsou-Internal-Key-Epoch", String(principal.keyEpoch));
  headers.set("X-Shinsou-Internal-Signing-Key", principal.signingPublicKey);
  headers.set("X-Shinsou-Internal-Expires", String(principal.expiresAt));
  return headers;
}

function hub(env: Env, workspaceId: string) {
  return env.WORKSPACE_HUB.get(env.WORKSPACE_HUB.idFromName(workspaceId));
}

async function notifyInvalidation(
  env: Env,
  workspaceId: string,
  targetDeviceId: string | null,
): Promise<void> {
  if (!realtime(env)) return;
  await hub(env, workspaceId).fetch("https://hub.internal/invalidate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ targetDeviceId }),
  });
}

async function notifyCheckpoint(
  env: Env,
  workspaceId: string,
  checkpoint: Record<string, unknown>,
): Promise<void> {
  if (!realtime(env)) return;
  await hub(env, workspaceId).fetch("https://hub.internal/checkpoint", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(checkpoint),
  });
}

async function abuseGate(request: Request, env: Env, scope: string, limit: number): Promise<void> {
  const address = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const hash = await hashSecret(address, env.RATE_LIMIT_SALT, `edge-rate:${scope}`);
  await enforceRateWindow(env.DB, "edge_rate_windows", "scope_hash", `${scope}:${hash}`, Date.now(), limit);
}

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function setupPage(request: Request, env: Env): Response {
  const headers = securityHeaders();
  headers.set("Content-Type", "text/html; charset=utf-8");
  const endpoint = new URL(request.url).origin;
  const instanceId = env.INSTANCE_ID.toLowerCase();
  const setupLink = `shinsou://sync/setup?endpoint=${encodeURIComponent(endpoint)}&instance=${encodeURIComponent(instanceId)}`;
  const escapedSetupLink = escapeHtml(setupLink);
  return new Response(`<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<meta name="shinsou-sync-endpoint" content="${escapeHtml(endpoint)}">
<meta name="shinsou-sync-instance" content="${escapeHtml(instanceId)}">
<meta name="shinsou-sync-setup-link" content="${escapedSetupLink}">
<title>Shinsou X Sync Setup</title></head><body>
<main><h1>Shinsou X Sync</h1><p>部署已啟動。請回到 App，以部署時產生的 bootstrap secret 完成第一臺裝置 claim。</p>
<p>Endpoint: <code>${escapeHtml(endpoint)}</code></p><p>Instance: <code>${escapeHtml(instanceId)}</code></p>
<figure>${qrSvg(setupLink)}<figcaption>使用 Shinsou X 掃描此 QR</figcaption></figure>
<p><a id="open-shinsou" href="${escapedSetupLink}">在 Shinsou X 開啟</a></p></main>
</body></html>`, { status: 200, headers });
}

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  validateEnvironment(env);
  allowedOrigin(request, env);
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method.toUpperCase();

  if (method === "OPTIONS") return emptyResponse(204, corsHeaders(request, env));
  if (method === "GET" && path === "/setup") return setupPage(request, env);
  if (method === "GET" && path === "/v1/capabilities") {
    const versions = versionCapabilities(env);
    const bodyPlaneConfigured = Boolean(env.BLOBS) &&
      versions.protocolVersion >= 2 && versions.schemaVersion >= 2;
    const blobUploadTtlSeconds = Number(env.BLOB_UPLOAD_SESSION_TTL_SECONDS ?? "3600");
    const blobGcSafetySeconds = Number(env.BLOB_GC_SAFETY_SECONDS ?? "86400");
    if (!Number.isSafeInteger(blobUploadTtlSeconds) || blobUploadTtlSeconds <= 0 ||
        !Number.isSafeInteger(blobGcSafetySeconds) || blobGcSafetySeconds <= 0) {
      throw new ApiError(503, "blob_body_configuration_invalid");
    }
    return jsonResponse({
      instanceId: env.INSTANCE_ID.toLowerCase(),
      ...versions,
      profile: realtime(env) ? "realtime" : "lite",
      websocket: realtime(env),
      checkpoint: { envelopeVersion: 1, maximumBytes: LIMITS.checkpointCiphertext, retainedStable: 3 },
      event: { envelopeVersion: 1, maximumBytes: LIMITS.eventCiphertext },
      blobBody: {
        enabled: bodyPlaneConfigured,
        protocolVersion: 2,
        schemaVersion: 2,
        maxBlobBytes: LIMITS.blobCiphertext,
        maxChunkBytes: LIMITS.blobChunkPlaintextMaximum,
        maxChunks: LIMITS.blobChunks,
        reservationTtlMillis: blobUploadTtlSeconds * 1000,
        gcSafetyWindowMillis: blobGcSafetySeconds * 1000,
      },
      auth: { accessTokenSeconds: Number(env.ACCESS_TOKEN_TTL_SECONDS ?? "600"), capabilitySeconds: Math.min(300, Number(env.CAPABILITY_TTL_SECONDS ?? "300")) },
    });
  }
  if (method === "POST" && path === "/v1/setup/claim") {
    await abuseGate(request, env, "setup", 10);
    return jsonResponse(await setupClaim(request, env), 201);
  }
  if (method === "POST" && path === "/v1/emergency-reset/handoff") {
    await abuseGate(request, env, "emergency-handoff", 10);
    const result = await claimEmergencyWorkspaceHandoff(request, env) as {
      workspaceId: string;
    };
    ctx.waitUntil(notifyInvalidation(env, result.workspaceId, null));
    return jsonResponse(result, 201);
  }
  if (method === "POST" && path === "/v1/admin/invites") {
    await abuseGate(request, env, "invite-admin", 30);
    return jsonResponse(await createInvite(request, env), 201);
  }
  if (method === "GET" && path === "/v1/admin/usage") {
    return jsonResponse(await getAdminUsage(request, env));
  }
  if (method === "PUT" && path === "/v1/admin/quota") {
    return jsonResponse(await updateAdminQuota(request, env));
  }
  if (method === "POST" && path === "/v1/invites/redeem") {
    await abuseGate(request, env, "invite-redeem", 20);
    return jsonResponse(await redeemInvite(request, env), 201);
  }
  if (method === "POST" && path === "/v1/auth/challenge") {
    await abuseGate(request, env, "auth-challenge", 60);
    return jsonResponse(await createAuthChallenge(request, env), 201);
  }
  if (method === "POST" && path === "/v1/auth/token") {
    await abuseGate(request, env, "auth-token", 60);
    return jsonResponse(await exchangeAuthToken(request, env), 201);
  }
  if (method === "POST" && path === "/v1/auth/capability") {
    return jsonResponse(await createCapability(request, env), 201);
  }
  if (method === "POST" && path === "/v1/pairings") {
    await abuseGate(request, env, "pair-create", 30);
    return jsonResponse(await createPairing(request, env), 201);
  }
  const pairingMatch = /^\/v1\/pairings\/([0-9a-f-]+)(?:\/(candidate|approve))?$/.exec(path);
  if (pairingMatch) {
    const pairingId = requireUuid(pairingMatch[1], "pairing_id");
    if (method === "POST" && pairingMatch[2] === "candidate") {
      await abuseGate(request, env, "pair-candidate", 30);
      return jsonResponse(await submitPairingCandidate(request, env, pairingId), 202);
    }
    if (method === "POST" && pairingMatch[2] === "approve") {
      return jsonResponse(await approvePairing(request, env, pairingId));
    }
    if (method === "GET" && !pairingMatch[2]) return jsonResponse(await getPairing(request, env, pairingId));
  }
  if (method === "GET" && path === "/v1/devices") return jsonResponse(await listDevices(request, env));
  const revocationReceiptMatch = /^\/v1\/device-revocations\/([0-9a-f-]+)$/.exec(path);
  if (method === "GET" && revocationReceiptMatch) {
    return jsonResponse(await getDeviceRevocationReceipt(request, env, revocationReceiptMatch[1]));
  }
  const revokeMatch = /^\/v1\/devices\/([0-9a-f-]+)\/revoke$/.exec(path);
  if (method === "POST" && revokeMatch) {
    const result = await revokeDevice(request, env, revokeMatch[1]);
    if (result.newlyCommitted) {
      for (const binding of result.receipt.workspaceBindings) {
        ctx.waitUntil(notifyInvalidation(env, binding.workspaceId, result.receipt.revokedDeviceId));
      }
    }
    return jsonResponse(result.receipt, result.newlyCommitted ? 201 : 200);
  }
  if (method === "POST" && path === "/v1/recovery/challenge") {
    await abuseGate(request, env, "recovery-challenge", 10);
    return jsonResponse(await createRecoveryChallenge(request, env), 201);
  }
  if (method === "POST" && path === "/v1/recovery/claim") {
    await abuseGate(request, env, "recovery-claim", 10);
    const result = await claimRecovery(request, env) as { rotationRequiredWorkspaces: string[]; deviceId: string };
    for (const workspaceId of result.rotationRequiredWorkspaces) {
      ctx.waitUntil(notifyInvalidation(env, workspaceId, null));
    }
    return jsonResponse(result, 201);
  }
  if (method === "GET" && path === "/v1/recovery/claim") {
    return jsonResponse(await reconcileRecoveryClaim(request, env));
  }

  const bodyWorkspaceMatch = /^\/v2\/workspaces\/([0-9a-f-]+)(?:\/(.*))?$/.exec(path);
  if (bodyWorkspaceMatch) {
    const workspaceId = requireUuid(bodyWorkspaceMatch[1], "workspace_id");
    const suffix = bodyWorkspaceMatch[2] ?? "";
    const principal = await authenticateCapability(request, env, workspaceId);
    if (method === "POST" && suffix === "blob-upload-sessions") {
      return jsonResponse(await createBlobUploadSession(request, env, principal), 201);
    }
    const uploadSessionMatch = /^blob-upload-sessions\/([0-9a-f-]+)(?:\/(commit|chunks\/(\d+)))?$/.exec(suffix);
    if (uploadSessionMatch) {
      const sessionId = requireUuid(uploadSessionMatch[1], "session_id");
      if (method === "GET" && !uploadSessionMatch[2]) {
        return jsonResponse(await getBlobUploadSession(env, principal, sessionId));
      }
      if (method === "POST" && uploadSessionMatch[2] === "commit") {
        return jsonResponse(await commitBlobUpload(request, env, principal, sessionId), 201);
      }
      if (method === "PUT" && uploadSessionMatch[3] !== undefined) {
        return jsonResponse(await uploadBlobChunk(
          request,
          env,
          principal,
          sessionId,
          Number(uploadSessionMatch[3]),
        ), 201);
      }
    }
    const blobMatch = /^blobs\/([0-9a-f-]+)(?:\/(manifest|chunks\/(\d+)|envelopes(?:\/(\d+))?|tombstone(?:\/(?:acks|revival))?))?$/.exec(suffix);
    if (blobMatch) {
      const blobId = requireUuid(blobMatch[1], "blob_id");
      if (method === "GET" && blobMatch[2] === "manifest") {
        return jsonResponse(await downloadBlobManifest(env, principal, blobId));
      }
      if (method === "GET" && blobMatch[3] !== undefined) {
        return downloadBlobChunk(env, principal, blobId, Number(blobMatch[3]));
      }
      if (method === "POST" && blobMatch[2] === "envelopes") {
        return jsonResponse(await rewrapBlobEnvelope(request, env, principal, blobId), 201);
      }
      if (method === "GET" && blobMatch[4] !== undefined) {
        return jsonResponse(await getBlobEnvelope(env, principal, blobId, Number(blobMatch[4])));
      }
      if (method === "POST" && blobMatch[2] === "tombstone") {
        return jsonResponse(await createBlobTombstone(request, env, principal, blobId), 201);
      }
      if (method === "POST" && blobMatch[2] === "tombstone/acks") {
        await acknowledgeBlobTombstone(request, env, principal, blobId);
        return emptyResponse(204);
      }
      if (method === "POST" && blobMatch[2] === "tombstone/revival") {
        return jsonResponse(await reviveBlobReference(request, env, principal, blobId));
      }
    }
    if (method === "POST" && suffix === "blob-gc") {
      return jsonResponse(await garbageCollectBlob(request, env, principal), 201);
    }
  }

  const workspaceMatch = /^\/v1\/workspaces\/([0-9a-f-]+)(?:\/(.*))?$/.exec(path);
  if (workspaceMatch) {
    const workspaceId = requireUuid(workspaceMatch[1], "workspace_id");
    const suffix = workspaceMatch[2] ?? "";
    const principal = await authenticateCapability(request, env, workspaceId);
    if (method === "GET" && suffix === "bootstrap") {
      return jsonResponse(await workspaceBootstrap(env, principal));
    }
    const receiptMatch = /^events\/receipts\/(\d+)$/.exec(suffix);
    if (method === "GET" && receiptMatch) {
      return jsonResponse(await lookupEventReceipt(env, principal, Number(receiptMatch[1])));
    }
    if (suffix === "events") {
      if (method === "GET") return jsonResponse(await catchUpEvents(request, env, principal));
      if (method === "POST") {
        if (!realtime(env)) {
          const receipt = await appendEvent(request, env, principal);
          return jsonResponse(receipt, receipt.duplicate ? 200 : 201);
        }
        const response = await hub(env, workspaceId).fetch("https://hub.internal/append", {
          method: "POST",
          headers: internalHeaders(principal, request.headers),
          body: request.body,
        });
        return response;
      }
    }
    if (method === "GET" && suffix === "stream") {
      if (!realtime(env)) throw new ApiError(404, "realtime_unavailable");
      const cursor = url.searchParams.get("cursor") ?? "0";
      return hub(env, workspaceId).fetch(`https://hub.internal/stream?cursor=${encodeURIComponent(cursor)}`, {
        method: "GET",
        headers: internalHeaders(principal, request.headers),
      });
    }
    if (method === "POST" && suffix === "checkpoint-leases") {
      return jsonResponse(await createCheckpointLease(request, env, principal), 201);
    }
    const checkpointMatch = /^checkpoints\/([0-9a-f-]+)(?:\/(commit|ack))?$/.exec(suffix);
    if (checkpointMatch) {
      const checkpointId = requireUuid(checkpointMatch[1], "checkpoint_id");
      if (method === "PUT" && !checkpointMatch[2]) {
        return jsonResponse(await uploadCheckpoint(request, env, principal, checkpointId), 201);
      }
      if (method === "POST" && checkpointMatch[2] === "commit") {
        const result = await commitCheckpoint(request, env, principal, checkpointId) as Record<string, unknown>;
        ctx.waitUntil(notifyCheckpoint(env, workspaceId, result));
        return jsonResponse(result, 201);
      }
      if (method === "POST" && checkpointMatch[2] === "ack") {
        const result = await acknowledgeCheckpoint(request, env, principal, checkpointId) as {
          status: string;
          throughWorkspaceSeq?: number;
        };
        if (result.status === "stable") {
          ctx.waitUntil(notifyCheckpoint(env, workspaceId, result as Record<string, unknown>));
        }
        return jsonResponse(result);
      }
      if (method === "GET" && !checkpointMatch[2]) {
        return downloadCheckpoint(env, principal, checkpointId);
      }
    }
    if (method === "POST" && suffix === "key-rotations") {
      return jsonResponse(await createRotationLease(request, env, principal), 201);
    }
    const rotationMatch = /^key-rotations\/([0-9a-f-]+)\/(commit|ack)$/.exec(suffix);
    if (method === "POST" && rotationMatch) {
      const rotationId = requireUuid(rotationMatch[1], "rotation_id");
      if (rotationMatch[2] === "commit") {
        const result = await commitRotation(request, env, principal, rotationId);
        ctx.waitUntil(notifyInvalidation(env, workspaceId, null));
        return jsonResponse(result);
      }
      return jsonResponse(await acknowledgeRotation(request, env, principal, rotationId));
    }
  }
  throw new ApiError(404, "not_found");
}

function applyCors(response: Response, request: Request, env: Env): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(corsHeaders(request, env))) headers.set(key, String(value));
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      const response = await route(request, env, ctx);
      if (response.status === 101) return response;
      return applyCors(response, request, env);
    } catch (error) {
      if (!(error instanceof ApiError)) {
        console.error(JSON.stringify({
          level: "error",
          code: "unhandled_request_error",
          method: request.method,
          path: new URL(request.url).pathname,
        }));
      }
      const response = errorResponse(error);
      try {
        return applyCors(response, request, env);
      } catch {
        return response;
      }
    }
  },
};
