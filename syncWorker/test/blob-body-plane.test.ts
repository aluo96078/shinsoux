import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/worker.ts";
import {
  acknowledgeBlobTombstone,
  blobTombstoneAckSignatureMessage,
  blobTombstoneRevivalSignatureMessage,
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
} from "../src/api/blobs.ts";
import {
  encodeBase64Url,
  hashSecret,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import { ApiError } from "../src/http.ts";
import type {
  CapabilityPrincipal,
  Env,
  ExecutionContext,
  R2Bucket,
  R2ObjectBody,
} from "../src/runtime.ts";
import {
  database,
  IDS,
  seedSecondTenant,
  seedTenant,
  sqliteD1,
} from "./helpers.ts";

const NOW = Date.now();
const TOKEN_PEPPER = "p".repeat(32);
const CAPABILITY_TOKEN = "capability-token-abcdefghijklmnopqrstuvwxyz";
const BLOB_ID = "00000000-0000-4000-8000-000000000030";
const MANIFEST_ID = "00000000-0000-4000-8000-000000000031";
const TOMBSTONE_ID = "00000000-0000-4000-8000-000000000032";
const STABLE_CHECKPOINT_ID = "00000000-0000-4000-8000-000000000033";
const SECOND_ROTATION_ID = "00000000-0000-4000-8000-000000000034";
const SECOND_TOMBSTONE_ID = "00000000-0000-4000-8000-000000000035";
const SECOND_STABLE_CHECKPOINT_ID = "00000000-0000-4000-8000-000000000036";
const THIRD_TOMBSTONE_ID = "00000000-0000-4000-8000-000000000037";
const REUPLOAD_MANIFEST_ID = "00000000-0000-4000-8000-000000000038";
const THIRD_STABLE_CHECKPOINT_ID = "00000000-0000-4000-8000-00000000003a";
const ORPHAN_BLOB_ID = "00000000-0000-4000-8000-00000000003b";
const ORPHAN_MANIFEST_ID = "00000000-0000-4000-8000-00000000003c";
const NEXT_BLOB_ID = "00000000-0000-4000-8000-00000000003d";
const NEXT_MANIFEST_ID = "00000000-0000-4000-8000-00000000003e";
const LARGE_ORPHAN_BLOB_ID = "00000000-0000-4000-8000-00000000003f";
const LARGE_ORPHAN_MANIFEST_ID = "00000000-0000-4000-8000-000000000040";

interface StoredObject {
  bytes: Uint8Array;
  customMetadata?: Record<string, string>;
  etag: string;
}

class MemoryR2 implements R2Bucket {
  readonly objects = new Map<string, StoredObject>();
  failOneDelete = false;

  async get(key: string): Promise<R2ObjectBody | null> {
    const stored = this.objects.get(key);
    if (!stored) return null;
    const bytes = stored.bytes.slice();
    return {
      key,
      size: bytes.byteLength,
      etag: stored.etag,
      customMetadata: stored.customMetadata ? { ...stored.customMetadata } : undefined,
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(bytes.slice());
          controller.close();
        },
      }),
      async arrayBuffer() {
        return bytes.slice().buffer;
      },
    };
  }

  async head(key: string) {
    const stored = this.objects.get(key);
    if (!stored) return null;
    return {
      key,
      size: stored.bytes.byteLength,
      etag: stored.etag,
      customMetadata: stored.customMetadata ? { ...stored.customMetadata } : undefined,
    };
  }

  async put(
    key: string,
    value: ArrayBuffer | Uint8Array | ReadableStream<Uint8Array>,
    options?: { customMetadata?: Record<string, string>; onlyIf?: { etagDoesNotMatch?: string } },
  ) {
    if (options?.onlyIf?.etagDoesNotMatch === "*" && this.objects.has(key)) return null;
    if (value instanceof ReadableStream) throw new Error("MemoryR2 fixture expects bounded bytes");
    const bytes = value instanceof Uint8Array
      ? value.slice()
      : new Uint8Array(value.slice(0));
    const stored = {
      bytes,
      customMetadata: options?.customMetadata ? { ...options.customMetadata } : undefined,
      etag: `etag-${this.objects.size + 1}`,
    };
    this.objects.set(key, stored);
    return { key, size: bytes.byteLength, etag: stored.etag };
  }

  async delete(key: string): Promise<void> {
    if (this.failOneDelete) {
      this.failOneDelete = false;
      throw new Error("injected R2 deletion interruption");
    }
    this.objects.delete(key);
  }
}

function environment(db: ReturnType<typeof database>, blobs = new MemoryR2()): Env {
  return {
    DB: sqliteD1(db),
    CHECKPOINTS: new MemoryR2(),
    BLOBS: blobs,
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER,
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    PROTOCOL_VERSION: "2",
    MIN_READER_VERSION: "2",
    MIN_WRITER_VERSION: "2",
    SCHEMA_VERSION: "2",
    MIN_SCHEMA_READER_VERSION: "2",
    MIN_SCHEMA_WRITER_VERSION: "2",
    REALTIME_ENABLED: "false",
    BLOB_UPLOAD_SESSION_TTL_SECONDS: "3600",
    BLOB_GC_SAFETY_SECONDS: "1",
  };
}

function principal(signingPublicKey = encodeBase64Url(new Uint8Array(32))): CapabilityPrincipal {
  return {
    capabilityId: IDS.capabilityA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 1,
    signingPublicKey,
    expiresAt: NOW + 10_000_000,
  };
}

