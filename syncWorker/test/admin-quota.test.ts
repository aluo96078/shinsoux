import test from "node:test";
import assert from "node:assert/strict";
import { getAdminUsage, updateAdminQuota } from "../src/api/admin.ts";
import { createPairing } from "../src/api/identity.ts";
import { atomicBatch } from "../src/storage/d1.ts";
import {
  domainSeparatedMessage,
  encodeBase64Url,
  hashSecret,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import type { Env } from "../src/runtime.ts";
import { database, IDS, seedSecondTenant, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_700_000_000_000;
const PEPPER = "p".repeat(32);
const ADMIN_TOKEN = "admin-access-token-abcdefghijklmnopqrstuvwxyz";
const USER_TOKEN = "user-access-token-abcdefghijklmnopqrstuvwxyz";

async function environment() {
  const db = database();
  seedTenant(db, NOW);
  seedSecondTenant(db, NOW);
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const publicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)));
  db.exec("DROP TRIGGER devices_identity_keys_immutable");
  db.prepare("UPDATE devices SET signing_public_key = ? WHERE device_id = ?").run(publicKey, IDS.deviceA);
  db.prepare(`INSERT INTO access_tokens(
    access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 1, ?, ?)`).run(
    "00000000-0000-4000-8000-000000000020",
    IDS.deviceA,
    await hashSecret(ADMIN_TOKEN, PEPPER, "access-token"),
    NOW + 60_000,
    NOW,
  );
  db.prepare(`INSERT INTO access_tokens(
    access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 1, ?, ?)`).run(
    "00000000-0000-4000-8000-000000000021",
    IDS.deviceB,
    await hashSecret(USER_TOKEN, PEPPER, "access-token"),
    NOW + 60_000,
    NOW,
  );
  db.prepare(`INSERT INTO usage_daily(
    workspace_id, day, events_written, event_bytes_written,
    checkpoints_written, checkpoint_bytes_written
  ) VALUES (?, '2026-08-20', 7, 700, 2, 2000)`).run(IDS.workspaceA);
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: PEPPER,
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
  };
  return { db, env, keys };
}

function accessRequest(token: string): Request {
  return new Request("https://sync.test/v1/admin/usage", {
    headers: { Authorization: `Bearer ${token}` },
  });
}

async function signedQuotaRequest(
  keys: CryptoKeyPair,
  body: Record<string, number>,
  nonceByte: number,
): Promise<Request> {
  return signedControlRequest(keys, "PUT", "/v1/admin/quota", body, nonceByte);
}

async function signedControlRequest(
  keys: CryptoKeyPair,
  method: "POST" | "PUT",
  path: string,
  body: Record<string, unknown>,
  nonceByte: number,
): Promise<Request> {
  const raw = JSON.stringify(body);
  const nonce = encodeBase64Url(new Uint8Array(24).fill(nonceByte));
  const bodyHash = await sha256Base64Url(new TextEncoder().encode(raw));
  const message = domainSeparatedMessage(
    "control-request",
    new TextEncoder().encode(`${method}\n${path}\n${NOW}\n${nonce}\n${bodyHash}\n${IDS.deviceA}`),
  );
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign("Ed25519", keys.privateKey, message)));
  return new Request(`https://sync.test${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${ADMIN_TOKEN}`,
      "Content-Type": "application/json",
      "X-Shinsou-Timestamp": String(NOW),
      "X-Shinsou-Nonce": nonce,
      "X-Shinsou-Signature": signature,
    },
    body: raw,
  });
}

test("admin usage exposes aggregate metadata but no ciphertext payload", async () => {
  const { env } = await environment();
  const usage = await getAdminUsage(accessRequest(ADMIN_TOKEN), env, NOW) as {
    totals: { activeUsers: number; activeDevices: number };
    daily: Array<{ eventsWritten: number }>;
    workspaces: Array<Record<string, unknown>>;
  };
  assert.equal(usage.totals.activeUsers, 2);
  assert.equal(usage.totals.activeDevices, 2);
  assert.equal(usage.daily[0].eventsWritten, 7);
  assert.equal(usage.workspaces.length, 2);
  assert.ok(usage.workspaces.every((workspace) => !("ciphertext" in workspace)));
  assert.doesNotMatch(JSON.stringify(usage), /authenticatedHeader|wrappedKey|signature/);
});

test("non-admin access token cannot inspect instance usage", async () => {
  const { env } = await environment();
  await assert.rejects(
    getAdminUsage(accessRequest(USER_TOKEN), env, NOW),
    (error: unknown) => (error as { status?: number; code?: string }).status === 403 &&
      (error as { code?: string }).code === "admin_required",
  );
});

