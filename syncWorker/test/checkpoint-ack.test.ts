import test from "node:test";
import assert from "node:assert/strict";
import { acknowledgeCheckpoint } from "../src/api/checkpoints.ts";
import {
  canonicalJson,
  domainSeparatedMessage,
  encodeBase64Url,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import { utf8Bytes } from "../src/http.ts";
import type { CapabilityPrincipal, D1Database, D1Result, Env } from "../src/runtime.ts";
import { database, IDS, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;

test("signed invalid ack clears a corrupt R2 candidate without requiring its hash", async () => {
  const db = database();
  seedTenant(db, NOW);
  const candidateBytes = Uint8Array.of(1, 2, 3);
  const corruptBytes = Uint8Array.of(9, 9, 9);
  const candidateHash = await sha256Base64Url(candidateBytes);
  const r2Key = "checkpoints/corrupt-candidate";
  db.prepare(`INSERT INTO checkpoint_leases(
    lease_id, workspace_id, checkpoint_id, ciphertext_sha256, through_seq, key_epoch,
    uploader_device_id, capability_id, reserved_bytes, actual_byte_size, r2_key,
    status, expires_at, created_at
  ) VALUES (?, ?, ?, ?, 0, 1, ?, ?, 3, 3, ?, 'uploaded', ?, ?)`)
    .run(
      IDS.challenge,
      IDS.workspaceA,
      IDS.checkpointA,
      candidateHash,
      IDS.deviceA,
      IDS.capabilityA,
      r2Key,
      NOW + 10_000,
      NOW,
    );
  db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, r2_key,
    ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at
  ) VALUES (?, 0, ?, 1, x'a0', 1, 1, 'CHACHA20_POLY1305', 'nonce', ?, ?, 3, ?, 'sig', 'candidate', ?)`)
    .run(IDS.workspaceA, IDS.checkpointA, r2Key, candidateHash, IDS.deviceA, NOW);

  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const signingPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)),
  );
  const ack = {
    ciphertextSha256: candidateHash,
    validationVersion: 1,
    replayFromSeq: 0,
    replayedThroughSeq: 0,
    replayedEventCount: 0,
    previousStableCheckpointId: null,
    previousStableSha256: null,
    valid: false,
  };
  const manifest = canonicalJson({
    workspaceId: IDS.workspaceA,
    checkpointId: IDS.checkpointA,
    ciphertextSha256: candidateHash,
    keyEpoch: 1,
    ...ack,
  });
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    keys.privateKey,
    domainSeparatedMessage("checkpoint-ack", utf8Bytes(manifest)),
  )));

  let getCount = 0;
  let deleteCount = 0;
  const checkpoints = {
    async get(key: string) {
      getCount += 1;
      return {
        key,
        size: corruptBytes.byteLength,
        etag: "corrupt",
        customMetadata: { ciphertextSha256: candidateHash },
        body: new ReadableStream<Uint8Array>(),
        async arrayBuffer() {
          return corruptBytes.buffer.slice(0);
        },
      };
    },
    async delete(key: string) {
      assert.equal(key, r2Key);
      deleteCount += 1;
    },
  } as Env["CHECKPOINTS"];
  const env = {
    DB: sqliteD1(db),
    CHECKPOINTS: checkpoints,
  } as Env;
  const principal: CapabilityPrincipal = {
    capabilityId: IDS.capabilityA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 1,
    signingPublicKey,
    expiresAt: NOW + 10_000,
  };
  const request = new Request(
    `https://sync.test/v1/workspaces/${IDS.workspaceA}/checkpoints/${IDS.checkpointA}/ack`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...ack, signature }),
    },
  );

  const result = await acknowledgeCheckpoint(request, env, principal, IDS.checkpointA, NOW + 1) as {
    status: string;
  };

  assert.equal(result.status, "rejected");
  assert.equal(getCount, 0, "invalid ack must not try to prove that a reported-corrupt object is healthy");
  assert.equal(deleteCount, 1);
  assert.equal(
    db.prepare("SELECT candidate_checkpoint_id FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.candidate_checkpoint_id,
    null,
  );
  assert.equal(
    db.prepare("SELECT status FROM checkpoints WHERE workspace_id = ? AND checkpoint_id = ?")
      .get(IDS.workspaceA, IDS.checkpointA)?.status,
    "deleted",
  );
});

interface AckFixture {
  db: ReturnType<typeof database>;
  hash: string;
  bytes: Uint8Array;
  r2Key: string;
  principal: CapabilityPrincipal;
  checkpoints: Env["CHECKPOINTS"];
}

async function signingKeys(): Promise<CryptoKeyPair> {
  return await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]) as CryptoKeyPair;
}

