import test from "node:test";
import assert from "node:assert/strict";
import {
  APPEND_EVENT_SQL,
  CANCEL_ROTATION_BLOCKED_CHECKPOINT_LEASE_SQL,
  COMMIT_CHECKPOINT_SQL,
  CREATE_CAPABILITY_SQL,
  CREATE_CHECKPOINT_LEASE_SQL,
  CREATE_ROTATION_LEASE_SQL,
  MARK_CHECKPOINT_UPLOADED_SQL,
  PROMOTE_CHECKPOINT_SQL,
} from "../src/storage/sql.ts";
import { classifyDeviceSequence } from "../src/protocol.ts";
import { createCheckpointLease } from "../src/api/checkpoints.ts";
import { appendEvent, loadCheckpointCandidate, lookupEventReceipt, workspaceBootstrap } from "../src/api/events.ts";
import { encodeCanonicalCbor } from "../src/crypto/cbor.ts";
import { encodeBase64Url, sha256Base64Url } from "../src/crypto/base.ts";
import { envelopeSignatureMessage } from "../src/protocol.ts";
import type { CapabilityPrincipal, Env } from "../src/runtime.ts";
import { database, IDS, numberedGet, seedSecondTenant, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;

function numberedChanges(
  db: ReturnType<typeof database>,
  sql: string,
  ...values: unknown[]
): number {
  const bindings: Record<string, unknown> = {};
  values.forEach((value, index) => { bindings[`?${index + 1}`] = value; });
  return Number(db.prepare(sql).run(bindings).changes);
}

function append(
  db: ReturnType<typeof database>,
  options: {
    workspaceId?: string;
    eventId?: string;
    deviceId?: string;
    deviceSeq?: number;
    keyEpoch?: number;
    capabilityId?: string;
    hash?: string;
    size?: number;
  } = {},
) {
  return numberedGet(db, APPEND_EVENT_SQL,
    options.workspaceId ?? IDS.workspaceA,
    options.eventId ?? IDS.eventA,
    options.deviceId ?? IDS.deviceA,
    options.deviceSeq ?? 1,
    options.keyEpoch ?? 1,
    Uint8Array.of(0xa0),
    Uint8Array.of(1, 2, 3),
    options.hash ?? "hash-a",
    "signature-a",
    options.size ?? 3,
    NOW + 1,
    options.capabilityId ?? IDS.capabilityA,
  );
}

test("numbered migration creates every authoritative table and index", () => {
  const db = database();
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all()
    .map((row) => String(row.name));
  for (const required of [
    "installation", "users", "devices", "workspaces", "memberships",
    "events", "event_receipts", "checkpoint_leases", "checkpoints",
    "key_rotations", "workspace_device_keys", "recovery_credentials",
    "device_revocation_workspaces",
    "blob_upload_sessions", "encrypted_blob_manifests", "blob_dek_envelopes",
    "blob_tombstones", "blob_tombstone_acks", "blob_gc_claims", "blob_gc_receipts",
  ]) assert.ok(tables.includes(required), `${required} missing`);
  assert.deepEqual(
    db.prepare("SELECT version FROM schema_migrations ORDER BY version").all().map((row) => row.version),
    [1, 2, 4, 5],
  );
});

test("installation bootstrap row is a one-time claim gate", () => {
  const db = database();
  seedTenant(db, NOW);
  assert.throws(() => db.prepare(`INSERT INTO installation(
    instance_id, schema_version, initialized_at, signup_mode
  ) VALUES (?, 1, ?, 'invite_only')`).run(
    "00000000-0000-4000-8000-000000000099",
    NOW + 1,
  ));
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM installation").get()?.count, 1);
});

test("event conditional insert atomically advances only its workspace head and permanent receipt", () => {
  const db = database();
  seedTenant(db, NOW);
  seedSecondTenant(db, NOW);
  const row = append(db);
  assert.equal(row?.workspaceSeq, 1);
  assert.deepEqual(
    { ...db.prepare("SELECT head_seq, committed_bytes FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA) },
    { head_seq: 1, committed_bytes: 3 },
  );
  assert.equal(
    db.prepare("SELECT head_seq FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceB)?.head_seq,
    0,
  );
  assert.deepEqual(
    { ...db.prepare(`SELECT committed_device_seq FROM device_stream_state
      WHERE workspace_id = ? AND device_id = ?`).get(IDS.workspaceA, IDS.deviceA) },
    { committed_device_seq: 1 },
  );
  assert.deepEqual(
    { ...db.prepare(`SELECT workspace_seq, ciphertext_sha256 FROM event_receipts
      WHERE workspace_id = ? AND device_id = ? AND device_seq = 1`).get(IDS.workspaceA, IDS.deviceA) },
    { workspace_seq: 1, ciphertext_sha256: "hash-a" },
  );
});

test("a capability from another tenant cannot append or create a cursor gap", () => {
  const db = database();
  seedTenant(db, NOW);
  seedSecondTenant(db, NOW);
  const rejected = append(db, {
    deviceId: IDS.deviceB,
    capabilityId: IDS.capabilityB,
    eventId: IDS.eventB,
  });
  assert.equal(rejected, undefined);
  assert.equal(db.prepare("SELECT head_seq FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.head_seq, 0);
  const accepted = append(db);
  assert.equal(accepted?.workspaceSeq, 1);
});

test("commit-time revoke and rotation checks close precheck TOCTOU", () => {
  const revoked = database();
  seedTenant(revoked, NOW);
  revoked.prepare("UPDATE devices SET status = 'revoked', auth_epoch = 2 WHERE device_id = ?").run(IDS.deviceA);
  assert.equal(append(revoked), undefined);
  assert.equal(revoked.prepare("SELECT head_seq FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.head_seq, 0);

  const rotated = database();
  seedTenant(rotated, NOW);
  rotated.prepare("UPDATE workspaces SET active_key_epoch = 2 WHERE workspace_id = ?").run(IDS.workspaceA);
  assert.equal(append(rotated, { keyEpoch: 1 }), undefined);
  assert.equal(rotated.prepare("SELECT COUNT(*) AS count FROM events").get()?.count, 0);

  // A surviving device can still hold an otherwise-valid old-epoch capability after revoke or
  // recovery commits. The rotation gate must therefore live in the conditional D1 insert.
  const rotationRequired = database();
  seedTenant(rotationRequired, NOW);
  rotationRequired.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  assert.equal(append(rotationRequired), undefined);
  assert.deepEqual(
    { ...rotationRequired.prepare(`SELECT head_seq, committed_bytes
      FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) },
    { head_seq: 0, committed_bytes: 0 },
  );
  assert.equal(rotationRequired.prepare("SELECT COUNT(*) AS count FROM events").get()?.count, 0);
});

test("rotation-required blocks checkpoint content writes but preserves read/rotation capability", async () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare(`INSERT INTO access_tokens(
    access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
  ) VALUES (?, ?, 'access-token', 1, ?, ?)`).run(IDS.challenge, IDS.deviceA, NOW + 20_000, NOW);
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);

  // Capability issuance remains available because bootstrap/catch-up and key-rotation routes use
  // the same short-lived capability. Only event/checkpoint content paths are gated.
  const capability = numberedGet(db, CREATE_CAPABILITY_SQL,
    IDS.capabilityB,
    IDS.workspaceA,
    IDS.deviceA,
    "new-capability-token",
    NOW + 10_000,
    NOW + 1,
    IDS.challenge,
  );
  assert.ok(capability);
  assert.ok(numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA,
    IDS.workspaceA,
    NOW + 10_000,
    NOW + 1,
    IDS.deviceA,
  ));
  assert.equal(numberedGet(db, CREATE_CHECKPOINT_LEASE_SQL,
    IDS.claim,
    IDS.workspaceA,
    IDS.checkpointA,
    "checkpoint-hash",
    100,
    NOW + 10_000,
    NOW + 2,
    IDS.capabilityB,
    0,
  ), undefined);
  assert.deepEqual(
    { ...db.prepare(`SELECT reserved_bytes, candidate_checkpoint_id
      FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) },
    { reserved_bytes: 0, candidate_checkpoint_id: null },
  );

  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', '{}', 'signature', ?)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA, NOW);
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "unused",
    TOKEN_PEPPER: "unused",
    RATE_LIMIT_SALT: "unused",
    INSTANCE_ID: IDS.instance,
  };
  const principal: CapabilityPrincipal = {
    capabilityId: IDS.capabilityB,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 1,
    signingPublicKey: "sign-a",
    expiresAt: NOW + 10_000,
  };
  await assert.rejects(
    createCheckpointLease(new Request("https://sync.test/checkpoint-leases", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        checkpointId: IDS.checkpointA,
        ciphertextSha256: "A".repeat(43),
        expectedByteSize: 100,
        throughWorkspaceSeq: 0,
      }),
    }), env, principal, NOW + 2),
    (error: unknown) => (error as { code?: string }).code === "checkpoint_rotation_required",
  );
  const bootstrap = await workspaceBootstrap(env, principal) as Record<string, unknown>;
  assert.equal(bootstrap.workspaceId, IDS.workspaceA);
  assert.equal(bootstrap.activeKeyEpoch, 1);
  assert.equal(bootstrap.rotationRequired, true);
});

test("append reports key_rotation_required without consuming the old-epoch sequence", async () => {
  const db = database();
  seedTenant(db, NOW);
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const signingPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)),
  );
  const capabilityId = "00000000-0000-4000-8000-000000000013";
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'rotation writer', 'ios', ?, 'wrap-a2', 'token-a2', 'active', 1, ?)`)
    .run(IDS.deviceA2, IDS.userA, signingPublicKey, NOW);
  db.prepare(`INSERT INTO workspace_capabilities(
    capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
    membership_auth_epoch, key_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 'rotation-capability', 1, 1, 1, ?, ?)`)
    .run(capabilityId, IDS.workspaceA, IDS.deviceA2, NOW + 10_000, NOW);
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);

  const ciphertext = Uint8Array.of(1, 2, 3);
  const ciphertextSha256 = await sha256Base64Url(ciphertext);
  const headerCbor = encodeCanonicalCbor({
    envelopeVersion: 1,
    protocolVersion: 1,
    schemaVersion: 1,
    cipherSuite: "CHACHA20_POLY1305",
    nonce: encodeBase64Url(new Uint8Array(12)),
    instanceId: IDS.instance,
    workspaceId: IDS.workspaceA,
    eventId: IDS.eventB,
    deviceId: IDS.deviceA2,
    deviceSeq: 1,
    keyEpoch: 1,
  });
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    keys.privateKey,
    envelopeSignatureMessage(
      "event",
      headerCbor,
      new Uint8Array(await crypto.subtle.digest("SHA-256", ciphertext)),
    ),
  )));
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "unused",
    TOKEN_PEPPER: "unused",
    RATE_LIMIT_SALT: "unused",
    INSTANCE_ID: IDS.instance,
  };
  const principal: CapabilityPrincipal = {
    capabilityId,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA2,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 1,
    signingPublicKey,
    expiresAt: NOW + 10_000,
  };
  await assert.rejects(
    appendEvent(new Request("https://sync.test/events", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        headerCbor: encodeBase64Url(headerCbor),
        ciphertext: encodeBase64Url(ciphertext),
        ciphertextSha256,
        signature,
      }),
    }), env, principal, NOW + 1),
    (error: unknown) => (error as { code?: string }).code === "key_rotation_required",
  );
  assert.equal(db.prepare(`SELECT committed_device_seq FROM device_stream_state
    WHERE workspace_id = ? AND device_id = ?`).get(IDS.workspaceA, IDS.deviceA2), undefined);
  assert.equal(db.prepare("SELECT head_seq FROM workspaces WHERE workspace_id = ?")
    .get(IDS.workspaceA)?.head_seq, 0);
});

test("checkpoint upload and commit recheck rotation-required after a valid lease", () => {
  const db = database();
  seedTenant(db, NOW);
  assert.ok(numberedGet(db, CREATE_CHECKPOINT_LEASE_SQL,
    IDS.challenge,
    IDS.workspaceA,
    IDS.checkpointA,
    "checkpoint-hash",
    100,
    NOW + 10_000,
    NOW + 2,
    IDS.capabilityA,
    0,
  ));
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);

  // The atomic upload transition loses the revoke race, then cancellation releases its quota.
  assert.equal(numberedGet(db, MARK_CHECKPOINT_UPLOADED_SQL,
    IDS.workspaceA,
    IDS.checkpointA,
    "checkpoint-hash",
    80,
    "safe/r2/key",
    NOW + 3,
    IDS.deviceA,
  ), undefined);
  assert.ok(numberedGet(db, CANCEL_ROTATION_BLOCKED_CHECKPOINT_LEASE_SQL, IDS.challenge));
  assert.deepEqual(
    { ...db.prepare(`SELECT status, actual_byte_size, r2_key
      FROM checkpoint_leases WHERE lease_id = ?`).get(IDS.challenge) },
    { status: "cancelled", actual_byte_size: null, r2_key: null },
  );
  assert.equal(db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
    .get(IDS.workspaceA)?.reserved_bytes, 0);

  // A lease that reached uploaded just before the revoke still cannot become a candidate.
  const commitRace = database();
  seedTenant(commitRace, NOW);
  assert.ok(numberedGet(commitRace, CREATE_CHECKPOINT_LEASE_SQL,
    IDS.challenge,
    IDS.workspaceA,
    IDS.checkpointA,
    "checkpoint-hash",
    100,
    NOW + 10_000,
    NOW + 2,
    IDS.capabilityA,
    0,
  ));
  assert.ok(numberedGet(commitRace, MARK_CHECKPOINT_UPLOADED_SQL,
    IDS.workspaceA,
    IDS.checkpointA,
    "checkpoint-hash",
    80,
    "safe/r2/key",
    NOW + 3,
    IDS.deviceA,
  ));
  commitRace.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  assert.equal(numberedGet(commitRace, COMMIT_CHECKPOINT_SQL,
    IDS.workspaceA,
    IDS.checkpointA,
    "checkpoint-hash",
    Uint8Array.of(0xa0),
    1,
    1,
    "AES_256_GCM",
    "nonce",
    null,
    "signature",
    NOW + 4,
    IDS.capabilityA,
  ), undefined);
  assert.equal(commitRace.prepare("SELECT COUNT(*) AS count FROM checkpoints").get()?.count, 0);
});

test("receipt hash survives payload GC and still detects corruption", async () => {
  const db = database();
  seedTenant(db, NOW);
  append(db);
  db.prepare("DELETE FROM events WHERE workspace_id = ?").run(IDS.workspaceA);
  const receipt = db.prepare(`SELECT ciphertext_sha256 AS ciphertextSha256,
    workspace_seq AS workspaceSeq FROM event_receipts
    WHERE workspace_id = ? AND device_id = ? AND device_seq = 1`)
    .get(IDS.workspaceA, IDS.deviceA) as { ciphertextSha256: string; workspaceSeq: number };
  assert.deepEqual(classifyDeviceSequence(1, 1, receipt, "hash-a"), {
    kind: "duplicate",
    workspaceSeq: 1,
  });
  assert.deepEqual(classifyDeviceSequence(1, 1, receipt, "different"), {
    kind: "replay_or_corruption",
  });
  const lookedUp = await lookupEventReceipt(
    { DB: sqliteD1(db) } as Env,
    { workspaceId: IDS.workspaceA, deviceId: IDS.deviceA } as CapabilityPrincipal,
    1,
  );
  assert.deepEqual(
    {
      duplicate: lookedUp.duplicate,
      eventId: lookedUp.eventId,
      workspaceSeq: lookedUp.workspaceSeq,
      ciphertextSha256: lookedUp.ciphertextSha256,
    },
    { duplicate: true, eventId: IDS.eventA, workspaceSeq: 1, ciphertextSha256: "hash-a" },
  );
});

test("single-use invite guard rolls back replay", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare(`INSERT INTO invites(
    invite_id, created_by_device_id, secret_hash, expires_at, status, created_at
  ) VALUES (?, ?, 'invite-hash', ?, 'active', ?)`)
    .run(IDS.invite, IDS.deviceA, NOW + 1000, NOW);
  db.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
    VALUES (?, 'B', 'user', 'active', 'private-default', ?)`)
    .run(IDS.userB, NOW);
  db.prepare("INSERT INTO invite_redemptions(invite_id, user_id, redeemed_at) VALUES (?, ?, ?)")
    .run(IDS.invite, IDS.userB, NOW + 1);
  assert.equal(db.prepare("SELECT status FROM invites WHERE invite_id = ?").get(IDS.invite)?.status, "redeemed");
  assert.throws(() => db.prepare(
    "INSERT INTO invite_redemptions(invite_id, user_id, redeemed_at) VALUES (?, ?, ?)",
  ).run(IDS.invite, IDS.userA, NOW + 2));
});