function jsonRequest(path: string, value: unknown): Request {
  return new Request(`https://sync.test${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  });
}

function canonicalFields(...values: string[]): Uint8Array {
  const encoded = values.map((value) => new TextEncoder().encode(value));
  const output = new Uint8Array(encoded.reduce((sum, value) => sum + 4 + value.byteLength, 0));
  let offset = 0;
  for (const value of encoded) {
    new DataView(output.buffer).setUint32(offset, value.byteLength, false);
    offset += 4;
    output.set(value, offset);
    offset += value.byteLength;
  }
  return output;
}

async function envelope(
  blobId: string,
  keyEpoch: number,
  seed: number,
  previousEnvelopeSha256Base64Url: string | null,
) {
  const nonceBase64Url = encodeBase64Url(new Uint8Array(12).fill(seed));
  const wrappedDekBase64Url = encodeBase64Url(new Uint8Array(48).fill(seed + 1));
  const envelopeSha256Base64Url = await sha256Base64Url(canonicalFields(
    "shinsou-blob-dek-envelope-hash-v2",
    blobId,
    String(keyEpoch),
    nonceBase64Url,
    wrappedDekBase64Url,
    previousEnvelopeSha256Base64Url ?? "",
  ));
  return {
    schemaVersion: 1,
    blobId,
    keyEpoch,
    cipherSuite: "CHACHA20_POLY1305",
    nonceBase64Url,
    wrappedDekBase64Url,
    envelopeSha256Base64Url,
    previousEnvelopeSha256Base64Url,
  };
}

async function commitFixture(
  env: Env,
  actor: CapabilityPrincipal,
  options: {
    blobId?: string;
    manifestId?: string;
    at?: number;
    seed?: number;
    chunkByteSize?: number;
    manifestByteSize?: number;
  } = {},
) {
  const blobId = options.blobId ?? BLOB_ID;
  const manifestId = options.manifestId ?? MANIFEST_ID;
  const at = options.at ?? NOW;
  const seed = options.seed ?? 0;
  const chunkCiphertext = new Uint8Array(options.chunkByteSize ?? 48)
    .map((_, index) => (index + 1 + seed) & 0xff);
  const privateManifestCiphertext = new Uint8Array(options.manifestByteSize ?? 32)
    .map((_, index) => (100 + index + seed) & 0xff);
  const chunkHash = await sha256Base64Url(chunkCiphertext);
  const manifestHash = await sha256Base64Url(privateManifestCiphertext);
  const initialEnvelope = await envelope(blobId, actor.keyEpoch, (7 + seed) & 0xff, null);
  const reservation = {
    protocolVersion: 2,
    schemaVersion: 2,
    blobId,
    manifestId,
    keyEpoch: actor.keyEpoch,
    chunkSizeBytes: 64 * 1024,
    expectedBodyCiphertextBytes: chunkCiphertext.byteLength,
    expectedManifestCiphertextBytes: privateManifestCiphertext.byteLength,
    manifestCiphertextSha256Base64Url: manifestHash,
    chunks: [{
      index: 0,
      ciphertextByteSize: chunkCiphertext.byteLength,
      ciphertextSha256Base64Url: chunkHash,
    }],
    initialDekEnvelope: initialEnvelope,
  };
  const session = await createBlobUploadSession(
    jsonRequest("/reservation", reservation),
    env,
    actor,
    at,
  ) as { sessionId: string };
  const manifestNonce = encodeBase64Url(new Uint8Array(12).fill((12 + seed) & 0xff));
  const commit = {
    protocolVersion: 2,
    schemaVersion: 2,
    encryptedPrivateManifest: {
      nonceBase64Url: manifestNonce,
      ciphertextBase64Url: encodeBase64Url(privateManifestCiphertext),
      ciphertextSha256Base64Url: manifestHash,
      ciphertextByteSize: privateManifestCiphertext.byteLength,
    },
  };
  return {
    session,
    reservation,
    commit,
    initialEnvelope,
    chunkCiphertext,
    chunkHash,
    privateManifestCiphertext,
    blobId,
    at,
  };
}

function databaseWithBeforeFirstBatch(
  base: Env["DB"],
  beforeBatch: () => void,
): Env["DB"] {
  let pending = true;
  return {
    prepare: base.prepare.bind(base),
    exec: base.exec.bind(base),
    async batch(statements) {
      if (pending) {
        pending = false;
        beforeBatch();
      }
      return base.batch(statements);
    },
  };
}

function stageKeyRotation(
  db: ReturnType<typeof database>,
  rotationId: string,
  fromEpoch: number,
  toEpoch: number,
  at: number,
): void {
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  db.prepare(`INSERT INTO key_rotations(
    rotation_id, workspace_id, from_epoch, to_epoch, proposer_device_id,
    proposer_device_auth_epoch, membership_auth_epoch, key_commitment, manifest_cbor,
    manifest_signature, status, lease_expires_at, created_at
  ) VALUES (?, ?, ?, ?, ?, 1, 1, 'commitment', 'manifest', 'signature', 'leased', ?, ?)`)
    .run(rotationId, IDS.workspaceA, fromEpoch, toEpoch, IDS.deviceA, at + 10_000, at);
  db.prepare(`INSERT INTO workspace_device_keys(
    workspace_id, key_epoch, device_id, rotation_id, key_commitment,
    wrapped_key, wrapped_by_device_id, signature, created_at
  ) VALUES (?, ?, ?, ?, 'commitment', 'wrapped', ?, 'signature', ?)`)
    .run(IDS.workspaceA, toEpoch, IDS.deviceA, rotationId, IDS.deviceA, at + 1);
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, ?, ?, 'commitment', 'recovery-wrapped', ?)`)
    .run(IDS.workspaceA, toEpoch, rotationId, at + 1);
}

async function uploadAndCommit(
  env: Env,
  actor: CapabilityPrincipal,
  fixture: Awaited<ReturnType<typeof commitFixture>>,
) {
  const chunkRequest = new Request("https://sync.test/chunk", {
    method: "PUT",
    headers: {
      "Content-Type": "application/octet-stream",
      "X-Shinsou-Ciphertext-Sha256": fixture.chunkHash,
    },
    body: fixture.chunkCiphertext,
  });
  await uploadBlobChunk(chunkRequest, env, actor, fixture.session.sessionId, 0, fixture.at + 1);
  return commitBlobUpload(
    jsonRequest("/commit", fixture.commit),
    env,
    actor,
    fixture.session.sessionId,
    fixture.at + 2,
  );
}

