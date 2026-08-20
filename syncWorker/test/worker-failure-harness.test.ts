import test from "node:test";
import assert from "node:assert/strict";
import { submitPairingCandidate, pairingView } from "../src/api/identity.ts";
import { createCheckpointLease } from "../src/api/checkpoints.ts";
import { WorkspaceHub } from "../src/durable-objects/WorkspaceHub.ts";
import {
  encodeBase64Url,
  hashSecret,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import { encodeCanonicalCbor } from "../src/crypto/cbor.ts";
import { envelopeSignatureMessage } from "../src/protocol.ts";
import type {
  CapabilityPrincipal,
  D1Database,
  D1PreparedStatement,
  D1Result,
  DurableObjectState,
  DurableObjectStorage,
  Env,
  R2Bucket,
} from "../src/runtime.ts";
import { database, IDS, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_700_000_000_000;
const PEPPER = "p".repeat(32);
const PAIRING_ID = "00000000-0000-4000-8000-000000000020";
const EXPIRED_PAIRING_ID = "00000000-0000-4000-8000-000000000021";
const PAIRING_SECRET = encodeBase64Url(new Uint8Array(32).map((_, index) => index + 1));
const WRONG_PAIRING_SECRET = encodeBase64Url(new Uint8Array(32).map((_, index) => 255 - index));

function baseEnv(db = database(), checkpoints: R2Bucket = emptyR2()): Env {
  return {
    DB: sqliteD1(db),
    CHECKPOINTS: checkpoints,
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: PEPPER,
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "true",
  };
}

function emptyR2(onDelete?: (key: string) => void | Promise<void>): R2Bucket {
  return {
    async get() { return null; },
    async head() { return null; },
    async put() { return null; },
    async delete(key: string) { await onDelete?.(key); },
  };
}

function capability(now = NOW): CapabilityPrincipal {
  return {
    capabilityId: IDS.capabilityA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 1,
    signingPublicKey: "unused-in-checkpoint-lease",
    expiresAt: now + 1_000_000,
  };
}

function checkpointLeaseRequest(
  checkpointId: string,
  hashByte: number,
  expectedByteSize: number,
): Request {
  return new Request("https://sync.test/v1/checkpoint-leases", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      checkpointId,
      ciphertextSha256: encodeBase64Url(new Uint8Array(32).fill(hashByte)),
      expectedByteSize,
      throughWorkspaceSeq: 0,
    }),
  });
}