test("expired invite cannot be redeemed even when its hash was found earlier", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare(`INSERT INTO invites(
    invite_id, created_by_device_id, secret_hash, expires_at, status, created_at
  ) VALUES (?, ?, 'expired-hash', ?, 'active', ?)`)
    .run(IDS.invite, IDS.deviceA, NOW + 1, NOW);
  db.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
    VALUES (?, 'B', 'user', 'active', 'private-default', ?)`)
    .run(IDS.userB, NOW);
  assert.throws(() => db.prepare(
    "INSERT INTO invite_redemptions(invite_id, user_id, redeemed_at) VALUES (?, ?, ?)",
  ).run(IDS.invite, IDS.userB, NOW + 2), /invite_invalid/);
});

test("device revoke increments auth epoch, revokes capabilities and requires rotation", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'A desktop', 'macos', 'sign-a2', 'wrap-a2', 'token-a2', 'active', 1, ?)`)
    .run(IDS.deviceA2, IDS.userA, NOW);
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  numberedGet(
    db,
    CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA,
    IDS.workspaceA,
    NOW + 10_000,
    NOW,
    IDS.deviceA,
  );
  db.prepare(`INSERT INTO device_revocations(
    revocation_id, actor_device_id, target_device_id, created_at
  ) VALUES (?, ?, ?, ?)`)
    .run(IDS.claim, IDS.deviceA, IDS.deviceA2, NOW + 1);
  assert.deepEqual(
    { ...db.prepare("SELECT status, auth_epoch FROM devices WHERE device_id = ?").get(IDS.deviceA2) },
    { status: "revoked", auth_epoch: 2 },
  );
  assert.equal(db.prepare("SELECT rotation_required FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.rotation_required, 1);
  assert.equal(db.prepare("SELECT status FROM key_rotations WHERE rotation_id = ?").get(IDS.rotationA)?.status, "aborted");
  assert.equal(
    db.prepare("SELECT pending_rotation_id FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.pending_rotation_id,
    null,
  );
  assert.throws(() => db.prepare(`INSERT INTO device_revocations(
    revocation_id, actor_device_id, target_device_id, created_at
  ) VALUES (?, ?, ?, ?)`).run(IDS.challenge, IDS.deviceA, IDS.deviceA, NOW + 2));
});

test("rotation commit requires the immutable complete recipient set and recovery envelope", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  const lease = numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA, IDS.workspaceA, NOW + 10_000, NOW + 1, IDS.deviceA,
  ) as Record<string, unknown>;
  assert.equal(lease.from_epoch ?? lease.fromEpoch, 1);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM key_rotation_recipients").get()?.count, 1);
  db.prepare(`UPDATE key_rotations SET key_commitment = 'commitment',
    manifest_cbor = x'a0', manifest_signature = 'signature' WHERE rotation_id = ?`)
    .run(IDS.rotationA);
  assert.throws(() => db.prepare(
    "INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)",
  ).run(IDS.rotationA, NOW + 2));
  db.prepare(`INSERT INTO workspace_device_keys(
    workspace_id, key_epoch, device_id, rotation_id, key_commitment,
    wrapped_key, wrapped_by_device_id, signature, created_at
  ) VALUES (?, 2, ?, ?, 'commitment', 'wrapped', ?, 'signature', ?)`)
    .run(IDS.workspaceA, IDS.deviceA, IDS.rotationA, IDS.deviceA, NOW + 2);
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 2, ?, 'commitment', 'recovery-wrapped', ?)`)
    .run(IDS.workspaceA, IDS.rotationA, NOW + 2);
  db.prepare("INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)")
    .run(IDS.rotationA, NOW + 3);
  assert.deepEqual(
    { ...db.prepare(`SELECT active_key_epoch, pending_rotation_id, rotation_required
      FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) },
    { active_key_epoch: 2, pending_rotation_id: null, rotation_required: 0 },
  );
  assert.equal(numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.claim, IDS.workspaceA, NOW + 20_000, NOW + 4, IDS.deviceA,
  ), undefined);
});