async function publicKey(keys: CryptoKeyPair): Promise<string> {
  return encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)));
}

async function ackFixture(signingPublicKey: string): Promise<AckFixture> {
  const db = database();
  seedTenant(db, NOW);
  const bytes = Uint8Array.of(1, 2, 3, 4);
  const hash = await sha256Base64Url(bytes);
  const r2Key = "checkpoints/ack-race-candidate";
  db.prepare(`INSERT INTO checkpoint_leases(
    lease_id, workspace_id, checkpoint_id, ciphertext_sha256, through_seq, key_epoch,
    uploader_device_id, capability_id, reserved_bytes, actual_byte_size, r2_key,
    status, expires_at, created_at
  ) VALUES (?, ?, ?, ?, 0, 1, ?, ?, 4, 4, ?, 'uploaded', ?, ?)`).run(
    IDS.challenge,
    IDS.workspaceA,
    IDS.checkpointA,
    hash,
    IDS.deviceA,
    IDS.capabilityA,
    r2Key,
    NOW + 1_000_000,
    NOW,
  );
  db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, r2_key,
    ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at
  ) VALUES (?, 0, ?, 1, x'a0', 1, 1, 'CHACHA20_POLY1305', 'nonce', ?, ?, 4, ?, 'sig',
    'candidate', ?)`).run(
    IDS.workspaceA,
    IDS.checkpointA,
    r2Key,
    hash,
    IDS.deviceA,
    NOW,
  );
  const checkpoints = {
    async get(key: string) {
      if (key !== r2Key) return null;
      return {
        key,
        size: bytes.byteLength,
        etag: "candidate",
        customMetadata: { ciphertextSha256: hash },
        body: new ReadableStream<Uint8Array>(),
        async arrayBuffer() { return bytes.buffer.slice(0); },
      };
    },
    async delete() {},
  } as Env["CHECKPOINTS"];
  return {
    db,
    hash,
    bytes,
    r2Key,
    checkpoints,
    principal: {
      capabilityId: IDS.capabilityA,
      workspaceId: IDS.workspaceA,
      deviceId: IDS.deviceA,
      userId: IDS.userA,
      role: "owner",
      deviceAuthEpoch: 1,
      membershipAuthEpoch: 1,
      keyEpoch: 1,
      signingPublicKey,
      expiresAt: NOW + 1_000_000,
    },
  };
}

async function signedAckRequest(
  fixture: AckFixture,
  keys: CryptoKeyPair,
  valid: boolean,
): Promise<Request> {
  const ack = {
    ciphertextSha256: fixture.hash,
    validationVersion: 1,
    replayFromSeq: 0,
    replayedThroughSeq: 0,
    replayedEventCount: 0,
    previousStableCheckpointId: null,
    previousStableSha256: null,
    valid,
  };
  const manifest = canonicalJson({
    workspaceId: IDS.workspaceA,
    checkpointId: IDS.checkpointA,
    ciphertextSha256: fixture.hash,
    keyEpoch: 1,
    validationVersion: 1,
    replayFromSeq: 0,
    replayedThroughSeq: 0,
    replayedEventCount: 0,
    previousStableCheckpointId: null,
    previousStableSha256: null,
    valid,
  });
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    keys.privateKey,
    domainSeparatedMessage("checkpoint-ack", utf8Bytes(manifest)),
  )));
  return new Request("https://sync.test/checkpoints/ack", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...ack, signature }),
  });
}

function databaseWithBatchHook(
  db: ReturnType<typeof database>,
  beforeBatch: () => void,
  onResults: (results: D1Result[]) => void = () => {},
): D1Database {
  const delegate = sqliteD1(db);
  return {
    prepare: delegate.prepare,
    exec: delegate.exec,
    async batch(statements) {
      beforeBatch();
      const results = await delegate.batch(statements);
      onResults(results);
      return results;
    },
  };
}

function assertCandidateUnchanged(fixture: AckFixture): void {
  assert.deepEqual(
    { ...fixture.db.prepare(`SELECT status FROM checkpoints
      WHERE workspace_id = ? AND checkpoint_id = ?`).get(IDS.workspaceA, IDS.checkpointA) },
    { status: "candidate" },
  );
  assert.equal(
    fixture.db.prepare("SELECT candidate_checkpoint_id FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.candidate_checkpoint_id,
    IDS.checkpointA,
  );
  assert.equal(fixture.db.prepare("SELECT COUNT(*) AS count FROM checkpoint_acks").get()?.count, 0);
  assert.equal(fixture.db.prepare("SELECT COUNT(*) AS count FROM checkpoint_promotions").get()?.count, 0);
  assert.equal(
    fixture.db.prepare("SELECT stable_checkpoint_id FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.stable_checkpoint_id,
    null,
  );
}

const ACK_COMMIT_RACES: ReadonlyArray<{
  name: string;
  mutate: (db: ReturnType<typeof database>, now: number) => void;
}> = [
  {
    name: "capability revocation",
    mutate: (db, now) => { db.prepare("UPDATE workspace_capabilities SET revoked_at = ? WHERE capability_id = ?")
      .run(now, IDS.capabilityA); },
  },
  {
    name: "capability expiry",
    mutate: (db, now) => { db.prepare("UPDATE workspace_capabilities SET expires_at = ? WHERE capability_id = ?")
      .run(now, IDS.capabilityA); },
  },
  {
    name: "device auth epoch advance",
    mutate: (db) => { db.prepare("UPDATE devices SET auth_epoch = auth_epoch + 1 WHERE device_id = ?")
      .run(IDS.deviceA); },
  },
  {
    name: "device revocation",
    mutate: (db, now) => { db.prepare("UPDATE devices SET status = 'revoked', revoked_at = ? WHERE device_id = ?")
      .run(now, IDS.deviceA); },
  },
  {
    name: "user suspension",
    mutate: (db) => { db.prepare("UPDATE users SET status = 'suspended' WHERE user_id = ?").run(IDS.userA); },
  },
  {
    name: "membership auth epoch advance",
    mutate: (db) => { db.prepare(`UPDATE memberships SET auth_epoch = auth_epoch + 1
      WHERE workspace_id = ? AND user_id = ?`).run(IDS.workspaceA, IDS.userA); },
  },
  {
    name: "membership revocation",
    mutate: (db) => { db.prepare(`UPDATE memberships SET status = 'revoked'
      WHERE workspace_id = ? AND user_id = ?`).run(IDS.workspaceA, IDS.userA); },
  },
  {
    name: "workspace suspension",
    mutate: (db) => { db.prepare("UPDATE workspaces SET status = 'suspended' WHERE workspace_id = ?")
      .run(IDS.workspaceA); },
  },
  {
    name: "rotation requirement",
    mutate: (db) => { db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?")
      .run(IDS.workspaceA); },
  },
  {
    name: "key epoch rotation",
    mutate: (db) => { db.prepare("UPDATE workspaces SET active_key_epoch = 2 WHERE workspace_id = ?")
      .run(IDS.workspaceA); },
  },
];

test("positive and negative ack commit CAS rejects every post-verification authorization race", async (t) => {
  const keys = await signingKeys();
  const signingPublicKey = await publicKey(keys);
  for (const valid of [true, false]) {
    for (const race of ACK_COMMIT_RACES) {
      await t.test(`${valid ? "positive" : "negative"}: ${race.name}`, async () => {
        const fixture = await ackFixture(signingPublicKey);
        const now = valid ? NOW + 600_001 : NOW + 1;
        let batches = 0;
        const env = {
          DB: databaseWithBatchHook(fixture.db, () => {
            batches += 1;
            race.mutate(fixture.db, now);
          }),
          CHECKPOINTS: fixture.checkpoints,
        } as Env;
        await assert.rejects(
          acknowledgeCheckpoint(
            await signedAckRequest(fixture, keys, valid),
            env,
            fixture.principal,
            IDS.checkpointA,
            now,
          ),
          (error: unknown) => (error as { code?: string }).code === "checkpoint_ack_authorization_changed",
        );
        assert.equal(batches, 1);
        assertCandidateUnchanged(fixture);
      });
    }
  }
});

test("single active device promotes only after grace and reports atomicBatch changes", async () => {
  const keys = await signingKeys();
  const fixture = await ackFixture(await publicKey(keys));
  let batchChanges: number[] = [];
  const env = {
    DB: databaseWithBatchHook(fixture.db, () => {}, (results) => {
      batchChanges = results.map((result) => result.meta?.changes ?? 0);
    }),
    CHECKPOINTS: fixture.checkpoints,
    CHECKPOINT_SELF_ACK_GRACE_SECONDS: "10",
  } as Env;

  await assert.rejects(
    acknowledgeCheckpoint(
      await signedAckRequest(fixture, keys, true),
      env,
      fixture.principal,
      IDS.checkpointA,
      NOW + 9_999,
    ),
    (error: unknown) => (error as { code?: string }).code === "checkpoint_self_ack_grace",
  );
  assertCandidateUnchanged(fixture);

  const result = await acknowledgeCheckpoint(
    await signedAckRequest(fixture, keys, true),
    env,
    fixture.principal,
    IDS.checkpointA,
    NOW + 10_000,
  ) as { status: string };
  assert.equal(result.status, "stable");
  assert.deepEqual(batchChanges, [1, 1], "Node SQLite reports one direct row for ack and promotion");
  assert.equal(fixture.db.prepare("SELECT COUNT(*) AS count FROM checkpoint_acks").get()?.count, 1);
  assert.equal(fixture.db.prepare("SELECT COUNT(*) AS count FROM checkpoint_promotions").get()?.count, 1);
});

test("a validator joining after the self-ack precheck blocks promotion without leaving an ack", async () => {
  const keys = await signingKeys();
  const fixture = await ackFixture(await publicKey(keys));
  const env = {
    DB: databaseWithBatchHook(fixture.db, () => dbAddValidator(fixture.db, "joined-validator-sign")),
    CHECKPOINTS: fixture.checkpoints,
  } as Env;

  await assert.rejects(
    acknowledgeCheckpoint(
      await signedAckRequest(fixture, keys, true),
      env,
      fixture.principal,
      IDS.checkpointA,
      NOW + 600_001,
    ),
    (error: unknown) => (error as { code?: string }).code === "checkpoint_ack_authorization_changed",
  );

  assertCandidateUnchanged(fixture);
});

test("multi-device candidate requires and accepts an independent active validator", async () => {
  const uploaderKeys = await signingKeys();
  const fixture = await ackFixture(await publicKey(uploaderKeys));
  const validatorKeys = await signingKeys();
  const validatorPublicKey = await publicKey(validatorKeys);
  dbAddValidator(fixture.db, validatorPublicKey);
  const validatorPrincipal: CapabilityPrincipal = {
    ...fixture.principal,
    capabilityId: IDS.capabilityB,
    deviceId: IDS.deviceA2,
    signingPublicKey: validatorPublicKey,
  };
  const env = { DB: sqliteD1(fixture.db), CHECKPOINTS: fixture.checkpoints } as Env;

  await assert.rejects(
    acknowledgeCheckpoint(
      await signedAckRequest(fixture, uploaderKeys, true),
      env,
      fixture.principal,
      IDS.checkpointA,
      NOW + 600_001,
    ),
    (error: unknown) => (error as { code?: string }).code === "independent_validator_required",
  );
  assertCandidateUnchanged(fixture);

  const result = await acknowledgeCheckpoint(
    await signedAckRequest(fixture, validatorKeys, true),
    env,
    validatorPrincipal,
    IDS.checkpointA,
    NOW + 1,
  ) as { status: string };
  assert.equal(result.status, "stable");
  assert.equal(
    fixture.db.prepare("SELECT validator_device_id FROM checkpoint_acks").get()?.validator_device_id,
    IDS.deviceA2,
  );
});

function dbAddValidator(db: ReturnType<typeof database>, signingPublicKey: string): void {
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'Independent validator', 'windows', ?, 'validator-wrap', 'validator-token',
    'active', 1, ?)`).run(IDS.deviceA2, IDS.userA, signingPublicKey, NOW);
  db.prepare(`INSERT INTO workspace_capabilities(
    capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
    membership_auth_epoch, key_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 'validator-capability', 1, 1, 1, ?, ?)`).run(
    IDS.capabilityB,
    IDS.workspaceA,
    IDS.deviceA2,
    NOW + 1_000_000,
    NOW,
  );
}

test("suspended users do not disable the remaining device's legal single-device grace", async () => {
  const keys = await signingKeys();
  const fixture = await ackFixture(await publicKey(keys));
  fixture.db.prepare(`INSERT INTO users(
    user_id, display_name, instance_role, status, quota_profile_id, created_at
  ) VALUES (?, 'Suspended', 'user', 'suspended', 'private-default', ?)`).run(IDS.userB, NOW);
  fixture.db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'Suspended user device', 'android', 'suspended-sign', 'suspended-wrap',
    'suspended-token', 'active', 1, ?)`).run(IDS.deviceB, IDS.userB, NOW);
  fixture.db.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
    VALUES (?, ?, 'reader', 'active', 1)`).run(IDS.workspaceA, IDS.userB);
  const env = {
    DB: sqliteD1(fixture.db),
    CHECKPOINTS: fixture.checkpoints,
    CHECKPOINT_SELF_ACK_GRACE_SECONDS: "10",
  } as Env;

  const result = await acknowledgeCheckpoint(
    await signedAckRequest(fixture, keys, true),
    env,
    fixture.principal,
    IDS.checkpointA,
    NOW + 10_000,
  ) as { status: string };

  assert.equal(result.status, "stable");
});