function candidateRequest(pairingId: string, pairingSecret: string, deviceId = IDS.deviceA2): Request {
  return new Request(`https://sync.test/v1/pairings/${pairingId}/candidate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      pairingId,
      pairingSecret,
      device: {
        deviceId,
        displayName: "Candidate phone",
        platform: "ios",
        signingPublicKey: encodeBase64Url(new Uint8Array(32).fill(11)),
        wrappingPublicKey: encodeBase64Url(new Uint8Array(32).fill(12)),
        deviceToken: encodeBase64Url(new Uint8Array(32).fill(13)),
      },
    }),
  });
}

async function seedOpenPairing(
  db: ReturnType<typeof database>,
  pairingId: string,
  expiresAt: number,
): Promise<void> {
  const secretHash = await hashSecret(PAIRING_SECRET, PEPPER, "pairing");
  db.prepare(`INSERT INTO pairing_sessions(
    pairing_id, workspace_id, sponsor_device_id, secret_hash, transcript_nonce,
    status, expires_at, created_at
  ) VALUES (?, ?, ?, ?, ?, 'open', ?, ?)`).run(
    pairingId,
    IDS.workspaceA,
    IDS.deviceA,
    secretHash,
    encodeBase64Url(new Uint8Array(32).fill(14)),
    expiresAt,
    NOW,
  );
}

test("pairing rejects a wrong secret, consumes the right secret once, and preserves the first candidate", async () => {
  const db = database();
  seedTenant(db, NOW);
  await seedOpenPairing(db, PAIRING_ID, NOW + 60_000);
  const env = baseEnv(db);

  await assert.rejects(
    submitPairingCandidate(candidateRequest(PAIRING_ID, WRONG_PAIRING_SECRET), env, PAIRING_ID, NOW + 1),
    (error: unknown) => (error as { status?: number; code?: string }).status === 410 &&
      (error as { code?: string }).code === "pairing_invalid_or_expired",
  );
  await assert.rejects(
    pairingView(env, PAIRING_ID, null, WRONG_PAIRING_SECRET, NOW + 1),
    (error: unknown) => (error as { status?: number; code?: string }).status === 401 &&
      (error as { code?: string }).code === "pairing_secret_invalid",
  );
  assert.deepEqual(
    { ...db.prepare(`SELECT status, candidate_device_id AS candidateDeviceId
      FROM pairing_sessions WHERE pairing_id = ?`).get(PAIRING_ID) },
    { status: "open", candidateDeviceId: null },
  );

  const accepted = await submitPairingCandidate(
    candidateRequest(PAIRING_ID, PAIRING_SECRET),
    env,
    PAIRING_ID,
    NOW + 2,
  ) as Record<string, unknown>;
  assert.equal(accepted.status, "candidate");
  assert.match(String(accepted.shortCode), /^\d{6}$/);

  const replayDeviceId = "00000000-0000-4000-8000-000000000022";
  await assert.rejects(
    submitPairingCandidate(
      candidateRequest(PAIRING_ID, PAIRING_SECRET, replayDeviceId),
      env,
      PAIRING_ID,
      NOW + 3,
    ),
    (error: unknown) => (error as { status?: number; code?: string }).status === 410 &&
      (error as { code?: string }).code === "pairing_invalid_or_expired",
  );
  const durable = db.prepare(`SELECT status, secret_hash AS secretHash,
    candidate_device_id AS candidateDeviceId, candidate_submitted_at AS submittedAt
    FROM pairing_sessions WHERE pairing_id = ?`).get(PAIRING_ID) as Record<string, unknown>;
  assert.deepEqual({
    status: durable.status,
    candidateDeviceId: durable.candidateDeviceId,
    submittedAt: durable.submittedAt,
  }, {
    status: "candidate",
    candidateDeviceId: IDS.deviceA2,
    submittedAt: NOW + 2,
  });
  assert.equal(durable.secretHash, await hashSecret(PAIRING_SECRET, PEPPER, "pairing"));
  assert.notEqual(durable.secretHash, PAIRING_SECRET, "D1 must never store the bearer secret");
});

test("an expired pairing cannot be viewed or claimed and does not retain candidate input", async () => {
  const db = database();
  seedTenant(db, NOW);
  await seedOpenPairing(db, EXPIRED_PAIRING_ID, NOW + 1);
  const env = baseEnv(db);

  await assert.rejects(
    submitPairingCandidate(
      candidateRequest(EXPIRED_PAIRING_ID, PAIRING_SECRET),
      env,
      EXPIRED_PAIRING_ID,
      NOW + 1,
    ),
    (error: unknown) => (error as { status?: number; code?: string }).status === 410 &&
      (error as { code?: string }).code === "pairing_invalid_or_expired",
  );
  await assert.rejects(
    pairingView(env, EXPIRED_PAIRING_ID, null, PAIRING_SECRET, NOW + 1),
    (error: unknown) => (error as { status?: number; code?: string }).status === 410 &&
      (error as { code?: string }).code === "pairing_invalid_or_expired",
  );
  assert.deepEqual(
    { ...db.prepare(`SELECT status, candidate_device_id AS candidateDeviceId
      FROM pairing_sessions WHERE pairing_id = ?`).get(EXPIRED_PAIRING_ID) },
    { status: "open", candidateDeviceId: null },
  );
});

test("expired checkpoint cleanup releases quota before a replacement reservation", async () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE quota_profiles SET max_workspace_bytes = 200 WHERE quota_profile_id = 'private-default'")
    .run();
  const oldHash = encodeBase64Url(new Uint8Array(32).fill(31));
  db.prepare(`INSERT INTO checkpoint_leases(
    lease_id, workspace_id, checkpoint_id, ciphertext_sha256, through_seq, key_epoch,
    uploader_device_id, capability_id, reserved_bytes, status, expires_at, created_at
  ) VALUES (?, ?, ?, ?, 0, 1, ?, ?, 100, 'active', ?, ?)`).run(
    IDS.challenge,
    IDS.workspaceA,
    IDS.checkpointA,
    oldHash,
    IDS.deviceA,
    IDS.capabilityA,
    NOW - 1,
    NOW - 10_000,
  );
  assert.equal(db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
    .get(IDS.workspaceA)?.reserved_bytes, 100);

  const deleted: string[] = [];
  const env = baseEnv(db, emptyR2((key) => { deleted.push(key); }));
  const replacement = await createCheckpointLease(
    checkpointLeaseRequest(IDS.checkpointB, 32, 150),
    env,
    capability(),
    NOW,
  ) as Record<string, unknown>;

  assert.equal(replacement.checkpointId, IDS.checkpointB);
  assert.equal(deleted.length, 1);
  assert.equal(db.prepare("SELECT status FROM checkpoint_leases WHERE lease_id = ?")
    .get(IDS.challenge)?.status, "expired");
  assert.equal(db.prepare("SELECT status FROM workspace_usage_reservations WHERE reservation_id = ?")
    .get(IDS.challenge)?.status, "released");
  assert.equal(db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
    .get(IDS.workspaceA)?.reserved_bytes, 150);
  assert.equal(db.prepare(`SELECT COUNT(*) AS count FROM workspace_usage_reservations
    WHERE workspace_id = ? AND status = 'active'`).get(IDS.workspaceA)?.count, 1);
});

for (const scenario of [
  { name: "partial unique CAS", maximum: 1_000, expectedCode: "checkpoint_lease_unavailable", expectedStatus: 409 },
  { name: "quota CAS", maximum: 100, expectedCode: "workspace_quota_exceeded", expectedStatus: 507 },
] as const) {
  test(`concurrent checkpoint reservations obey ${scenario.name} without double accounting`, async () => {
    const db = database();
    seedTenant(db, NOW);
    db.prepare("UPDATE quota_profiles SET max_workspace_bytes = ? WHERE quota_profile_id = 'private-default'")
      .run(scenario.maximum);
    const env = baseEnv(db);
    const outcomes = await Promise.allSettled([
      createCheckpointLease(
        checkpointLeaseRequest(IDS.checkpointA, 41, 80),
        env,
        capability(),
        NOW,
      ),
      createCheckpointLease(
        checkpointLeaseRequest(IDS.checkpointB, 42, 80),
        env,
        capability(),
        NOW,
      ),
    ]);
    const successes = outcomes.filter((outcome) => outcome.status === "fulfilled");
    const failures = outcomes.filter((outcome) => outcome.status === "rejected") as PromiseRejectedResult[];
    assert.equal(successes.length, 1);
    assert.equal(failures.length, 1);
    assert.equal((failures[0].reason as { status?: number }).status, scenario.expectedStatus);
    assert.equal((failures[0].reason as { code?: string }).code, scenario.expectedCode);
    assert.equal(db.prepare(`SELECT COUNT(*) AS count FROM checkpoint_leases
      WHERE workspace_id = ? AND status IN ('active', 'uploaded')`).get(IDS.workspaceA)?.count, 1);
    assert.equal(db.prepare("SELECT reserved_bytes FROM workspaces WHERE workspace_id = ?")
      .get(IDS.workspaceA)?.reserved_bytes, 80);
    assert.equal(db.prepare(`SELECT COALESCE(SUM(byte_size), 0) AS bytes
      FROM workspace_usage_reservations WHERE workspace_id = ? AND status = 'active'`)
      .get(IDS.workspaceA)?.bytes, 80);
  });
}

class FaultingStatement implements D1PreparedStatement {
  private readonly inner: D1PreparedStatement;
  private readonly failAll: boolean;

  constructor(inner: D1PreparedStatement, failAll: boolean) {
    this.inner = inner;
    this.failAll = failAll;
  }

  bind(...values: unknown[]): D1PreparedStatement {
    return new FaultingStatement(this.inner.bind(...values), this.failAll);
  }

  first<T = Record<string, unknown>>(column?: string): Promise<T | null> {
    return this.inner.first<T>(column);
  }

  all<T = Record<string, unknown>>(): Promise<D1Result<T>> {
    if (this.failAll) return Promise.resolve({ success: false, error: "injected commit failure" });
    return this.inner.all<T>();
  }

  run<T = Record<string, unknown>>(): Promise<D1Result<T>> {
    return this.inner.run<T>();
  }

  raw<T = unknown[]>(): Promise<T[]> {
    return this.inner.raw<T>();
  }
}

function failEventCommit(base: D1Database): D1Database {
  return {
    prepare(sql: string) {
      return new FaultingStatement(
        base.prepare(sql),
        sql.trimStart().startsWith("INSERT INTO events("),
      );
    },
    batch(statements: D1PreparedStatement[]) { return base.batch(statements); },
    exec(sql: string) { return base.exec(sql); },
  };
}

interface CloseCall {
  code?: number;
  reason?: string;
}

class FakeSocket {
  readonly messages: string[] = [];
  readonly closes: CloseCall[] = [];
  private savedAttachment: unknown = null;

  constructor(value?: unknown) {
    this.savedAttachment = value ?? null;
  }

  send(value: string | ArrayBufferLike | Blob | ArrayBufferView): void {
    if (typeof value !== "string") throw new Error("test socket only accepts strings");
    this.messages.push(value);
  }

  close(code?: number, reason?: string): void {
    this.closes.push({ code, reason });
  }

  serializeAttachment(value: unknown): void {
    this.savedAttachment = structuredClone(value);
  }

  deserializeAttachment(): unknown {
    return structuredClone(this.savedAttachment);
  }
}

class FakeStorage implements DurableObjectStorage {
  readonly values = new Map<string, unknown>();
  readonly sockets: WebSocket[];
  alarmAt: number | null = null;

  constructor(sockets: FakeSocket[] = []) {
    this.sockets = sockets.map((socket) => socket as unknown as WebSocket);
  }

  async get<T = unknown>(key: string): Promise<T | undefined> {
    return this.values.get(key) as T | undefined;
  }

  async put<T = unknown>(key: string, value: T): Promise<void> {
    this.values.set(key, value);
  }

  async delete(key: string): Promise<boolean> {
    return this.values.delete(key);
  }

  async setAlarm(timestamp: number | Date): Promise<void> {
    this.alarmAt = timestamp instanceof Date ? timestamp.getTime() : timestamp;
  }

  async getAlarm(): Promise<number | null> {
    return this.alarmAt;
  }

  acceptWebSocket(socket: WebSocket): void {
    this.sockets.push(socket);
  }

  getWebSockets(): WebSocket[] {
    return [...this.sockets];
  }
}

function fakeState(storage: FakeStorage): DurableObjectState {
  return {
    id: { toString: () => "workspace-hub-test" },
    storage,
    waitUntil() {},
    async blockConcurrencyWhile<T>(callback: () => Promise<T>): Promise<T> { return callback(); },
  };
}

function socketAttachment(
  deviceId: string,
  cursor = 0,
  expiresAt = NOW + 60_000,
): Record<string, unknown> {
  return {
    workspaceId: IDS.workspaceA,
    deviceId,
    capabilityId: IDS.capabilityA,
    expiresAt,
    cursor,
  };
}

function internalHeaders(principal: CapabilityPrincipal): Headers {
  return new Headers({
    "Content-Type": "application/json",
    "X-Shinsou-Internal-Capability": principal.capabilityId,
    "X-Shinsou-Internal-Workspace": principal.workspaceId,
    "X-Shinsou-Internal-Device": principal.deviceId,
    "X-Shinsou-Internal-User": principal.userId,
    "X-Shinsou-Internal-Role": principal.role,
    "X-Shinsou-Internal-Device-Epoch": String(principal.deviceAuthEpoch),
    "X-Shinsou-Internal-Membership-Epoch": String(principal.membershipAuthEpoch),
    "X-Shinsou-Internal-Key-Epoch": String(principal.keyEpoch),
    "X-Shinsou-Internal-Signing-Key": principal.signingPublicKey,
    "X-Shinsou-Internal-Expires": String(principal.expiresAt),
  });
}

async function signedEventFixture(now: number): Promise<{
  db: ReturnType<typeof database>;
  principal: CapabilityPrincipal;
  body: string;
}> {
  const db = database();
  seedTenant(db, now);
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const signingPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)),
  );
  db.exec("DROP TRIGGER devices_identity_keys_immutable");
  db.prepare("UPDATE devices SET signing_public_key = ? WHERE device_id = ?")
    .run(signingPublicKey, IDS.deviceA);
  const principal: CapabilityPrincipal = {
    ...capability(now),
    signingPublicKey,
  };
  const ciphertext = Uint8Array.of(1, 2, 3, 4);
  const ciphertextSha256 = await sha256Base64Url(ciphertext);
  const headerCbor = encodeCanonicalCbor({
    envelopeVersion: 1,
    protocolVersion: 1,
    schemaVersion: 1,
    cipherSuite: "CHACHA20_POLY1305",
    nonce: encodeBase64Url(new Uint8Array(12).fill(7)),
    instanceId: IDS.instance,
    workspaceId: IDS.workspaceA,
    eventId: IDS.eventA,
    deviceId: IDS.deviceA,
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
  return {
    db,
    principal,
    body: JSON.stringify({
      headerCbor: encodeBase64Url(headerCbor),
      ciphertext: encodeBase64Url(ciphertext),
      ciphertextSha256,
      signature,
    }),
  };
}

test("WorkspaceHub never broadcasts a D1 commit failure or a duplicate append", async () => {
  const now = Date.now();
  const failed = await signedEventFixture(now);
  const failedSocket = new FakeSocket(socketAttachment(IDS.deviceA, 0, now + 60_000));
  const failedStorage = new FakeStorage([failedSocket]);
  const failedEnv = baseEnv(failed.db);
  failedEnv.DB = failEventCommit(failedEnv.DB);
  const failedHub = new WorkspaceHub(fakeState(failedStorage), failedEnv);
  const failedResponse = await failedHub.fetch(new Request("https://hub.internal/append", {
    method: "POST",
    headers: internalHeaders(failed.principal),
    body: failed.body,
  }));
  assert.equal(failedResponse.status, 503);
  assert.equal(failedSocket.messages.length, 0);
  assert.equal(failed.db.prepare("SELECT head_seq FROM workspaces WHERE workspace_id = ?")
    .get(IDS.workspaceA)?.head_seq, 0);

  const duplicate = await signedEventFixture(now);
  const duplicateSocket = new FakeSocket(socketAttachment(IDS.deviceA, 0, now + 60_000));
  const duplicateHub = new WorkspaceHub(
    fakeState(new FakeStorage([duplicateSocket])),
    baseEnv(duplicate.db),
  );
  const append = () => duplicateHub.fetch(new Request("https://hub.internal/append", {
    method: "POST",
    headers: internalHeaders(duplicate.principal),
    body: duplicate.body,
  }));
  assert.equal((await append()).status, 201);
  assert.equal(duplicateSocket.messages.length, 1);
  assert.equal(JSON.parse(duplicateSocket.messages[0]).type, "event");
  assert.equal((await append()).status, 200);
  assert.equal(duplicateSocket.messages.length, 1, "dedup receipt must not create a second fan-out");
  assert.equal(duplicate.db.prepare("SELECT head_seq FROM workspaces WHERE workspace_id = ?")
    .get(IDS.workspaceA)?.head_seq, 1);
});

test("WorkspaceHub targeted invalidation disconnects only the revoked device", async () => {
  const revoked = new FakeSocket(socketAttachment(IDS.deviceA));
  const survivor = new FakeSocket(socketAttachment(IDS.deviceA2));
  const hub = new WorkspaceHub(
    fakeState(new FakeStorage([revoked, survivor])),
    baseEnv(),
  );
  const response = await hub.fetch(new Request("https://hub.internal/invalidate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ targetDeviceId: IDS.deviceA }),
  }));

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { disconnected: 1 });
  assert.deepEqual(revoked.messages.map((message) => JSON.parse(message)), [{ type: "reauthRequired" }]);
  assert.deepEqual(revoked.closes, [{ code: 4001, reason: "capability invalidated" }]);
  assert.equal(survivor.messages.length, 0);
  assert.equal(survivor.closes.length, 0);
});

test("a rehydrated WorkspaceHub preserves attachments for ping, cursor resync, and expiry alarms", async () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, r2_key,
    ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at, promoted_at
  ) VALUES (?, 4, ?, 1, x'a0', 1, 1, 'AES_256_GCM', 'nonce',
    'r2/stable', 'stable-hash', 1, ?, 'signature', 'stable', ?, ?)`).run(
    IDS.workspaceA,
    IDS.checkpointA,
    IDS.deviceA,
    NOW,
    NOW,
  );
  db.prepare(`UPDATE workspaces SET head_seq = 8, gc_before_seq = 5,
    stable_checkpoint_id = ? WHERE workspace_id = ?`).run(IDS.checkpointA, IDS.workspaceA);

  const active = new FakeSocket(socketAttachment(IDS.deviceA, 1, Date.now() + 60_000));
  const expired = new FakeSocket(socketAttachment(IDS.deviceA2, 0, 0));
  const storage = new FakeStorage([active, expired]);
  const state = fakeState(storage);

  // No constructor-local connection map is populated: this instance sees only attachments restored
  // by getWebSockets(), matching a Durable Object waking from hibernation.
  const rehydratedHub = new WorkspaceHub(state, baseEnv(db));
  await rehydratedHub.webSocketMessage(active as unknown as WebSocket, JSON.stringify({ type: "ping" }));
  assert.equal(JSON.parse(active.messages[0]).type, "pong");

  await rehydratedHub.webSocketMessage(
    active as unknown as WebSocket,
    JSON.stringify({ type: "ack", workspaceSeq: 2 }),
  );
  assert.deepEqual(JSON.parse(active.messages[1]), {
    type: "resyncRequired",
    stableCheckpointSeq: 4,
    headSeq: 8,
  });
  assert.equal((active.deserializeAttachment() as { cursor: number }).cursor, 2);

  await rehydratedHub.alarm();
  assert.deepEqual(JSON.parse(expired.messages[0]), { type: "reauthRequired" });
  assert.deepEqual(expired.closes, [{ code: 4001, reason: "capability expired" }]);
  assert.equal(storage.alarmAt, (active.deserializeAttachment() as { expiresAt: number }).expiresAt);
});