test("new active recipient appearing during rotation invalidates the lease", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA, IDS.workspaceA, NOW + 10_000, NOW + 1, IDS.deviceA,
  );
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'new', 'ios', 'sign', 'wrap', 'token-new', 'active', 1, ?)`)
    .run(IDS.deviceA2, IDS.userA, NOW + 2);
  db.prepare(`UPDATE key_rotations SET key_commitment = 'commitment',
    manifest_cbor = x'a0', manifest_signature = 'signature' WHERE rotation_id = ?`)
    .run(IDS.rotationA);
  db.prepare(`INSERT INTO workspace_device_keys(
    workspace_id, key_epoch, device_id, rotation_id, key_commitment,
    wrapped_key, wrapped_by_device_id, signature, created_at
  ) VALUES (?, 2, ?, ?, 'commitment', 'wrapped', ?, 'signature', ?)`)
    .run(IDS.workspaceA, IDS.deviceA, IDS.rotationA, IDS.deviceA, NOW + 2);
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 2, ?, 'commitment', 'wrapped', ?)`)
    .run(IDS.workspaceA, IDS.rotationA, NOW + 2);
  assert.throws(() => db.prepare(
    "INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)",
  ).run(IDS.rotationA, NOW + 3), /rotation_recipient_changed/);
});

