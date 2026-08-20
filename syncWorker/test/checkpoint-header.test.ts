import test from "node:test";
import assert from "node:assert/strict";
import { commitCheckpoint, uploadCheckpoint } from "../src/api/checkpoints.ts";
import { encodeBase64Url, sha256Base64Url } from "../src/crypto/base.ts";
import { encodeCanonicalCbor } from "../src/crypto/cbor.ts";
import { ApiError } from "../src/http.ts";
import { CHECKPOINT_COMPRESSION, LIMITS } from "../src/protocol.ts";
import type { CapabilityPrincipal, D1Database, Env } from "../src/runtime.ts";
import {
  COMMIT_CHECKPOINT_SQL,
  CREATE_CHECKPOINT_LEASE_SQL,
  MARK_CHECKPOINT_UPLOADED_SQL,
} from "../src/storage/sql.ts";
import { database, IDS, numberedGet, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;

const principal: CapabilityPrincipal = {
  capabilityId: IDS.capabilityA,
  workspaceId: IDS.workspaceA,
  deviceId: IDS.deviceA,
  userId: IDS.userA,
  role: "owner",
  deviceAuthEpoch: 1,
  membershipAuthEpoch: 1,
  keyEpoch: 1,
  signingPublicKey: encodeBase64Url(new Uint8Array(32)),
  expiresAt: 2_000_000,
};

function checkpointHeaderValues(overrides: Record<string, string | number> = {}) {
  return {
    envelopeVersion: 1,
    protocolVersion: 1,
    schemaVersion: 1,
    cipherSuite: "CHACHA20_POLY1305",
    nonce: encodeBase64Url(new Uint8Array(12)),
    instanceId: IDS.instance,
    workspaceId: IDS.workspaceA,
    checkpointId: IDS.checkpointA,
    deviceId: IDS.deviceA,
    throughWorkspaceSeq: 0,
    keyEpoch: 1,
    previousStableCheckpointHash: "",
    stateFormat: "sync-state-v1",
    compression: CHECKPOINT_COMPRESSION,
    uncompressedSize: 128,
    ...overrides,
  };
}

async function uploadRequest(header: Record<string, string | number>): Promise<Request> {
  const ciphertext = Uint8Array.of(1, 2, 3);
  return new Request(`https://sync.test/v1/workspaces/${IDS.workspaceA}/checkpoints/${IDS.checkpointA}`, {
    method: "PUT",
    headers: {
      "X-Shinsou-Header-Cbor": encodeBase64Url(encodeCanonicalCbor(header)),
      "X-Shinsou-Ciphertext-Sha256": await sha256Base64Url(ciphertext),
      "X-Shinsou-Device-Signature": "",
    },
    body: ciphertext,
  });
}

test("checkpoint upload rejects unsafe compression metadata before any storage access", async () => {
  let storageAccessed = false;
  const unavailable = () => {
    storageAccessed = true;
    throw new Error("Storage must not be reached for an invalid authenticated header");
  };
  const env = {
    DB: { prepare: unavailable },
    CHECKPOINTS: { put: unavailable, head: unavailable, get: unavailable, delete: unavailable },
    INSTANCE_ID: IDS.instance,
  } as unknown as Env;

  for (const [header, expectedCode] of [
    [checkpointHeaderValues({ compression: "NONE" }), "unsupported_checkpoint_compression"],
    [checkpointHeaderValues({ uncompressedSize: LIMITS.checkpointUncompressed + 1 }),
      "invalid_header_uncompressedSize"],
  ] as const) {
    await assert.rejects(
      uploadCheckpoint(await uploadRequest(header), env, principal, IDS.checkpointA),
      (error: unknown) => error instanceof ApiError && error.code === expectedCode,
    );
  }
  assert.equal(storageAccessed, false);
});

test("checkpoint upload rejects protocol and schema writers outside server ranges before storage", async () => {
  let storageAccessed = false;
  const unavailable = () => {
    storageAccessed = true;
    throw new Error("Storage must not be reached for an incompatible writer");
  };
  const env = {
    DB: { prepare: unavailable },
    CHECKPOINTS: { put: unavailable, head: unavailable, get: unavailable, delete: unavailable },
    INSTANCE_ID: IDS.instance,
  } as unknown as Env;

  for (const [header, expectedCode] of [
    [checkpointHeaderValues({ protocolVersion: 2 }), "protocol_write_version_incompatible"],
    [checkpointHeaderValues({ schemaVersion: 2 }), "schema_write_version_incompatible"],
  ] as const) {
    await assert.rejects(
      uploadCheckpoint(await uploadRequest(header), env, principal, IDS.checkpointA),
      (error: unknown) => error instanceof ApiError && error.status === 409 && error.code === expectedCode,
    );
  }
  assert.equal(storageAccessed, false);
});

test("checkpoint commit reparses R2 metadata and rejects incompatible writers before candidate SQL", async () => {
  const ciphertext = Uint8Array.of(1, 2, 3);
  const ciphertextSha256 = await sha256Base64Url(ciphertext);

  for (const [header, expectedCode] of [
    [checkpointHeaderValues({ protocolVersion: 2 }), "protocol_write_version_incompatible"],
    [checkpointHeaderValues({ schemaVersion: 2 }), "schema_write_version_incompatible"],
  ] as const) {
    const db = database();
    seedTenant(db, NOW);
    assert.ok(numberedGet(
      db,
      CREATE_CHECKPOINT_LEASE_SQL,
      IDS.challenge,
      IDS.workspaceA,
      IDS.checkpointA,
      ciphertextSha256,
      100,
      NOW + 10_000,
      NOW + 1,
      IDS.capabilityA,
      0,
    ));
    assert.ok(numberedGet(
      db,
      MARK_CHECKPOINT_UPLOADED_SQL,
      IDS.workspaceA,
      IDS.checkpointA,
      ciphertextSha256,
      ciphertext.byteLength,
      "safe/r2/key",
      NOW + 2,
      IDS.deviceA,
    ));

    const baseD1 = sqliteD1(db);
    let candidateSqlPrepared = false;
    const observedD1: D1Database = {
      prepare(sql) {
        if (sql === COMMIT_CHECKPOINT_SQL) candidateSqlPrepared = true;
        return baseD1.prepare(sql);
      },
      batch(statements) {
        return baseD1.batch(statements);
      },
      exec(sql) {
        return baseD1.exec(sql);
      },
    };
    const headerCbor = encodeBase64Url(encodeCanonicalCbor(header));
    const env = {
      DB: observedD1,
      CHECKPOINTS: {
        async head(key: string) {
          assert.equal(key, "safe/r2/key");
          return {
            key,
            size: ciphertext.byteLength,
            etag: "fixture-etag",
            customMetadata: {
              workspaceId: IDS.workspaceA,
              checkpointId: IDS.checkpointA,
              ciphertextSha256,
              headerCbor,
              deviceSignature: "fixture-signature",
            },
          };
        },
      },
      INSTANCE_ID: IDS.instance,
    } as unknown as Env;
    const request = new Request("https://sync.test/checkpoints/commit", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ciphertextSha256 }),
    });

    await assert.rejects(
      commitCheckpoint(request, env, principal, IDS.checkpointA, NOW + 3),
      (error: unknown) => error instanceof ApiError && error.status === 409 && error.code === expectedCode,
    );
    assert.equal(candidateSqlPrepared, false, "incompatible metadata must not reach candidate insertion");
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM checkpoints").get()?.count, 0);
  }
});
