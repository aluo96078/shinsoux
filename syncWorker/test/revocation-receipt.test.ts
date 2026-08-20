import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/worker.ts";
import {
  domainSeparatedMessage,
  encodeBase64Url,
  hashSecret,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import type { Env, ExecutionContext } from "../src/runtime.ts";
import { database, IDS, seedSecondTenant, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = Date.now();
const PEPPER = "p".repeat(32);
const ADMIN_TOKEN = "revocation-access-token-abcdefghijklmnopqrstuvwxyz";
const OTHER_TOKEN = "other-device-access-token-abcdefghijklmnopqrstuvwxyz";
const REVOCATION_ID = "00000000-0000-4000-8000-000000000040";
const SECOND_REVOCATION_ID = "00000000-0000-4000-8000-000000000041";

const context: ExecutionContext = {
  waitUntil() {},
  passThroughOnException() {},
};

async function fixture() {
  const db = database();
  seedTenant(db, NOW);
  seedSecondTenant(db, NOW);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'Second device', 'macos', 'sign-a2', 'wrap-a2', 'token-a2', 'active', 1, ?)`).run(
    IDS.deviceA2,
    IDS.userA,
    NOW,
  );
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const publicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)));
  db.exec("DROP TRIGGER devices_identity_keys_immutable");
  db.prepare("UPDATE devices SET signing_public_key = ? WHERE device_id = ?").run(publicKey, IDS.deviceA);
  for (const [id, deviceId, token] of [
    ["00000000-0000-4000-8000-000000000042", IDS.deviceA, ADMIN_TOKEN],
    ["00000000-0000-4000-8000-000000000043", IDS.deviceB, OTHER_TOKEN],
  ]) {
    db.prepare(`INSERT INTO access_tokens(
      access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
    ) VALUES (?, ?, ?, 1, ?, ?)`).run(
      id,
      deviceId,
      await hashSecret(token, PEPPER, "access-token"),
      NOW + 60_000,
      NOW,
    );
  }
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

async function revokeRequest(
  keys: CryptoKeyPair,
  targetDeviceId: string,
  revocationId: string,
  nonceByte: number,
): Promise<Request> {
  const path = `/v1/devices/${targetDeviceId}/revoke`;
  const body = JSON.stringify({ revocationId });
  const nonce = encodeBase64Url(new Uint8Array(24).fill(nonceByte));
  const bodyHash = await sha256Base64Url(new TextEncoder().encode(body));
  const message = domainSeparatedMessage(
    "control-request",
    new TextEncoder().encode(`POST\n${path}\n${NOW}\n${nonce}\n${bodyHash}\n${IDS.deviceA}`),
  );
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign("Ed25519", keys.privateKey, message)));
  return new Request(`https://sync.test${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${ADMIN_TOKEN}`,
      "Content-Type": "application/json",
      "X-Shinsou-Timestamp": String(NOW),
      "X-Shinsou-Nonce": nonce,
      "X-Shinsou-Signature": signature,
    },
    body,
  });
}

test("revocation response loss retries exact operation without applying side effects twice", async () => {
  const { db, env, keys } = await fixture();
  const first = await worker.fetch(
    await revokeRequest(keys, IDS.deviceA2, REVOCATION_ID, 1),
    env,
    context,
  );
  assert.equal(first.status, 201);
  const firstReceipt = await first.json() as Record<string, unknown>;
  const retry = await worker.fetch(
    await revokeRequest(keys, IDS.deviceA2, REVOCATION_ID, 2),
    env,
    context,
  );
  assert.equal(retry.status, 200);
  assert.deepEqual(await retry.json(), firstReceipt);

  assert.deepEqual(
    { ...db.prepare("SELECT status, auth_epoch FROM devices WHERE device_id = ?").get(IDS.deviceA2) },
    { status: "revoked", auth_epoch: 2 },
  );
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM device_revocations WHERE revocation_id = ?")
      .get(REVOCATION_ID)?.count,
    1,
  );
  assert.equal(
    db.prepare("SELECT device_directory_epoch FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.device_directory_epoch,
    2,
  );
  const bindings = firstReceipt.workspaceBindings as Array<Record<string, unknown>>;
  assert.equal(bindings[0].workspaceId, IDS.workspaceA);
  assert.equal(bindings[0].revokedAtKeyEpoch, 1);
  assert.equal(bindings[0].directoryEpochAfterRevocation, 2);
  assert.equal(bindings[0].currentRotationRequired, true);
});

test("revocation receipt lookup is actor-bound and operation id cannot bind another target", async () => {
  const { env, keys } = await fixture();
  await worker.fetch(await revokeRequest(keys, IDS.deviceA2, REVOCATION_ID, 3), env, context);

  const own = await worker.fetch(new Request(
    `https://sync.test/v1/device-revocations/${REVOCATION_ID}`,
    { headers: { Authorization: `Bearer ${ADMIN_TOKEN}` } },
  ), env, context);
  assert.equal(own.status, 200);
  const other = await worker.fetch(new Request(
    `https://sync.test/v1/device-revocations/${REVOCATION_ID}`,
    { headers: { Authorization: `Bearer ${OTHER_TOKEN}` } },
  ), env, context);
  assert.equal(other.status, 404);

  const rebound = await worker.fetch(
    await revokeRequest(keys, IDS.deviceB, REVOCATION_ID, 4),
    env,
    context,
  );
  assert.equal(rebound.status, 409);
  assert.equal((await rebound.json() as { error: { code: string } }).error.code, "device_revocation_id_conflict");

  const unrelatedRetry = await worker.fetch(
    await revokeRequest(keys, IDS.deviceA2, SECOND_REVOCATION_ID, 5),
    env,
    context,
  );
  assert.equal(unrelatedRetry.status, 404);
});

test("receipt reports a committed covering rotation without changing immutable revoke epoch", async () => {
  const { db, env, keys } = await fixture();
  await worker.fetch(await revokeRequest(keys, IDS.deviceA2, REVOCATION_ID, 6), env, context);
  db.prepare(`INSERT INTO key_rotations(
    rotation_id, workspace_id, from_epoch, to_epoch, proposer_device_id,
    proposer_device_auth_epoch, membership_auth_epoch, status, lease_expires_at,
    created_at, committed_at
  ) VALUES (?, ?, 1, 2, ?, 1, 1, 'committed', ?, ?, ?)`).run(
    IDS.rotationA,
    IDS.workspaceA,
    IDS.deviceA,
    NOW + 60_000,
    NOW + 1,
    NOW + 2,
  );
  db.prepare(`UPDATE workspaces
    SET active_key_epoch = 2, rotation_required = 0 WHERE workspace_id = ?`).run(IDS.workspaceA);

  const response = await worker.fetch(new Request(
    `https://sync.test/v1/device-revocations/${REVOCATION_ID}`,
    { headers: { Authorization: `Bearer ${ADMIN_TOKEN}` } },
  ), env, context);
  const receipt = await response.json() as { workspaceBindings: Array<Record<string, unknown>> };
  const binding = receipt.workspaceBindings[0];
  assert.equal(binding.revokedAtKeyEpoch, 1);
  assert.equal(binding.currentActiveKeyEpoch, 2);
  assert.equal(binding.currentRotationRequired, false);
  assert.equal(binding.coveringRotationId, IDS.rotationA);
  assert.equal(binding.coveringProposerDeviceId, IDS.deviceA);
});
