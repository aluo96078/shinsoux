import test from "node:test";
import assert from "node:assert/strict";
import { claimEmergencyWorkspaceHandoff } from "../src/api/emergency-reset.ts";
import { loadWorkspaceDeviceDirectory } from "../src/api/device-directory.ts";
import {
  canonicalJson,
  domainSeparatedMessage,
  encodeBase64Url,
  publicTokenCommitment,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import type { Env } from "../src/runtime.ts";
import { database, IDS, seedSecondTenant, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;
const RESET_ID = "00000000-0000-4000-8000-000000000020";
const NEW_DEVICE_ID = "00000000-0000-4000-8000-000000000021";
const HANDOFF_SECRET = encodeBase64Url(new Uint8Array(32).fill(71));

async function insertReset(db: ReturnType<typeof database>, confirmation: string): Promise<void> {
  const workspace = db.prepare(`SELECT active_key_epoch, directory_generation, head_seq, committed_bytes
    FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) as Record<string, number>;
  const handoffHash = await publicTokenCommitment(HANDOFF_SECRET, "emergency_handoff");
  db.prepare(`INSERT INTO emergency_workspace_resets(
    reset_id, workspace_id, owner_user_id, confirmation, r2_prefix,
    handoff_secret_hash, status, previous_key_epoch, previous_directory_generation,
    previous_head_seq, previous_committed_bytes, handoff_expires_at, requested_at
  ) VALUES (?, ?, ?, ?, ?, ?, 'requested', ?, ?, ?, ?, ?, ?)`).run(
    RESET_ID,
    IDS.workspaceA,
    IDS.userA,
    confirmation,
    `workspaces/${IDS.workspaceA}/`,
    handoffHash,
    workspace.active_key_epoch,
    workspace.directory_generation,
    workspace.head_seq,
    workspace.committed_bytes,
    NOW + 86_400_000,
    NOW,
  );
}

function environment(db: ReturnType<typeof database>): Env {
  return {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
  };
}

test("emergency reset requires the exact confirmation and atomically clears only one workspace", async () => {
  const db = database();
  seedTenant(db, NOW - 100);
  seedSecondTenant(db, NOW - 100);
  db.prepare(`INSERT INTO workspace_device_keys(
    workspace_id, key_epoch, device_id, rotation_id, key_commitment,
    wrapped_key, wrapped_by_device_id, signature, created_at
  ) VALUES (?, 1, ?, 'initial-old', 'old-commitment', 'old-wrapped-device-key-0000000000', ?,
            'old-envelope-signature', ?)`).run(IDS.workspaceA, IDS.deviceA, IDS.deviceA, NOW - 50);
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 1, 'initial-old', 'old-commitment', 'old-wrapped-recovery-key-00000000', ?)`).run(
    IDS.workspaceA,
    NOW - 50,
  );
  db.prepare(`INSERT INTO events(
    workspace_id, workspace_seq, event_id, device_id, device_seq, key_epoch,
    authenticated_header_cbor, ciphertext, ciphertext_sha256, signature, byte_size, created_at
  ) VALUES (?, 1, ?, ?, 1, 1, x'a0', x'010203', 'old-hash', 'old-signature', 3, ?)`).run(
    IDS.workspaceA,
    IDS.eventA,
    IDS.deviceA,
    NOW - 20,
  );
  db.prepare(`INSERT INTO access_tokens(
    access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
  ) VALUES ('old-access', ?, 'old-access-hash', 1, ?, ?)`).run(IDS.deviceA, NOW + 100_000, NOW - 10);

  await assert.rejects(
    insertReset(db, `RESET EMPTY WORKSPACE ${IDS.workspaceB}`),
    /CHECK constraint failed/,
  );
  assert.equal(db.prepare("SELECT status FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.status, "active");

  await insertReset(db, `RESET EMPTY WORKSPACE ${IDS.workspaceA}`);
  assert.deepEqual(
    { ...db.prepare(`SELECT status, active_key_epoch, directory_generation, head_seq, gc_before_seq,
      committed_bytes, reserved_bytes, stable_checkpoint_id, candidate_checkpoint_id
      FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) },
    {
      status: "reset",
      active_key_epoch: 1,
      directory_generation: 2,
      head_seq: 0,
      gc_before_seq: 0,
      committed_bytes: 0,
      reserved_bytes: 0,
      stable_checkpoint_id: null,
      candidate_checkpoint_id: null,
    },
  );
  assert.equal(db.prepare("SELECT status FROM emergency_workspace_resets WHERE reset_id = ?").get(RESET_ID)?.status, "purge_pending");
  assert.equal(db.prepare("SELECT status FROM devices WHERE device_id = ?").get(IDS.deviceA)?.status, "revoked");
  assert.equal(db.prepare("SELECT revoked_at FROM access_tokens WHERE access_token_id = 'old-access'").get()?.revoked_at, NOW);
  assert.equal(db.prepare("SELECT revoked_at FROM workspace_capabilities WHERE capability_id = ?").get(IDS.capabilityA)?.revoked_at, NOW);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM events WHERE workspace_id = ?").get(IDS.workspaceA)?.count, 0);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM event_receipts WHERE workspace_id = ?").get(IDS.workspaceA)?.count, 0);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM workspace_device_keys WHERE workspace_id = ?").get(IDS.workspaceA)?.count, 0);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM workspace_recovery_keys WHERE workspace_id = ?").get(IDS.workspaceA)?.count, 0);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM recovery_credentials WHERE user_id = ?").get(IDS.userA)?.count, 0);
  assert.equal(db.prepare("SELECT instance_role FROM users WHERE user_id = ?").get(IDS.userA)?.instance_role, "admin");

  // A second tenant is outside every mutation scope.
  assert.equal(db.prepare("SELECT status FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceB)?.status, "active");
  assert.equal(db.prepare("SELECT status FROM devices WHERE device_id = ?").get(IDS.deviceB)?.status, "active");
  assert.equal(db.prepare("SELECT revoked_at FROM workspace_capabilities WHERE capability_id = ?").get(IDS.capabilityB)?.revoked_at, null);
});

test("R2 purge receipt gates a one-time cryptographic handoff and starts a clean directory", async () => {
  const db = database();
  seedTenant(db, NOW - 100);
  await insertReset(db, `RESET EMPTY WORKSPACE ${IDS.workspaceA}`);
  const env = environment(db);

  const deviceKeys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const recoveryKeys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const signingPublicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", deviceKeys.publicKey)));
  const recoverySigningPublicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", recoveryKeys.publicKey)));
  const wrappingPublicKey = encodeBase64Url(new Uint8Array(32).fill(72));
  const recoveryWrappingPublicKey = encodeBase64Url(new Uint8Array(32).fill(73));
  const deviceToken = encodeBase64Url(new Uint8Array(32).fill(74));
  const keyCommitment = encodeBase64Url(new Uint8Array(32).fill(75));
  const deviceWrappedKey = "device-wrapped-key-value-0000000001";
  const recoveryWrappedKey = "recovery-wrapped-key-value-0000001";
  const deviceEnvelopeSignature = "e".repeat(80);
  const recoveryTrustManifest = canonicalJson({
    instanceId: IDS.instance,
    userId: IDS.userA,
    workspaceId: IDS.workspaceA,
    deviceId: NEW_DEVICE_ID,
    signingPublicKey,
    wrappingPublicKey,
    recoverySigningPublicKey,
    recoveryWrappingPublicKey,
  });
  const recoveryDeviceTrustSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    recoveryKeys.privateKey,
    domainSeparatedMessage("initial-device-recovery-trust", new TextEncoder().encode(recoveryTrustManifest)),
  )));
  const deviceTokenHash = await publicTokenCommitment(deviceToken, "device_token");
  const claimManifest = canonicalJson({
    instanceId: IDS.instance,
    userId: IDS.userA,
    workspaceId: IDS.workspaceA,
    deviceId: NEW_DEVICE_ID,
    signingPublicKey,
    wrappingPublicKey,
    deviceTokenHash,
    keyEpoch: 1,
    keyCommitment,
    deviceWrappedKeyHash: await sha256Base64Url(new TextEncoder().encode(deviceWrappedKey)),
    recoverySigningPublicKey,
    recoveryWrappingPublicKey,
    recoveryWrappedKeyHash: await sha256Base64Url(new TextEncoder().encode(recoveryWrappedKey)),
    recoveryDeviceTrustSignature,
  });
  const claimSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    deviceKeys.privateKey,
    domainSeparatedMessage("initial-workspace-claim", new TextEncoder().encode(claimManifest)),
  )));
  const body = {
    resetId: RESET_ID,
    workspaceId: IDS.workspaceA,
    userId: IDS.userA,
    displayName: "A",
    handoffSecret: HANDOFF_SECRET,
    device: {
      deviceId: NEW_DEVICE_ID,
      displayName: "Replacement phone",
      platform: "ios",
      signingPublicKey,
      wrappingPublicKey,
      deviceToken,
    },
    initialKeys: {
      keyCommitment,
      deviceWrappedKey,
      deviceEnvelopeSignature,
      recoverySigningPublicKey,
      recoveryWrappingPublicKey,
      recoveryWrappedKey,
      recoveryDeviceTrustSignature,
    },
    claimSignature,
  };
  const request = () => new Request("https://sync.test/v1/emergency-reset/handoff", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  await assert.rejects(
    claimEmergencyWorkspaceHandoff(request(), env, NOW + 1),
    (error: unknown) => (error as { code?: string }).code === "emergency_handoff_invalid_or_expired",
  );
  assert.throws(() => db.prepare(`INSERT INTO emergency_workspace_reset_purges(
    reset_id, workspace_id, r2_prefix, deleted_object_count, verified_remaining_count, purged_at
  ) VALUES (?, ?, ?, 1, 1, ?)`).run(
    RESET_ID, IDS.workspaceA, `workspaces/${IDS.workspaceA}/`, NOW + 1,
  ));
  db.prepare(`INSERT INTO emergency_workspace_reset_purges(
    reset_id, workspace_id, r2_prefix, deleted_object_count, verified_remaining_count, purged_at
  ) VALUES (?, ?, ?, 1, 0, ?)`).run(
    RESET_ID, IDS.workspaceA, `workspaces/${IDS.workspaceA}/`, NOW + 1,
  );
  assert.equal(db.prepare("SELECT status FROM emergency_workspace_resets WHERE reset_id = ?").get(RESET_ID)?.status, "handoff_ready");

  const result = await claimEmergencyWorkspaceHandoff(request(), env, NOW + 2) as Record<string, unknown>;
  assert.equal(result.deviceId, NEW_DEVICE_ID);
  assert.equal(db.prepare("SELECT status FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.status, "active");
  assert.equal(db.prepare("SELECT status FROM emergency_workspace_resets WHERE reset_id = ?").get(RESET_ID)?.status, "complete");
  assert.equal(db.prepare("SELECT directory_generation FROM devices WHERE device_id = ?").get(NEW_DEVICE_ID)?.directory_generation, 2);
  assert.equal(db.prepare("SELECT instance_role FROM users WHERE user_id = ?").get(IDS.userA)?.instance_role, "admin");

  const directory = await loadWorkspaceDeviceDirectory(env, IDS.workspaceA);
  assert.deepEqual(directory.devices.map((device) => device.deviceId), [NEW_DEVICE_ID]);
  assert.equal(directory.allDeviceCount, 1);

  await assert.rejects(
    claimEmergencyWorkspaceHandoff(request(), env, NOW + 3),
    (error: unknown) => (error as { code?: string }).code === "emergency_handoff_invalid_or_expired",
  );
});