function seedStableCheckpoint(
  db: ReturnType<typeof database>,
  ciphertextSha256: string,
  keyEpoch: number,
  throughSeq = 0,
  checkpointId = STABLE_CHECKPOINT_ID,
): void {
  db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, previous_stable_sha256,
    r2_key, ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at, promoted_at
  ) VALUES (?, ?, ?, ?, ?, 1, 2, 'CHACHA20_POLY1305', ?, NULL, ?, ?, 1, ?, ?,
            'stable', ?, ?)`)
    .run(
      IDS.workspaceA,
      throughSeq,
      checkpointId,
      keyEpoch,
      new Uint8Array([1]),
      encodeBase64Url(new Uint8Array(12).fill(4)),
      `checkpoint/${checkpointId}`,
      ciphertextSha256,
      IDS.deviceA,
      "checkpoint-signature",
      NOW + 10,
      NOW + 10,
    );
  db.prepare("UPDATE workspaces SET stable_checkpoint_id = ? WHERE workspace_id = ?")
    .run(checkpointId, IDS.workspaceA);
}

test("v2 upload is resumable and exact, then manifest/chunk download stays tenant-bound", async () => {
  const db = database();
  seedTenant(db, NOW);
  seedSecondTenant(db, NOW);
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  const actor = principal();
  const fixture = await commitFixture(env, actor);

  const resumed = await createBlobUploadSession(
    jsonRequest("/reservation", fixture.reservation),
    env,
    actor,
    NOW + 1,
  ) as { sessionId: string };
  assert.equal(resumed.sessionId, fixture.session.sessionId);
  await assert.rejects(
    commitBlobUpload(
      jsonRequest("/commit", fixture.commit),
      env,
      actor,
      fixture.session.sessionId,
      NOW + 2,
    ),
    (error: unknown) => error instanceof ApiError && error.code === "blob_chunks_incomplete",
  );

  const receipt = await uploadAndCommit(env, actor, fixture) as {
    receiptId: string;
    manifest: { blobId: string };
  };
  assert.equal(receipt.manifest.blobId, BLOB_ID);
  const uploadStatus = await getBlobUploadSession(
    env,
    actor,
    fixture.session.sessionId,
    NOW + 3,
  ) as { receivedChunks: unknown[] };
  assert.equal(uploadStatus.receivedChunks.length, 1);
  assert.deepEqual(
    await commitBlobUpload(
      jsonRequest("/commit", fixture.commit),
      env,
      actor,
      fixture.session.sessionId,
      NOW + 4,
    ),
    receipt,
  );

  const manifest = await downloadBlobManifest(env, actor, BLOB_ID) as {
    remote: { commitReceiptId: string };
    chunks: unknown[];
    encryptedPrivateManifest: { ciphertextBase64Url: string };
    dekEnvelopes: unknown[];
  };
  assert.equal(manifest.remote.commitReceiptId, receipt.receiptId);
  assert.equal(manifest.chunks.length, 1);
  assert.equal(manifest.dekEnvelopes.length, 1);
  assert.equal(
    manifest.encryptedPrivateManifest.ciphertextBase64Url,
    encodeBase64Url(fixture.privateManifestCiphertext),
  );
  const chunkResponse = await downloadBlobChunk(env, actor, BLOB_ID, 0);
  assert.equal(chunkResponse.headers.get("X-Shinsou-Ciphertext-Sha256"), fixture.chunkHash);
  assert.deepEqual(new Uint8Array(await chunkResponse.arrayBuffer()), fixture.chunkCiphertext);

  const foreign: CapabilityPrincipal = {
    ...actor,
    capabilityId: IDS.capabilityB,
    workspaceId: IDS.workspaceB,
    deviceId: IDS.deviceB,
    userId: IDS.userB,
  };
  await assert.rejects(
    downloadBlobManifest(env, foreign, BLOB_ID),
    (error: unknown) => error instanceof ApiError && error.status === 404,
  );
  assert.deepEqual([...blobs.objects.keys()].sort(), [
    `workspaces/${IDS.workspaceA}/content/v2/blobs/${BLOB_ID}/manifests/${MANIFEST_ID}.bin`,
    `workspaces/${IDS.workspaceA}/content/v2/blobs/${BLOB_ID}/manifests/${MANIFEST_ID}/chunks/0.bin`,
  ].sort());
});

test("stable v2 evidence gates re-wrap; canonical signed acks and recovery boundary gate retryable GC", async () => {
  const db = database();
  const keyPair = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const publicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", keyPair.publicKey)));
  seedTenant(db, NOW, publicKey);
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  const actor = principal(publicKey);
  const fixture = await commitFixture(env, actor);
  await uploadAndCommit(env, actor, fixture);

  db.prepare("UPDATE workspaces SET active_key_epoch = 2 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  db.prepare("UPDATE workspace_capabilities SET key_epoch = 2 WHERE capability_id = ?")
    .run(IDS.capabilityA);
  const currentActor = { ...actor, keyEpoch: 2 };
  await assert.rejects(
    downloadBlobManifest(env, currentActor, BLOB_ID),
    (error: unknown) => error instanceof ApiError && error.code === "blob_envelope_rewrap_required",
  );

  const checkpointHash = encodeBase64Url(new Uint8Array(32).fill(91));
  const currentEnvelope = await envelope(
    BLOB_ID,
    2,
    20,
    fixture.initialEnvelope.envelopeSha256Base64Url,
  );
  const rewrap = {
    protocolVersion: 2,
    schemaVersion: 2,
    manifestId: MANIFEST_ID,
    envelope: currentEnvelope,
    checkpointEvidence: {
      checkpointId: STABLE_CHECKPOINT_ID,
      checkpointCiphertextSha256Base64Url: checkpointHash,
      throughWorkspaceSeq: 0,
    },
  };
  await assert.rejects(
    rewrapBlobEnvelope(jsonRequest("/rewrap", rewrap), env, currentActor, BLOB_ID, NOW + 20),
    (error: unknown) => error instanceof ApiError && error.code === "stable_checkpoint_evidence_invalid",
  );
  seedStableCheckpoint(db, checkpointHash, 2);
  assert.deepEqual(
    await rewrapBlobEnvelope(
      jsonRequest("/rewrap", rewrap),
      env,
      currentActor,
      BLOB_ID,
      NOW + 21,
    ),
    currentEnvelope,
  );
  assert.deepEqual(
    await getBlobEnvelope(env, currentActor, BLOB_ID, 2),
    {
      protocolVersion: 2,
      schemaVersion: 2,
      manifestId: MANIFEST_ID,
      envelope: currentEnvelope,
      checkpointEvidence: rewrap.checkpointEvidence,
      status: "current",
    },
  );
  await assert.rejects(
    getBlobEnvelope(env, currentActor, BLOB_ID, 3),
    (error: unknown) => error instanceof ApiError && error.code === "invalid_blob_envelope_epoch",
  );
  assert.deepEqual(
    await rewrapBlobEnvelope(
      jsonRequest("/rewrap", rewrap),
      env,
      currentActor,
      BLOB_ID,
      NOW + 22,
    ),
    currentEnvelope,
  );
  await assert.rejects(
    rewrapBlobEnvelope(
      jsonRequest("/rewrap", { ...rewrap, manifestId: TOMBSTONE_ID }),
      env,
      currentActor,
      BLOB_ID,
      NOW + 22,
    ),
    (error: unknown) => error instanceof ApiError && error.code === "blob_manifest_identity_mismatch",
  );
  const rewrappedManifest = await downloadBlobManifest(
    env,
    currentActor,
    BLOB_ID,
  ) as { dekEnvelopes: unknown[] };
  assert.equal(rewrappedManifest.dekEnvelopes.length, 2);

  const tombstone = {
    protocolVersion: 2,
    schemaVersion: 2,
    tombstoneId: TOMBSTONE_ID,
    blobId: BLOB_ID,
    manifestId: MANIFEST_ID,
    throughWorkspaceSeq: 0,
    createdAtEpochMillis: NOW + 22,
  };
  await createBlobTombstone(
    jsonRequest("/tombstone", tombstone),
    env,
    currentActor,
    BLOB_ID,
    NOW + 22,
  );
  const evidence = {
    checkpointId: STABLE_CHECKPOINT_ID,
    checkpointCiphertextSha256Base64Url: checkpointHash,
    throughWorkspaceSeq: 0,
  };
  const signatureMessage = blobTombstoneAckSignatureMessage(
    env,
    IDS.workspaceA,
    BLOB_ID,
    TOMBSTONE_ID,
    evidence,
    IDS.deviceA,
  );
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    keyPair.privateKey,
    signatureMessage,
  )));
  await acknowledgeBlobTombstone(
    jsonRequest("/ack", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: TOMBSTONE_ID,
      ...evidence,
      signatureBase64Url: signature,
    }),
    env,
    currentActor,
    BLOB_ID,
    NOW + 23,
  );

  const gc = { protocolVersion: 2, schemaVersion: 2, blobId: BLOB_ID, tombstoneId: TOMBSTONE_ID };
  await assert.rejects(
    garbageCollectBlob(jsonRequest("/gc", gc), env, currentActor, NOW + 999),
    (error: unknown) => error instanceof ApiError && error.code === "blob_gc_safety_window_pending",
  );
  blobs.failOneDelete = true;
  await assert.rejects(
    garbageCollectBlob(jsonRequest("/gc", gc), env, currentActor, NOW + 1_023),
    /injected R2 deletion interruption/,
  );
  assert.equal(
    db.prepare("SELECT status FROM blob_gc_claims WHERE tombstone_id = ?")
      .get(TOMBSTONE_ID)?.status,
    "deleting",
  );
  db.prepare("UPDATE workspaces SET head_seq = 1 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  const postClaimCheckpointHash = encodeBase64Url(new Uint8Array(32).fill(92));
  seedStableCheckpoint(db, postClaimCheckpointHash, 2, 1, SECOND_STABLE_CHECKPOINT_ID);
  const postClaimEvidence = {
    checkpointId: SECOND_STABLE_CHECKPOINT_ID,
    checkpointCiphertextSha256Base64Url: postClaimCheckpointHash,
    throughWorkspaceSeq: 1,
  };
  const postClaimRevivalMessage = blobTombstoneRevivalSignatureMessage(
    env,
    IDS.workspaceA,
    BLOB_ID,
    MANIFEST_ID,
    TOMBSTONE_ID,
    postClaimEvidence,
    IDS.deviceA,
  );
  const postClaimRevivalSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    keyPair.privateKey,
    postClaimRevivalMessage,
  )));
  const postClaimRevival = await reviveBlobReference(
    jsonRequest("/revival", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: TOMBSTONE_ID,
      blobId: BLOB_ID,
      manifestId: MANIFEST_ID,
      ...postClaimEvidence,
      signatureBase64Url: postClaimRevivalSignature,
    }),
    env,
    currentActor,
    BLOB_ID,
    NOW + 1_023,
  ) as { disposition: string; cancelledAtEpochMillis: null };
  assert.deepEqual(postClaimRevival, {
    protocolVersion: 2,
    schemaVersion: 2,
    tombstoneId: TOMBSTONE_ID,
    blobId: BLOB_ID,
    manifestId: MANIFEST_ID,
    disposition: "reupload_required",
    cancelledAtEpochMillis: null,
  });
  assert.equal(
    db.prepare("SELECT status FROM blob_tombstones WHERE tombstone_id = ?")
      .get(TOMBSTONE_ID)?.status,
    "deleting",
  );
  const gcReceipt = await garbageCollectBlob(
    jsonRequest("/gc", gc),
    env,
    currentActor,
    NOW + 1_024,
  ) as { receiptId: string; deletedObjectCount: number; deletedCiphertextBytes: number };
  assert.equal(gcReceipt.deletedObjectCount, 2);
  assert.equal(gcReceipt.deletedCiphertextBytes, 80);
  assert.equal(blobs.objects.size, 0);
  assert.deepEqual(
    await garbageCollectBlob(jsonRequest("/gc", gc), env, currentActor, NOW + 1_025),
    gcReceipt,
  );
  assert.equal(
    db.prepare("SELECT status FROM encrypted_blob_manifests WHERE workspace_id = ? AND blob_id = ?")
      .get(IDS.workspaceA, BLOB_ID)?.status,
    "deleted",
  );
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM blob_dek_envelopes WHERE workspace_id = ? AND blob_id = ?")
      .get(IDS.workspaceA, BLOB_ID)?.count,
    0,
  );
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM blob_upload_initial_envelopes WHERE workspace_id = ? AND blob_id = ?")
      .get(IDS.workspaceA, BLOB_ID)?.count,
    0,
  );

  const historicalAudit = {
    tombstone: db.prepare(`SELECT manifest_id, status, deleted_at FROM blob_tombstones
      WHERE tombstone_id = ?`).get(TOMBSTONE_ID),
    revival: db.prepare(`SELECT manifest_id, outcome, created_at FROM blob_tombstone_revivals
      WHERE tombstone_id = ? AND validator_device_id = ?`).get(TOMBSTONE_ID, IDS.deviceA),
    gc: db.prepare(`SELECT manifest_ciphertext_sha256, object_list_sha256,
      deleted_object_count, deleted_ciphertext_bytes, completed_at
      FROM blob_gc_receipts WHERE tombstone_id = ?`).get(TOMBSTONE_ID),
  };
  const secondFixture = await commitFixture(env, currentActor, {
    manifestId: REUPLOAD_MANIFEST_ID,
    at: NOW + 1_030,
    seed: 41,
    chunkByteSize: 64,
    manifestByteSize: 40,
  });
  assert.notEqual(secondFixture.session.sessionId, fixture.session.sessionId);
  const secondReceipt = await uploadAndCommit(env, currentActor, secondFixture) as {
    receiptId: string;
    sessionId: string;
    manifest: { manifestId: string; bodyCiphertextByteSize: number; manifestCiphertextByteSize: number };
  };
  assert.equal(secondReceipt.sessionId, secondFixture.session.sessionId);
  assert.equal(secondReceipt.manifest.manifestId, REUPLOAD_MANIFEST_ID);
  assert.equal(secondReceipt.manifest.bodyCiphertextByteSize, 64);
  assert.equal(secondReceipt.manifest.manifestCiphertextByteSize, 40);

  const reuploadedManifest = await downloadBlobManifest(env, currentActor, BLOB_ID) as {
    remote: { manifestId: string; commitReceiptId: string };
    encryptedPrivateManifest: { ciphertextBase64Url: string };
  };
  assert.equal(reuploadedManifest.remote.manifestId, REUPLOAD_MANIFEST_ID);
  assert.equal(reuploadedManifest.remote.commitReceiptId, secondReceipt.receiptId);
  assert.equal(
    reuploadedManifest.encryptedPrivateManifest.ciphertextBase64Url,
    encodeBase64Url(secondFixture.privateManifestCiphertext),
  );
  assert.deepEqual(
    new Uint8Array(await (await downloadBlobChunk(env, currentActor, BLOB_ID, 0)).arrayBuffer()),
    secondFixture.chunkCiphertext,
  );

  // A late device may still observe the old removal after the same blob id has been reincarnated.
  // Its typed recovery answer is derived from the old tombstone/GC receipt generation, never from
  // the new committed manifest row that now owns (workspace_id, blob_id).
  const lateKeys = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const latePublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", lateKeys.publicKey)),
  );
  db.prepare(`INSERT INTO users(
    user_id, display_name, instance_role, status, quota_profile_id, created_at
  ) VALUES (?, 'Late validator', 'user', 'active', 'private-default', ?)`)
    .run(IDS.userB, NOW + 1_033);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'Late validator', 'windows', ?, 'late-wrap', 'late-token', 'active', 1, ?)`)
    .run(IDS.deviceB, IDS.userB, latePublicKey, NOW + 1_033);
  db.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
    VALUES (?, ?, 'editor', 'active', 1)`)
    .run(IDS.workspaceA, IDS.userB);
  db.prepare(`INSERT INTO workspace_capabilities(
    capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
    membership_auth_epoch, key_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 'late-capability', 1, 1, 2, ?, ?)`)
    .run(IDS.capabilityB, IDS.workspaceA, IDS.deviceB, NOW + 100_000, NOW + 1_033);
  const lateActor: CapabilityPrincipal = {
    ...currentActor,
    capabilityId: IDS.capabilityB,
    deviceId: IDS.deviceB,
    userId: IDS.userB,
    role: "editor",
    signingPublicKey: latePublicKey,
  };
  const lateRevivalSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    lateKeys.privateKey,
    blobTombstoneRevivalSignatureMessage(
      env,
      IDS.workspaceA,
      BLOB_ID,
      MANIFEST_ID,
      TOMBSTONE_ID,
      postClaimEvidence,
      IDS.deviceB,
    ),
  )));
  assert.deepEqual(
    await reviveBlobReference(
      jsonRequest("/late-revival", {
        protocolVersion: 2,
        schemaVersion: 2,
        tombstoneId: TOMBSTONE_ID,
        blobId: BLOB_ID,
        manifestId: MANIFEST_ID,
        ...postClaimEvidence,
        signatureBase64Url: lateRevivalSignature,
      }),
      env,
      lateActor,
      BLOB_ID,
      NOW + 1_034,
    ),
    {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: TOMBSTONE_ID,
      blobId: BLOB_ID,
      manifestId: MANIFEST_ID,
      disposition: "reupload_required",
      cancelledAtEpochMillis: null,
    },
  );
  assert.deepEqual(
    await garbageCollectBlob(jsonRequest("/gc", gc), env, currentActor, NOW + 1_040),
    gcReceipt,
  );
  assert.deepEqual(
    {
      tombstone: db.prepare(`SELECT manifest_id, status, deleted_at FROM blob_tombstones
        WHERE tombstone_id = ?`).get(TOMBSTONE_ID),
      revival: db.prepare(`SELECT manifest_id, outcome, created_at FROM blob_tombstone_revivals
        WHERE tombstone_id = ? AND validator_device_id = ?`).get(TOMBSTONE_ID, IDS.deviceA),
      gc: db.prepare(`SELECT manifest_ciphertext_sha256, object_list_sha256,
        deleted_object_count, deleted_ciphertext_bytes, completed_at
        FROM blob_gc_receipts WHERE tombstone_id = ?`).get(TOMBSTONE_ID),
    },
    historicalAudit,
  );
  assert.deepEqual(
    { ...db.prepare(`SELECT manifest_id AS manifestId, session_id AS sessionId, status,
        committed_at AS committedAt, deleted_at AS deletedAt
        FROM encrypted_blob_manifests WHERE workspace_id = ? AND blob_id = ?`)
      .get(IDS.workspaceA, BLOB_ID) },
    {
      manifestId: REUPLOAD_MANIFEST_ID,
      sessionId: secondFixture.session.sessionId,
      status: "committed",
      committedAt: NOW + 1_032,
      deletedAt: null,
    },
  );
  assert.equal(
    db.prepare(`SELECT COUNT(*) AS count FROM blob_manifest_commits
      WHERE workspace_id = ? AND blob_id = ?`).get(IDS.workspaceA, BLOB_ID)?.count,
    2,
  );
});

test("two devices adopt one canonical removal boundary; signed revival cancels GC and permits a later cycle", async () => {
  const db = database();
  const firstKeys = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const secondKeys = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const firstPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", firstKeys.publicKey)),
  );
  const secondPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", secondKeys.publicKey)),
  );
  seedTenant(db, NOW, firstPublicKey);
  db.prepare(`INSERT INTO users(
    user_id, display_name, instance_role, status, quota_profile_id, created_at
  ) VALUES (?, 'B', 'user', 'active', 'private-default', ?)`)
    .run(IDS.userB, NOW);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'B desktop', 'windows', ?, 'wrap-b', 'token-b', 'active', 1, ?)`)
    .run(IDS.deviceB, IDS.userB, secondPublicKey, NOW);
  db.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
    VALUES (?, ?, 'editor', 'active', 1)`)
    .run(IDS.workspaceA, IDS.userB);

  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  const firstActor = principal(firstPublicKey);
  const secondActor: CapabilityPrincipal = {
    ...firstActor,
    capabilityId: IDS.capabilityB,
    deviceId: IDS.deviceB,
    userId: IDS.userB,
    role: "editor",
    signingPublicKey: secondPublicKey,
  };
  const fixture = await commitFixture(env, firstActor);
  await uploadAndCommit(env, firstActor, fixture);

  const firstRemoval = {
    protocolVersion: 2,
    schemaVersion: 2,
    tombstoneId: TOMBSTONE_ID,
    blobId: BLOB_ID,
    manifestId: MANIFEST_ID,
    throughWorkspaceSeq: 0,
    createdAtEpochMillis: NOW + 10,
  };
  const secondProvisional = {
    ...firstRemoval,
    tombstoneId: SECOND_TOMBSTONE_ID,
    createdAtEpochMillis: NOW + 11,
  };
  const [firstResult, secondResult] = await Promise.all([
    createBlobTombstone(
      jsonRequest("/tombstone", firstRemoval),
      env,
      firstActor,
      BLOB_ID,
      NOW + 10,
    ),
    createBlobTombstone(
      jsonRequest("/tombstone", secondProvisional),
      env,
      secondActor,
      BLOB_ID,
      NOW + 11,
    ),
  ]) as Array<{
    disposition: string;
    handle: { tombstoneId: string; referenceThroughWorkspaceSeq: number };
  }>;
  assert.equal(firstResult.disposition, "active");
  assert.equal(secondResult.disposition, "active");
  assert.deepEqual(firstResult.handle, secondResult.handle);
  assert.ok([TOMBSTONE_ID, SECOND_TOMBSTONE_ID].includes(firstResult.handle.tombstoneId));
  assert.equal(firstResult.handle.referenceThroughWorkspaceSeq, 0);
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM blob_tombstones WHERE workspace_id = ? AND blob_id = ?")
      .get(IDS.workspaceA, BLOB_ID)?.count,
    1,
  );

  db.prepare("UPDATE workspaces SET head_seq = 1 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  const firstLiveHash = encodeBase64Url(new Uint8Array(32).fill(81));
  seedStableCheckpoint(db, firstLiveHash, 1, 1);
  const firstLiveEvidence = {
    checkpointId: STABLE_CHECKPOINT_ID,
    checkpointCiphertextSha256Base64Url: firstLiveHash,
    throughWorkspaceSeq: 1,
  };
  const firstRevivalMessage = blobTombstoneRevivalSignatureMessage(
    env,
    IDS.workspaceA,
    BLOB_ID,
    MANIFEST_ID,
    firstResult.handle.tombstoneId,
    firstLiveEvidence,
    IDS.deviceB,
  );
  const firstRevivalSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    secondKeys.privateKey,
    firstRevivalMessage,
  )));
  const firstRevival = await reviveBlobReference(
    jsonRequest("/revival", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: firstResult.handle.tombstoneId,
      blobId: BLOB_ID,
      manifestId: MANIFEST_ID,
      ...firstLiveEvidence,
      signatureBase64Url: firstRevivalSignature,
    }),
    env,
    secondActor,
    BLOB_ID,
    NOW + 20,
  ) as { disposition: string; cancelledAtEpochMillis: number };
  assert.equal(firstRevival.disposition, "cancelled");
  assert.equal(firstRevival.cancelledAtEpochMillis, NOW + 20);
  assert.equal(
    db.prepare("SELECT status FROM blob_tombstones WHERE tombstone_id = ?")
      .get(firstResult.handle.tombstoneId)?.status,
    "cancelled",
  );
  assert.equal(
    db.prepare("SELECT status FROM encrypted_blob_manifests WHERE workspace_id = ? AND blob_id = ?")
      .get(IDS.workspaceA, BLOB_ID)?.status,
    "committed",
  );
  assert.equal(
    db.prepare("SELECT outcome FROM blob_tombstone_revivals WHERE tombstone_id = ?")
      .get(firstResult.handle.tombstoneId)?.outcome,
    "cancelled",
  );

  await assert.rejects(
    acknowledgeBlobTombstone(
      jsonRequest("/ack", {
        protocolVersion: 2,
        schemaVersion: 2,
        tombstoneId: firstResult.handle.tombstoneId,
        ...firstLiveEvidence,
        signatureBase64Url: encodeBase64Url(new Uint8Array(64)),
      }),
      env,
      firstActor,
      BLOB_ID,
      NOW + 21,
    ),
    (error: unknown) => error instanceof ApiError && error.code === "blob_tombstone_not_acknowledgeable",
  );
  await assert.rejects(
    garbageCollectBlob(
      jsonRequest("/gc", {
        protocolVersion: 2,
        schemaVersion: 2,
        blobId: BLOB_ID,
        tombstoneId: firstResult.handle.tombstoneId,
      }),
      env,
      firstActor,
      NOW + 100_000,
    ),
    (error: unknown) => error instanceof ApiError && error.code === "blob_gc_not_pending",
  );
  assert.equal(blobs.objects.size, 2);

  const secondRemoval = await createBlobTombstone(
    jsonRequest("/tombstone", {
      ...firstRemoval,
      tombstoneId: THIRD_TOMBSTONE_ID,
      throughWorkspaceSeq: 1,
      createdAtEpochMillis: NOW + 30,
    }),
    env,
    firstActor,
    BLOB_ID,
    NOW + 30,
  ) as { disposition: string; handle: { tombstoneId: string } };
  assert.equal(secondRemoval.disposition, "active");
  assert.equal(secondRemoval.handle.tombstoneId, THIRD_TOMBSTONE_ID);
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM blob_tombstones WHERE workspace_id = ? AND blob_id = ?")
      .get(IDS.workspaceA, BLOB_ID)?.count,
    2,
  );

  db.prepare("UPDATE workspaces SET head_seq = 2 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  const secondLiveHash = encodeBase64Url(new Uint8Array(32).fill(82));
  seedStableCheckpoint(db, secondLiveHash, 1, 2, SECOND_STABLE_CHECKPOINT_ID);
  const secondLiveEvidence = {
    checkpointId: SECOND_STABLE_CHECKPOINT_ID,
    checkpointCiphertextSha256Base64Url: secondLiveHash,
    throughWorkspaceSeq: 2,
  };
  const secondRevivalMessage = blobTombstoneRevivalSignatureMessage(
    env,
    IDS.workspaceA,
    BLOB_ID,
    MANIFEST_ID,
    THIRD_TOMBSTONE_ID,
    secondLiveEvidence,
    IDS.deviceA,
  );
  const secondRevivalSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    firstKeys.privateKey,
    secondRevivalMessage,
  )));
  const secondRevival = await reviveBlobReference(
    jsonRequest("/revival", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: THIRD_TOMBSTONE_ID,
      blobId: BLOB_ID,
      manifestId: MANIFEST_ID,
      ...secondLiveEvidence,
      signatureBase64Url: secondRevivalSignature,
    }),
    env,
    firstActor,
    BLOB_ID,
    NOW + 40,
  ) as { disposition: string };
  assert.equal(secondRevival.disposition, "cancelled");
  assert.deepEqual(
    db.prepare(`SELECT status FROM blob_tombstones
      WHERE workspace_id = ? AND blob_id = ? ORDER BY reference_through_seq`)
      .all(IDS.workspaceA, BLOB_ID).map((row) => row.status),
    ["cancelled", "cancelled"],
  );
  assert.equal(blobs.objects.size, 2);
});

test("tombstoned bodies re-wrap across two rotations and refreshed epoch acks keep GC live", async () => {
  const db = database();
  const keys = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const publicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)),
  );
  seedTenant(db, NOW, publicKey);
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  const actorEpoch1 = principal(publicKey);
  const fixture = await commitFixture(env, actorEpoch1);
  await uploadAndCommit(env, actorEpoch1, fixture);

  const checkpointHash1 = encodeBase64Url(new Uint8Array(32).fill(101));
  seedStableCheckpoint(db, checkpointHash1, 1);
  await createBlobTombstone(
    jsonRequest("/tombstone", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: TOMBSTONE_ID,
      blobId: BLOB_ID,
      manifestId: MANIFEST_ID,
      throughWorkspaceSeq: 0,
      createdAtEpochMillis: NOW + 20,
    }),
    env,
    actorEpoch1,
    BLOB_ID,
    NOW + 20,
  );

  async function acknowledgeAtEpoch(
    actor: CapabilityPrincipal,
    checkpointId: string,
    checkpointHash: string,
    at: number,
  ): Promise<void> {
    const evidence = {
      checkpointId,
      checkpointCiphertextSha256Base64Url: checkpointHash,
      throughWorkspaceSeq: 0,
    };
    const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
      "Ed25519",
      keys.privateKey,
      blobTombstoneAckSignatureMessage(
        env,
        IDS.workspaceA,
        BLOB_ID,
        TOMBSTONE_ID,
        evidence,
        IDS.deviceA,
      ),
    )));
    await acknowledgeBlobTombstone(
      jsonRequest("/ack", {
        protocolVersion: 2,
        schemaVersion: 2,
        tombstoneId: TOMBSTONE_ID,
        ...evidence,
        signatureBase64Url: signature,
      }),
      env,
      actor,
      BLOB_ID,
      at,
    );
  }

  await acknowledgeAtEpoch(actorEpoch1, STABLE_CHECKPOINT_ID, checkpointHash1, NOW + 21);

  stageKeyRotation(db, IDS.rotationA, 1, 2, NOW + 30);
  db.prepare("INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)")
    .run(IDS.rotationA, NOW + 32);
  db.prepare("UPDATE workspace_capabilities SET key_epoch = 2 WHERE capability_id = ?")
    .run(IDS.capabilityA);
  const actorEpoch2 = { ...actorEpoch1, keyEpoch: 2 };
  const checkpointHash2 = encodeBase64Url(new Uint8Array(32).fill(102));
  seedStableCheckpoint(db, checkpointHash2, 2, 0, SECOND_STABLE_CHECKPOINT_ID);
  const envelopeEpoch2 = await envelope(
    BLOB_ID,
    2,
    31,
    fixture.initialEnvelope.envelopeSha256Base64Url,
  );
  await rewrapBlobEnvelope(
    jsonRequest("/rewrap", {
      protocolVersion: 2,
      schemaVersion: 2,
      manifestId: MANIFEST_ID,
      envelope: envelopeEpoch2,
      checkpointEvidence: {
        checkpointId: SECOND_STABLE_CHECKPOINT_ID,
        checkpointCiphertextSha256Base64Url: checkpointHash2,
        throughWorkspaceSeq: 0,
      },
    }),
    env,
    actorEpoch2,
    BLOB_ID,
    NOW + 33,
  );
  await acknowledgeAtEpoch(actorEpoch2, SECOND_STABLE_CHECKPOINT_ID, checkpointHash2, NOW + 34);

  stageKeyRotation(db, SECOND_ROTATION_ID, 2, 3, NOW + 40);
  assert.doesNotThrow(() => {
    db.prepare("INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)")
      .run(SECOND_ROTATION_ID, NOW + 42);
  });
  db.prepare("UPDATE workspace_capabilities SET key_epoch = 3 WHERE capability_id = ?")
    .run(IDS.capabilityA);
  const actorEpoch3 = { ...actorEpoch2, keyEpoch: 3 };
  const checkpointHash3 = encodeBase64Url(new Uint8Array(32).fill(103));
  seedStableCheckpoint(db, checkpointHash3, 3, 0, THIRD_STABLE_CHECKPOINT_ID);
  const envelopeEpoch3 = await envelope(
    BLOB_ID,
    3,
    32,
    envelopeEpoch2.envelopeSha256Base64Url,
  );
  await rewrapBlobEnvelope(
    jsonRequest("/rewrap", {
      protocolVersion: 2,
      schemaVersion: 2,
      manifestId: MANIFEST_ID,
      envelope: envelopeEpoch3,
      checkpointEvidence: {
        checkpointId: THIRD_STABLE_CHECKPOINT_ID,
        checkpointCiphertextSha256Base64Url: checkpointHash3,
        throughWorkspaceSeq: 0,
      },
    }),
    env,
    actorEpoch3,
    BLOB_ID,
    NOW + 43,
  );
  await acknowledgeAtEpoch(actorEpoch3, THIRD_STABLE_CHECKPOINT_ID, checkpointHash3, NOW + 44);

  assert.deepEqual(
    { ...db.prepare(`SELECT evidence_checkpoint_id AS checkpointId, through_seq AS throughSeq
      FROM blob_tombstone_acks
      WHERE tombstone_id = ? AND validator_device_id = ?`)
      .get(TOMBSTONE_ID, IDS.deviceA) },
    { checkpointId: THIRD_STABLE_CHECKPOINT_ID, throughSeq: 0 },
  );
  assert.equal(
    db.prepare(`SELECT current_envelope_epoch FROM encrypted_blob_manifests
      WHERE workspace_id = ? AND blob_id = ?`).get(IDS.workspaceA, BLOB_ID)?.current_envelope_epoch,
    3,
  );

  const receipt = await garbageCollectBlob(
    jsonRequest("/gc", {
      protocolVersion: 2,
      schemaVersion: 2,
      blobId: BLOB_ID,
      tombstoneId: TOMBSTONE_ID,
    }),
    env,
    actorEpoch3,
    NOW + 2_000,
  ) as { deletedObjectCount: number };
  assert.equal(receipt.deletedObjectCount, 2);
  assert.equal(blobs.objects.size, 0);
});

test("D1 rejects re-wrap revocation and GC role-change races after Worker prechecks", async () => {
  const db = database();
  const keys = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const publicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)),
  );
  seedTenant(db, NOW, publicKey);
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  const actorEpoch1 = principal(publicKey);
  const fixture = await commitFixture(env, actorEpoch1);
  await uploadAndCommit(env, actorEpoch1, fixture);
  db.prepare("UPDATE workspaces SET active_key_epoch = 2 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  db.prepare("UPDATE workspace_capabilities SET key_epoch = 2 WHERE capability_id = ?")
    .run(IDS.capabilityA);
  const actor = { ...actorEpoch1, keyEpoch: 2 };
  const checkpointHash = encodeBase64Url(new Uint8Array(32).fill(111));
  seedStableCheckpoint(db, checkpointHash, 2);
  const currentEnvelope = await envelope(
    BLOB_ID,
    2,
    41,
    fixture.initialEnvelope.envelopeSha256Base64Url,
  );
  const rewrapRequest = jsonRequest("/rewrap", {
    protocolVersion: 2,
    schemaVersion: 2,
    manifestId: MANIFEST_ID,
    envelope: currentEnvelope,
    checkpointEvidence: {
      checkpointId: STABLE_CHECKPOINT_ID,
      checkpointCiphertextSha256Base64Url: checkpointHash,
      throughWorkspaceSeq: 0,
    },
  });
  const revokeRaceEnv: Env = {
    ...env,
    DB: databaseWithBeforeFirstBatch(env.DB, () => {
      db.prepare("UPDATE workspace_capabilities SET revoked_at = ? WHERE capability_id = ?")
        .run(NOW + 21, IDS.capabilityA);
    }),
  };
  await assert.rejects(
    rewrapBlobEnvelope(rewrapRequest, revokeRaceEnv, actor, BLOB_ID, NOW + 21),
    (error: unknown) => error instanceof ApiError && error.code === "blob_envelope_rewrap_invalid",
  );
  assert.equal(
    db.prepare(`SELECT current_envelope_epoch FROM encrypted_blob_manifests
      WHERE workspace_id = ? AND blob_id = ?`).get(IDS.workspaceA, BLOB_ID)?.current_envelope_epoch,
    1,
  );

  db.prepare("UPDATE workspace_capabilities SET revoked_at = NULL WHERE capability_id = ?")
    .run(IDS.capabilityA);
  await rewrapBlobEnvelope(
    jsonRequest("/rewrap", {
      protocolVersion: 2,
      schemaVersion: 2,
      manifestId: MANIFEST_ID,
      envelope: currentEnvelope,
      checkpointEvidence: {
        checkpointId: STABLE_CHECKPOINT_ID,
        checkpointCiphertextSha256Base64Url: checkpointHash,
        throughWorkspaceSeq: 0,
      },
    }),
    env,
    actor,
    BLOB_ID,
    NOW + 22,
  );
  await createBlobTombstone(
    jsonRequest("/tombstone", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: TOMBSTONE_ID,
      blobId: BLOB_ID,
      manifestId: MANIFEST_ID,
      throughWorkspaceSeq: 0,
      createdAtEpochMillis: NOW + 23,
    }),
    env,
    actor,
    BLOB_ID,
    NOW + 23,
  );
  const ackEvidence = {
    checkpointId: STABLE_CHECKPOINT_ID,
    checkpointCiphertextSha256Base64Url: checkpointHash,
    throughWorkspaceSeq: 0,
  };
  const ackSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    keys.privateKey,
    blobTombstoneAckSignatureMessage(
      env,
      IDS.workspaceA,
      BLOB_ID,
      TOMBSTONE_ID,
      ackEvidence,
      IDS.deviceA,
    ),
  )));
  await acknowledgeBlobTombstone(
    jsonRequest("/ack", {
      protocolVersion: 2,
      schemaVersion: 2,
      tombstoneId: TOMBSTONE_ID,
      ...ackEvidence,
      signatureBase64Url: ackSignature,
    }),
    env,
    actor,
    BLOB_ID,
    NOW + 24,
  );

  const roleRaceEnv: Env = {
    ...env,
    DB: databaseWithBeforeFirstBatch(env.DB, () => {
      db.prepare("UPDATE memberships SET role = 'reader' WHERE workspace_id = ? AND user_id = ?")
        .run(IDS.workspaceA, IDS.userA);
    }),
  };
  await assert.rejects(
    garbageCollectBlob(
      jsonRequest("/gc", {
        protocolVersion: 2,
        schemaVersion: 2,
        blobId: BLOB_ID,
        tombstoneId: TOMBSTONE_ID,
      }),
      roleRaceEnv,
      actor,
      NOW + 2_000,
    ),
    (error: unknown) => error instanceof ApiError && error.code === "blob_gc_claim_invalid",
  );
  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM blob_gc_claims WHERE tombstone_id = ?")
      .get(TOMBSTONE_ID)?.count,
    0,
  );
  assert.equal(
    db.prepare("SELECT status FROM blob_tombstones WHERE tombstone_id = ?")
      .get(TOMBSTONE_ID)?.status,
    "waiting",
  );
  assert.equal(blobs.objects.size, 2);
});

test("expired upload orphans stay quota-charged until a bounded workspace sweep removes them", async () => {
  const db = database();
  seedTenant(db, NOW);
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  env.BLOB_UPLOAD_SESSION_TTL_SECONDS = "1";
  const actor = principal();
  const orphan = await commitFixture(env, actor, {
    blobId: ORPHAN_BLOB_ID,
    manifestId: ORPHAN_MANIFEST_ID,
    at: NOW,
    seed: 51,
  });
  await uploadBlobChunk(
    new Request("https://sync.test/chunk", {
      method: "PUT",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-Shinsou-Ciphertext-Sha256": orphan.chunkHash,
      },
      body: orphan.chunkCiphertext,
    }),
    env,
    actor,
    orphan.session.sessionId,
    0,
    NOW + 1,
  );
  assert.equal(blobs.objects.size, 1);
  assert.equal(
    db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.reserved_bytes,
    80,
  );

  blobs.failOneDelete = true;
  await assert.rejects(
    commitFixture(env, actor, {
      blobId: NEXT_BLOB_ID,
      manifestId: NEXT_MANIFEST_ID,
      at: NOW + 61_001,
      seed: 52,
      chunkByteSize: 64,
      manifestByteSize: 40,
    }),
    /injected R2 deletion interruption/,
  );
  assert.deepEqual(
    { ...db.prepare(`SELECT status, reserved_bytes AS reservedBytes
      FROM blob_upload_sessions WHERE session_id = ?`).get(orphan.session.sessionId) },
    { status: "expired", reservedBytes: 80 },
  );
  assert.equal(
    db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.reserved_bytes,
    80,
  );
  assert.equal(blobs.objects.size, 1);

  const next = await commitFixture(env, actor, {
    blobId: NEXT_BLOB_ID,
    manifestId: NEXT_MANIFEST_ID,
    at: NOW + 61_002,
    seed: 52,
    chunkByteSize: 64,
    manifestByteSize: 40,
  });
  assert.notEqual(next.session.sessionId, orphan.session.sessionId);
  assert.equal(
    db.prepare("SELECT status FROM blob_upload_sessions WHERE session_id = ?")
      .get(orphan.session.sessionId)?.status,
    "cleaned",
  );
  assert.equal(
    db.prepare("SELECT status FROM workspace_usage_reservations WHERE reservation_id = ?")
      .get(`blob:${orphan.session.sessionId}`)?.status,
    "released",
  );
  assert.equal(
    db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.reserved_bytes,
    104,
  );
  assert.equal(blobs.objects.size, 0);
});

test("large orphan cleanup advances a durable bounded cursor from unrelated blob traffic", async () => {
  const db = database();
  seedTenant(db, NOW);
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  env.BLOB_UPLOAD_SESSION_TTL_SECONDS = "1";
  const actor = principal();
  const planned: Array<{ bytes: Uint8Array; hash: string }> = [];
  for (let index = 0; index < 33; index += 1) {
    const bytes = new Uint8Array(16).fill(index + 1);
    planned.push({ bytes, hash: await sha256Base64Url(bytes) });
  }
  const privateManifest = new Uint8Array(16).fill(77);
  const initialEnvelope = await envelope(LARGE_ORPHAN_BLOB_ID, 1, 61, null);
  const session = await createBlobUploadSession(
    jsonRequest("/large-orphan", {
      protocolVersion: 2,
      schemaVersion: 2,
      blobId: LARGE_ORPHAN_BLOB_ID,
      manifestId: LARGE_ORPHAN_MANIFEST_ID,
      keyEpoch: 1,
      chunkSizeBytes: 64 * 1024,
      expectedBodyCiphertextBytes: planned.length * 16,
      expectedManifestCiphertextBytes: privateManifest.byteLength,
      manifestCiphertextSha256Base64Url: await sha256Base64Url(privateManifest),
      chunks: planned.map((chunk, index) => ({
        index,
        ciphertextByteSize: chunk.bytes.byteLength,
        ciphertextSha256Base64Url: chunk.hash,
      })),
      initialDekEnvelope: initialEnvelope,
    }),
    env,
    actor,
    NOW,
  ) as { sessionId: string };
  const last = planned[planned.length - 1];
  await uploadBlobChunk(
    new Request("https://sync.test/last-chunk", {
      method: "PUT",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-Shinsou-Ciphertext-Sha256": last.hash,
      },
      body: last.bytes,
    }),
    env,
    actor,
    session.sessionId,
    32,
    NOW + 1,
  );

  const unrelated = await commitFixture(env, actor, {
    blobId: NEXT_BLOB_ID,
    manifestId: NEXT_MANIFEST_ID,
    at: NOW + 61_001,
    seed: 62,
  });
  assert.deepEqual(
    { ...db.prepare(`SELECT status, orphan_cleanup_chunk_cursor AS cleanupCursor
      FROM blob_upload_sessions WHERE session_id = ?`).get(session.sessionId) },
    { status: "expired", cleanupCursor: 23 },
  );
  assert.equal(blobs.objects.size, 1);
  assert.equal(
    db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.reserved_bytes,
    624,
  );

  const resumedUnrelated = await commitFixture(env, actor, {
    blobId: NEXT_BLOB_ID,
    manifestId: NEXT_MANIFEST_ID,
    at: NOW + 61_002,
    seed: 62,
  });
  assert.equal(resumedUnrelated.session.sessionId, unrelated.session.sessionId);
  assert.deepEqual(
    { ...db.prepare(`SELECT status, orphan_cleanup_chunk_cursor AS cleanupCursor
      FROM blob_upload_sessions WHERE session_id = ?`).get(session.sessionId) },
    { status: "cleaned", cleanupCursor: 32 },
  );
  assert.equal(
    db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.reserved_bytes,
    80,
  );
  assert.equal(blobs.objects.size, 0);
});

test("D1 blocks a second key rotation until every live body reaches the previous epoch", async () => {
  const db = database();
  seedTenant(db, NOW);
  const env = environment(db);
  const actor = principal();
  const fixture = await commitFixture(env, actor);
  await uploadAndCommit(env, actor, fixture);

  function stageRotation(rotationId: string, fromEpoch: number, toEpoch: number, at: number) {
    db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?")
      .run(IDS.workspaceA);
    db.prepare(`INSERT INTO key_rotations(
      rotation_id, workspace_id, from_epoch, to_epoch, proposer_device_id,
      proposer_device_auth_epoch, membership_auth_epoch, key_commitment, manifest_cbor,
      manifest_signature, status, lease_expires_at, created_at
    ) VALUES (?, ?, ?, ?, ?, 1, 1, 'commitment', 'manifest', 'signature', 'leased', ?, ?)`)
      .run(rotationId, IDS.workspaceA, fromEpoch, toEpoch, IDS.deviceA, at + 10_000, at);
    db.prepare(`INSERT INTO workspace_device_keys(
      workspace_id, key_epoch, device_id, rotation_id, key_commitment,
      wrapped_key, wrapped_by_device_id, signature, created_at
    ) VALUES (?, ?, ?, ?, 'commitment', 'wrapped', ?, 'signature', ?)`)
      .run(IDS.workspaceA, toEpoch, IDS.deviceA, rotationId, IDS.deviceA, at + 1);
    db.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?, ?, ?, 'commitment', 'recovery-wrapped', ?)`)
      .run(IDS.workspaceA, toEpoch, rotationId, at + 1);
  }

  // The first rotation is safe: an uploaded body's initial envelope is at from_epoch.
  stageRotation(IDS.rotationA, 1, 2, NOW + 10);
  db.prepare("INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)")
    .run(IDS.rotationA, NOW + 12);
  assert.equal(
    db.prepare("SELECT active_key_epoch FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.active_key_epoch,
    2,
  );

  // A second rotation must wait for the low-priority 1 -> 2 body-envelope re-wrap.
  stageRotation(SECOND_ROTATION_ID, 2, 3, NOW + 20);
  assert.throws(
    () => db.prepare("INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)")
      .run(SECOND_ROTATION_ID, NOW + 22),
    /blob_rotation_envelopes_incomplete/,
  );
  assert.equal(
    db.prepare(`SELECT current_envelope_epoch FROM encrypted_blob_manifests
      WHERE workspace_id = ? AND blob_id = ?`).get(IDS.workspaceA, BLOB_ID)?.current_envelope_epoch,
    1,
  );
});