test("rotation leases require a durable revoke/recovery reason and stop after it clears", () => {
  const db = database();
  seedTenant(db, NOW);
  assert.equal(numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA, IDS.workspaceA, NOW + 10_000, NOW + 1, IDS.deviceA,
  ), undefined);

  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  assert.ok(numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA, IDS.workspaceA, NOW + 10_000, NOW + 2, IDS.deviceA,
  ));

  // A completion/reconciliation racing a retry clears the durable reason. Even after the prior
  // lease is no longer pending, the retry cannot create an unrequested second epoch.
  db.prepare("UPDATE key_rotations SET status = 'aborted' WHERE rotation_id = ?").run(IDS.rotationA);
  db.prepare(`UPDATE workspaces SET pending_rotation_id = NULL, rotation_required = 0
    WHERE workspace_id = ?`).run(IDS.workspaceA);
  assert.equal(numberedGet(db, CREATE_ROTATION_LEASE_SQL,
    IDS.claim, IDS.workspaceA, NOW + 20_000, NOW + 3, IDS.deviceA,
  ), undefined);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM key_rotations").get()?.count, 1);
});

test("checkpoint reservation, discoverable candidate and exact-hash promotion settle quota atomically", async () => {
  const db = database();
  seedTenant(db, NOW);
  append(db);
  const hash = "checkpoint-hash";
  const leaseId = IDS.challenge;
  const lease = numberedGet(db, CREATE_CHECKPOINT_LEASE_SQL,
    leaseId,
    IDS.workspaceA,
    IDS.checkpointA,
    hash,
    100,
    NOW + 10_000,
    NOW + 2,
    IDS.capabilityA,
    1,
  ) as Record<string, unknown>;
  assert.equal(lease.through_seq ?? lease.throughSeq, 1);
  assert.equal(db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.reserved_bytes, 100);
  db.prepare(`UPDATE checkpoint_leases SET status = 'uploaded', actual_byte_size = 80,
    r2_key = 'safe/r2/key' WHERE lease_id = ?`).run(leaseId);
  const candidate = numberedGet(db, COMMIT_CHECKPOINT_SQL,
    IDS.workspaceA,
    IDS.checkpointA,
    hash,
    Uint8Array.of(0xa0),
    1,
    1,
    "AES_256_GCM",
    "nonce",
    null,
    "signature",
    NOW + 3,
    IDS.capabilityA,
  );
  assert.ok(candidate);
  assert.deepEqual(
    { ...db.prepare(`SELECT candidate_checkpoint_id, reserved_bytes, committed_bytes
      FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) },
    { candidate_checkpoint_id: IDS.checkpointA, reserved_bytes: 0, committed_bytes: 83 },
  );
  const discoverable = await loadCheckpointCandidate(
    { DB: sqliteD1(db) } as Env,
    IDS.workspaceA,
  ) as Record<string, unknown>;
  assert.deepEqual(
    {
      checkpointId: discoverable.checkpointId,
      ciphertextSha256: discoverable.ciphertextSha256,
      throughWorkspaceSeq: discoverable.throughWorkspaceSeq,
      previousStableThroughWorkspaceSeq: discoverable.previousStableThroughWorkspaceSeq,
      status: discoverable.status,
    },
    {
      checkpointId: IDS.checkpointA,
      ciphertextSha256: hash,
      throughWorkspaceSeq: 1,
      previousStableThroughWorkspaceSeq: 0,
      status: "candidate",
    },
  );
  db.prepare(`INSERT INTO checkpoint_acks(
    workspace_id, checkpoint_id, ciphertext_sha256, validator_device_id,
    validation_version, replay_from_seq, replayed_through_seq, replayed_event_count,
    signature, created_at
  ) VALUES (?, ?, ?, ?, 1, 0, 1, 1, 'ack-signature', ?)`)
    .run(IDS.workspaceA, IDS.checkpointA, hash, IDS.deviceA, NOW + 4);
  assert.equal(numberedChanges(db, PROMOTE_CHECKPOINT_SQL,
    IDS.workspaceA, IDS.checkpointA, "wrong-hash", NOW + 5,
    IDS.capabilityA, IDS.deviceA, 0,
  ), 0);
  assert.equal(numberedChanges(db, PROMOTE_CHECKPOINT_SQL,
    IDS.workspaceA, IDS.checkpointA, hash, NOW + 5,
    IDS.capabilityA, IDS.deviceA, 0,
  ), 1);
  assert.deepEqual(
    { ...db.prepare(`SELECT stable_checkpoint_id, candidate_checkpoint_id
      FROM workspaces WHERE workspace_id = ?`).get(IDS.workspaceA) },
    { stable_checkpoint_id: IDS.checkpointA, candidate_checkpoint_id: null },
  );
});

test("event GC refuses to run without two stable recovery bases", () => {
  const db = database();
  seedTenant(db, NOW);
  append(db);
  db.prepare(`INSERT INTO workspace_gc_state(workspace_id, target_seq, execute_after, status, updated_at)
    VALUES (?, 1, ?, 'waiting', ?)`).run(IDS.workspaceA, NOW, NOW);
  assert.throws(() => db.prepare(
    "INSERT INTO event_gc_runs(workspace_id, target_seq, executed_at) VALUES (?, 1, ?)",
  ).run(IDS.workspaceA, NOW + 1), /event_gc_not_safe/);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM events").get()?.count, 1);
});

test("safe event GC stops at recovery base and preserves compact receipts", () => {
  const db = database();
  seedTenant(db, NOW);
  append(db);
  append(db, { eventId: IDS.eventB, deviceSeq: 2, hash: "hash-b" });
  const insertStable = db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, r2_key,
    ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at, promoted_at
  ) VALUES (?, ?, ?, 1, x'a0', 1, 1, 'AES_256_GCM', 'nonce', ?, ?, 10, ?, 'sig', 'stable', ?, ?)`);
  insertStable.run(
    IDS.workspaceA, 1, IDS.checkpointA, "r2/c1", "checkpoint-hash-1",
    IDS.deviceA, NOW + 1, NOW + 1,
  );
  insertStable.run(
    IDS.workspaceA, 2, IDS.checkpointB, "r2/c2", "checkpoint-hash-2",
    IDS.deviceA, NOW + 2, NOW + 2,
  );
  db.prepare(`INSERT INTO workspace_gc_state(workspace_id, target_seq, execute_after, status, updated_at)
    VALUES (?, 1, ?, 'waiting', ?)`).run(IDS.workspaceA, NOW, NOW);
  db.prepare("INSERT INTO event_gc_runs(workspace_id, target_seq, executed_at) VALUES (?, 1, ?)")
    .run(IDS.workspaceA, NOW + 3);
  assert.deepEqual(
    { ...db.prepare("SELECT gc_before_seq, committed_bytes FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA) },
    { gc_before_seq: 1, committed_bytes: 3 },
  );
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM events").get()?.count, 1);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM event_receipts").get()?.count, 2);
});

test("checkpoints at the same watermark keep distinct exact identities", () => {
  const db = database();
  seedTenant(db, NOW);
  const statement = db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, r2_key,
    ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at
  ) VALUES (?, 0, ?, 1, x'a0', 1, 1, 'AES_256_GCM', 'nonce', ?, ?, 10, ?, 'sig', 'rejected', ?)`);
  statement.run(IDS.workspaceA, IDS.checkpointA, "r2/retry-a", "hash-a", IDS.deviceA, NOW);
  statement.run(IDS.workspaceA, IDS.checkpointB, "r2/retry-b", "hash-b", IDS.deviceA, NOW + 1);
  assert.equal(db.prepare(
    "SELECT COUNT(*) AS count FROM checkpoints WHERE workspace_id = ? AND through_seq = 0",
  ).get(IDS.workspaceA)?.count, 2);
});

test("recovery claim is single-use and revokes all prior devices", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 1, 'initial', 'commitment', 'wrapped', ?)`)
    .run(IDS.workspaceA, NOW);
  db.prepare(`INSERT INTO recovery_challenges(
    challenge_id, user_id, challenge_hash, expires_at, created_at
  ) VALUES (?, ?, 'hash', ?, ?)`)
    .run(IDS.challenge, IDS.userA, NOW + 10_000, NOW);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'recovered', 'ios', 'sign-new', 'wrap-new', 'token-new', 'active', 1, ?)`)
    .run(IDS.deviceA2, IDS.userA, NOW + 1);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_public_key,
    signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'recovery', 'recovery-sign-a', 'recovery-claim', '{}', 'signature', ?)`)
    .run(IDS.deviceA2, IDS.workspaceA, NOW + 1);
  db.prepare(`INSERT INTO workspace_device_keys(
    workspace_id, key_epoch, device_id, rotation_id, key_commitment,
    wrapped_key, wrapped_by_device_id, signature, created_at
  ) VALUES (?, 1, ?, 'recovery-device', 'commitment', 'device-wrapped', ?, 'signature', ?)`)
    .run(IDS.workspaceA, IDS.deviceA2, IDS.deviceA2, NOW + 1);
  db.prepare(`INSERT INTO recovery_claim_key_envelopes(
    claim_id, workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, ?, 1, 'recovery-root', 'commitment', 'replacement-wrapped', ?)`)
    .run(IDS.claim, IDS.workspaceA, NOW + 1);
  db.prepare(`INSERT INTO recovery_claims(
    claim_id, challenge_id, user_id, new_device_id, workspace_envelope_count, created_at
  ) VALUES (?, ?, ?, ?, 1, ?)`)
    .run(IDS.claim, IDS.challenge, IDS.userA, IDS.deviceA2, NOW + 2);
  assert.equal(db.prepare("SELECT status FROM devices WHERE device_id = ?").get(IDS.deviceA)?.status, "revoked");
  assert.equal(db.prepare("SELECT status FROM devices WHERE device_id = ?").get(IDS.deviceA2)?.status, "active");
  assert.equal(db.prepare("SELECT rotation_required FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.rotation_required, 1);
  assert.throws(() => db.prepare(`INSERT INTO recovery_claims(
    claim_id, challenge_id, user_id, new_device_id, workspace_envelope_count, created_at
  ) VALUES (?, ?, ?, ?, 1, ?)`)
    .run(IDS.rotationA, IDS.challenge, IDS.userA, IDS.deviceB, NOW + 3));
});