test("signed quota mutation validates current usage atomically and records audit", async () => {
  const { db, env, keys } = await environment();
  db.prepare("UPDATE workspaces SET committed_bytes = 2097152 WHERE workspace_id = ?").run(IDS.workspaceA);
  const unsafe = {
    maxUsers: 25,
    maxWorkspacesPerUser: 1,
    maxDevicesPerUser: 10,
    maxWorkspaceBytes: 1048576,
    maxEventBytes: 1024,
    maxCheckpointBytes: 1048576,
  };
  await assert.rejects(
    updateAdminQuota(
      await signedQuotaRequest(keys, { ...unsafe, maxEventBytes: 32769, maxWorkspaceBytes: 4194304 }, 3),
      env,
      NOW,
    ),
    (error: unknown) => (error as { status?: number; code?: string }).status === 400 &&
      (error as { code?: string }).code === "invalid_max_event_bytes",
  );
  await assert.rejects(
    updateAdminQuota(
      await signedQuotaRequest(keys, { ...unsafe, maxWorkspacesPerUser: 0, maxWorkspaceBytes: 4194304 }, 6),
      env,
      NOW,
    ),
    (error: unknown) => (error as { status?: number; code?: string }).status === 400 &&
      (error as { code?: string }).code === "invalid_max_workspaces_per_user",
  );
  await assert.rejects(
    updateAdminQuota(await signedQuotaRequest(keys, unsafe, 1), env, NOW),
    (error: unknown) => (error as { status?: number; code?: string }).status === 409 &&
      (error as { code?: string }).code === "quota_below_current_usage",
  );
  assert.equal(
    (db.prepare("SELECT max_workspace_bytes AS value FROM quota_profiles WHERE quota_profile_id = 'private-default'")
      .get() as { value: number }).value,
    262144000,
  );

  const safe = { ...unsafe, maxWorkspaceBytes: 4194304 };
  const receipt = await updateAdminQuota(await signedQuotaRequest(keys, safe, 2), env, NOW) as {
    quota: { maxWorkspaceBytes: number };
  };
  assert.equal(receipt.quota.maxWorkspaceBytes, 4194304);
  assert.equal(
    (db.prepare("SELECT COUNT(*) AS count FROM audit_events WHERE action = 'admin_quota_updated'")
      .get() as { count: number }).count,
    1,
  );
});

test("updated device quota gates pairing early and activation trigger closes competing approval race", async () => {
  const { db, env, keys } = await environment();
  const limitOne = {
    maxUsers: 25,
    maxWorkspacesPerUser: 1,
    maxDevicesPerUser: 1,
    maxWorkspaceBytes: 262144000,
    maxEventBytes: 32768,
    maxCheckpointBytes: 33554432,
  };
  await updateAdminQuota(await signedQuotaRequest(keys, limitOne, 4), env, NOW);
  await assert.rejects(
    createPairing(
      await signedControlRequest(keys, "POST", "/v1/pairings", { workspaceId: IDS.workspaceA }, 5),
      env,
      NOW,
    ),
    (error: unknown) => (error as { status?: number; code?: string }).status === 507 &&
      (error as { code?: string }).code === "device_quota_exceeded",
  );

  db.prepare("UPDATE quota_profiles SET max_devices_per_user = 2 WHERE quota_profile_id = 'private-default'").run();
  const activate = async (suffix: string, pairingId: string, candidateId: string) => {
    db.prepare(`INSERT INTO pairing_sessions(
      pairing_id, workspace_id, sponsor_device_id, secret_hash, transcript_nonce,
      status, expires_at, candidate_device_id, candidate_display_name, candidate_platform,
      candidate_signing_public_key, candidate_wrapping_public_key, candidate_token_hash,
      candidate_submitted_at, created_at
    ) VALUES (?, ?, ?, ?, ?, 'candidate', ?, ?, ?, 'ios', ?, ?, ?, ?, ?)`).run(
      pairingId,
      IDS.workspaceA,
      IDS.deviceA,
      `secret-${suffix}`,
      `nonce-${suffix}`,
      NOW + 60_000,
      candidateId,
      `Candidate ${suffix}`,
      `sign-${suffix}`,
      `wrap-${suffix}`,
      `token-${suffix}`,
      NOW,
      NOW,
    );
    await atomicBatch(env.DB, [
      env.DB.prepare(`INSERT INTO devices(
        device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
        token_hash, status, auth_epoch, created_at
      ) VALUES (?1, ?2, ?3, 'ios', ?4, ?5, ?6, 'active', 1, ?7)`).bind(
        candidateId,
        IDS.userA,
        `Candidate ${suffix}`,
        `sign-${suffix}`,
        `wrap-${suffix}`,
        `token-${suffix}`,
        NOW,
      ),
      env.DB.prepare(`INSERT INTO device_key_attestations(
        device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
        signature_domain, manifest_json, signature, created_at
      ) VALUES (?1, ?2, 'pairing', ?3, 'sign-a', 'pairing-approval', '{}', ?4, ?5)`).bind(
        candidateId,
        IDS.workspaceA,
        IDS.deviceA,
        `signature-${suffix}`,
        NOW,
      ),
      env.DB.prepare(`INSERT INTO pairing_activations(
        pairing_id, candidate_device_id, envelope_count, activated_at
      ) VALUES (?1, ?2, 1, ?3)`).bind(pairingId, candidateId, NOW),
    ]);
  };

  await activate(
    "one",
    "00000000-0000-4000-8000-000000000030",
    "00000000-0000-4000-8000-000000000031",
  );
  await assert.rejects(
    activate(
      "two",
      "00000000-0000-4000-8000-000000000032",
      "00000000-0000-4000-8000-000000000033",
    ),
    (error: unknown) => (error as { code?: string }).code === "device_quota_exceeded",
  );
  assert.equal(
    (db.prepare("SELECT COUNT(*) AS count FROM devices WHERE user_id = ? AND status = 'active'")
      .get(IDS.userA) as { count: number }).count,
    2,
  );
  assert.equal(
    (db.prepare("SELECT COUNT(*) AS count FROM devices WHERE device_id = ?")
      .get("00000000-0000-4000-8000-000000000033") as { count: number }).count,
    0,
  );
});