test("Worker exposes the v2 capability and authenticated manifest/chunk routes", async () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspace_capabilities SET token_hash = ?, expires_at = ? WHERE capability_id = ?")
    .run(
      await hashSecret(CAPABILITY_TOKEN, TOKEN_PEPPER, "workspace-capability"),
      NOW + 10_000_000,
      IDS.capabilityA,
    );
  const blobs = new MemoryR2();
  const env = environment(db, blobs);
  const actor = principal();
  const fixture = await commitFixture(env, actor);
  await uploadAndCommit(env, actor, fixture);
  const ctx: ExecutionContext = { waitUntil() {}, passThroughOnException() {} };

  const capabilities = await worker.fetch(
    new Request("https://sync.test/v1/capabilities"),
    env,
    ctx,
  );
  assert.equal(capabilities.status, 200);
  assert.deepEqual((await capabilities.json() as { blobBody: unknown }).blobBody, {
    enabled: true,
    protocolVersion: 2,
    schemaVersion: 2,
    maxBlobBytes: 134_217_728,
    maxChunkBytes: 8_388_608,
    maxChunks: 1024,
    reservationTtlMillis: 3_600_000,
    gcSafetyWindowMillis: 1_000,
  });

  const headers = { Authorization: `Bearer ${CAPABILITY_TOKEN}` };
  const manifest = await worker.fetch(
    new Request(`https://sync.test/v2/workspaces/${IDS.workspaceA}/blobs/${BLOB_ID}/manifest`, {
      headers,
    }),
    env,
    ctx,
  );
  assert.equal(manifest.status, 200);
  assert.equal((await manifest.json() as { remote: { blobId: string } }).remote.blobId, BLOB_ID);
  const chunk = await worker.fetch(
    new Request(`https://sync.test/v2/workspaces/${IDS.workspaceA}/blobs/${BLOB_ID}/chunks/0`, {
      headers,
    }),
    env,
    ctx,
  );
  assert.equal(chunk.status, 200);
  assert.equal(chunk.headers.get("X-Shinsou-Ciphertext-Sha256"), fixture.chunkHash);
  assert.deepEqual(new Uint8Array(await chunk.arrayBuffer()), fixture.chunkCiphertext);
  const recoveredEnvelope = await worker.fetch(
    new Request(
      `https://sync.test/v2/workspaces/${IDS.workspaceA}/blobs/${BLOB_ID}/envelopes/1`,
      { headers },
    ),
    env,
    ctx,
  );
  assert.equal(recoveredEnvelope.status, 200);
  assert.deepEqual(await recoveredEnvelope.json(), {
    protocolVersion: 2,
    schemaVersion: 2,
    manifestId: MANIFEST_ID,
    envelope: fixture.initialEnvelope,
    checkpointEvidence: null,
    status: "current",
  });
  const v1Body = await worker.fetch(
    new Request(`https://sync.test/v1/workspaces/${IDS.workspaceA}/blobs/${BLOB_ID}/manifest`, {
      headers,
    }),
    env,
    ctx,
  );
  assert.equal(v1Body.status, 404);
});