test("recovery replacement root requires the exact retained epoch set", () => {
  for (const scenario of ["missing", "extra", "wrong-commitment"] as const) {
    const db = database();
    seedTenant(db, NOW);
    db.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?, 1, 'initial', 'commitment', 'wrapped', ?)`).run(IDS.workspaceA, NOW);
    db.prepare(`INSERT INTO recovery_challenges(
      challenge_id, user_id, challenge_hash, expires_at, created_at
    ) VALUES (?, ?, 'hash', ?, ?)`).run(IDS.challenge, IDS.userA, NOW + 10_000, NOW);
    db.prepare(`INSERT INTO devices(
      device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
      token_hash, status, auth_epoch, created_at
    ) VALUES (?, ?, 'recovered', 'ios', 'sign-new', 'wrap-new', 'token-new', 'active', 1, ?)`)
      .run(IDS.deviceA2, IDS.userA, NOW + 1);
    db.prepare(`INSERT INTO device_key_attestations(
      device_id, workspace_id, attestation_type, attestor_public_key,
      signature_domain, manifest_json, signature, created_at
    ) VALUES (?, ?, 'recovery', 'recovery-sign-a', 'recovery-claim', '{}', 'signature', ?)`)
      .run(IDS.deviceA2, IDS.workspaceA, NOW + 1);
    db.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?, 1, ?, 'recovery-device', 'commitment', 'device-wrapped', ?, 'signature', ?)`)
      .run(IDS.workspaceA, IDS.deviceA2, IDS.deviceA2, NOW + 1);
    if (scenario !== "missing") {
      db.prepare(`INSERT INTO recovery_claim_key_envelopes(
        claim_id, workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
      ) VALUES (?, ?, 1, 'replacement-1', ?, 'replacement-wrapped', ?)`)
        .run(IDS.claim, IDS.workspaceA, scenario === "wrong-commitment" ? "wrong" : "commitment", NOW + 1);
    }
    if (scenario === "extra") {
      db.prepare(`INSERT INTO recovery_claim_key_envelopes(
        claim_id, workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
      ) VALUES (?, ?, 2, 'replacement-2', 'extra', 'extra-wrapped', ?)`)
        .run(IDS.claim, IDS.workspaceA, NOW + 1);
    }
    assert.throws(() => db.prepare(`INSERT INTO recovery_claims(
      claim_id, challenge_id, user_id, new_device_id, workspace_envelope_count, created_at
    ) VALUES (?, ?, ?, ?, 1, ?)`)
      .run(IDS.claim, IDS.challenge, IDS.userA, IDS.deviceA2, NOW + 2), /recovery_keyring_incomplete/);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM recovery_claims").get()?.count, 0, scenario);
    assert.equal(db.prepare("SELECT auth_epoch FROM recovery_credentials WHERE user_id = ?").get(IDS.userA)?.auth_epoch, 1);
  }
});
