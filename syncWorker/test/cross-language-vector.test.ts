import test from "node:test";
import assert from "node:assert/strict";
import type { DatabaseSync } from "node:sqlite";
import {
  appendEvent,
  catchUpEvents,
} from "../src/api/events.ts";
import {
  commitCheckpoint,
  createCheckpointLease,
  downloadCheckpoint,
  uploadCheckpoint,
} from "../src/api/checkpoints.ts";
import {
  canonicalJson,
  decodeBase64Url,
  encodeBase64Url,
  sha256Base64Url,
  verifyEd25519,
} from "../src/crypto/base.ts";
import { encodeCanonicalCbor } from "../src/crypto/cbor.ts";
import {
  envelopeSignatureMessage,
  parseCheckpointHeader,
  parseEventHeader,
} from "../src/protocol.ts";
import type {
  CapabilityPrincipal,
  Env,
  R2Bucket,
  R2ObjectBody,
} from "../src/runtime.ts";
import { database, sqliteD1 } from "./helpers.ts";

const NOW = 1_700_000_000_000;
const INSTANCE_ID = "11111111-1111-4111-8111-111111111111";
const USER_ID = "22222222-2222-4222-8222-222222222222";
const WORKSPACE_ID = "33333333-3333-4333-8333-333333333333";
const DEVICE_ID = "44444444-4444-4444-8444-444444444444";
const EVENT_ID = "55555555-5555-4555-8555-555555555555";
const CHECKPOINT_ID = "66666666-6666-4666-8666-666666666666";
const CAPABILITY_ID = "77777777-7777-4777-8777-777777777777";
const RECOVERY_CHALLENGE_ID = CAPABILITY_ID;
const DEVICE_SIGNING_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const DEVICE_WRAPPING_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
const PREVIOUS_RECOVERY_SIGNING_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI";
const NEXT_RECOVERY_SIGNING_KEY = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
const RECOVERY_WRAPPING_KEY = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ";
const INITIAL_RECOVERY_TRUST_CANONICAL_JSON =
  `{"deviceId":"${DEVICE_ID}","instanceId":"${INSTANCE_ID}",` +
  `"recoverySigningPublicKey":"${PREVIOUS_RECOVERY_SIGNING_KEY}",` +
  `"recoveryWrappingPublicKey":"${RECOVERY_WRAPPING_KEY}",` +
  `"signingPublicKey":"${DEVICE_SIGNING_KEY}","userId":"${USER_ID}",` +
  `"workspaceId":"${WORKSPACE_ID}","wrappingPublicKey":"${DEVICE_WRAPPING_KEY}"}`;
const RECOVERY_LINEAGE_CANONICAL_JSON =
  `{"challengeId":"${RECOVERY_CHALLENGE_ID}","deviceId":"${DEVICE_ID}",` +
  `"deviceSigningPublicKey":"${DEVICE_SIGNING_KEY}",` +
  `"deviceWrappingPublicKey":"${DEVICE_WRAPPING_KEY}","instanceId":"${INSTANCE_ID}",` +
  `"newRecoverySigningPublicKey":"${NEXT_RECOVERY_SIGNING_KEY}",` +
  `"newRecoveryWrappingPublicKey":"${RECOVERY_WRAPPING_KEY}",` +
  `"previousRecoverySigningPublicKey":"${PREVIOUS_RECOVERY_SIGNING_KEY}",` +
  `"userId":"${USER_ID}"}`;

const PUBLIC_KEY_HEX = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
const PUBLIC_KEY_BASE64_URL = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo";
const PRIVATE_KEY_SEED_HEX = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
const ED25519_PKCS8_PREFIX_HEX = "302e020100300506032b657004220420";

const EVENT_HEADER_VALUES = {
  envelopeVersion: 1,
  protocolVersion: 1,
  schemaVersion: 1,
  cipherSuite: "CHACHA20_POLY1305",
  nonce: "AAECAwQFBgcICQoL",
  instanceId: INSTANCE_ID,
  workspaceId: WORKSPACE_ID,
  eventId: EVENT_ID,
  deviceId: DEVICE_ID,
  deviceSeq: 1,
  keyEpoch: 3,
};

const CHECKPOINT_HEADER_VALUES = {
  envelopeVersion: 1,
  protocolVersion: 1,
  schemaVersion: 1,
  cipherSuite: "CHACHA20_POLY1305",
  nonce: "AAAAAAAAAAAAAAAA",
  instanceId: INSTANCE_ID,
  workspaceId: WORKSPACE_ID,
  checkpointId: CHECKPOINT_ID,
  deviceId: DEVICE_ID,
  throughWorkspaceSeq: 42,
  keyEpoch: 3,
  previousStableCheckpointHash: "",
  compression: "LZ4_BLOCK_V1",
  uncompressedSize: 1024,
  stateFormat: "sync-state-v1",
};

/** The same exact strings are asserted by CrossLanguageProtocolVectorTest on every KMP target. */
test("Kotlin and Worker recovery trust manifests have identical canonical JSON", () => {
  const initialTrust = canonicalJson({
    instanceId: INSTANCE_ID,
    userId: USER_ID,
    workspaceId: WORKSPACE_ID,
    deviceId: DEVICE_ID,
    signingPublicKey: DEVICE_SIGNING_KEY,
    wrappingPublicKey: DEVICE_WRAPPING_KEY,
    recoverySigningPublicKey: PREVIOUS_RECOVERY_SIGNING_KEY,
    recoveryWrappingPublicKey: RECOVERY_WRAPPING_KEY,
  });
  const lineage = canonicalJson({
    instanceId: INSTANCE_ID,
    userId: USER_ID,
    challengeId: RECOVERY_CHALLENGE_ID,
    deviceId: DEVICE_ID,
    deviceSigningPublicKey: DEVICE_SIGNING_KEY,
    deviceWrappingPublicKey: DEVICE_WRAPPING_KEY,
    previousRecoverySigningPublicKey: PREVIOUS_RECOVERY_SIGNING_KEY,
    newRecoverySigningPublicKey: NEXT_RECOVERY_SIGNING_KEY,
    newRecoveryWrappingPublicKey: RECOVERY_WRAPPING_KEY,
  });

  assert.equal(initialTrust, INITIAL_RECOVERY_TRUST_CANONICAL_JSON);
  assert.equal(lineage, RECOVERY_LINEAGE_CANONICAL_JSON);
});

/** The same fixture is asserted by CrossLanguageProtocolVectorTest on every KMP target. */
test("Kotlin and Worker envelope vectors have identical canonical and Ed25519 bytes", async () => {
  const eventHeader = encodeCanonicalCbor(EVENT_HEADER_VALUES);
  const checkpointHeader = encodeCanonicalCbor(CHECKPOINT_HEADER_VALUES);
  const eventCiphertext = hexBytes(EVENT_CIPHERTEXT_HEX);
  const checkpointCiphertext = hexBytes(CHECKPOINT_CIPHERTEXT_HEX);

  assert.equal(hex(hexBytes(PUBLIC_KEY_HEX)), PUBLIC_KEY_HEX);
  assert.equal(encodeBase64Url(hexBytes(PUBLIC_KEY_HEX)), PUBLIC_KEY_BASE64_URL);
  assert.equal(hex(eventHeader), EVENT_HEADER_HEX);
  assert.equal(hex(checkpointHeader), CHECKPOINT_HEADER_HEX);
  assert.deepEqual(parseEventHeader(eventHeader), EVENT_HEADER_VALUES);
  assert.deepEqual(parseCheckpointHeader(checkpointHeader), CHECKPOINT_HEADER_VALUES);
  assert.equal(await sha256Base64Url(eventCiphertext), EVENT_CIPHERTEXT_SHA256_BASE64_URL);
  assert.equal(await sha256Base64Url(checkpointCiphertext), CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL);

  const privateKey = await importFixturePrivateKey();
  for (const vector of [
    {
      kind: "event" as const,
      domainHex: EVENT_ENVELOPE_DOMAIN_HEX,
      header: eventHeader,
      headerHex: EVENT_HEADER_HEX,
      hashBase64Url: EVENT_CIPHERTEXT_SHA256_BASE64_URL,
      hashHex: EVENT_CIPHERTEXT_SHA256_HEX,
      signatureBase64Url: EVENT_SIGNATURE_BASE64_URL,
    },
    {
      kind: "checkpoint" as const,
      domainHex: CHECKPOINT_ENVELOPE_DOMAIN_HEX,
      header: checkpointHeader,
      headerHex: CHECKPOINT_HEADER_HEX,
      hashBase64Url: CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL,
      hashHex: CHECKPOINT_CIPHERTEXT_SHA256_HEX,
      signatureBase64Url: CHECKPOINT_SIGNATURE_BASE64_URL,
    },
  ]) {
    const hash = decodeBase64Url(vector.hashBase64Url, "fixture_hash");
    const message = envelopeSignatureMessage(vector.kind, vector.header, hash);
    assert.equal(hex(message), vector.domainHex + vector.headerHex + vector.hashHex);
    const signature = new Uint8Array(await crypto.subtle.sign("Ed25519", privateKey, message));
    assert.equal(encodeBase64Url(signature), vector.signatureBase64Url);
    assert.equal(
      await verifyEd25519(PUBLIC_KEY_BASE64_URL, vector.signatureBase64Url, message),
      true,
    );
  }
});

test("Worker preserves exact KMP envelope bytes through D1 and R2", async () => {
  const db = database();
  seedVectorTenant(db);
  const bucket = new MemoryR2Bucket();
  const env = environment(db, bucket);
  const principal = vectorPrincipal();
  const eventHeader = hexBytes(EVENT_HEADER_HEX);
  const eventCiphertext = hexBytes(EVENT_CIPHERTEXT_HEX);
  const checkpointHeader = hexBytes(CHECKPOINT_HEADER_HEX);
  const checkpointCiphertext = hexBytes(CHECKPOINT_CIPHERTEXT_HEX);

  const lease = await createCheckpointLease(
    jsonRequest(`https://sync.test/v1/workspaces/${WORKSPACE_ID}/checkpoint-leases`, {
      checkpointId: CHECKPOINT_ID,
      ciphertextSha256: CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL,
      expectedByteSize: checkpointCiphertext.byteLength,
      throughWorkspaceSeq: 42,
    }),
    env,
    principal,
    NOW,
  ) as { throughWorkspaceSeq: number; keyEpoch: number };
  assert.equal(lease.throughWorkspaceSeq, 42);
  assert.equal(lease.keyEpoch, 3);

  const receipt = await appendEvent(
    jsonRequest(`https://sync.test/v1/workspaces/${WORKSPACE_ID}/events`, {
      headerCbor: encodeBase64Url(eventHeader),
      ciphertext: encodeBase64Url(eventCiphertext),
      ciphertextSha256: EVENT_CIPHERTEXT_SHA256_BASE64_URL,
      signature: EVENT_SIGNATURE_BASE64_URL,
    }),
    env,
    principal,
    NOW + 1,
  );
  assert.equal(receipt.workspaceSeq, 43);

  const storedEvent = db.prepare(`
    SELECT authenticated_header_cbor AS headerCbor, ciphertext, ciphertext_sha256 AS hash,
           signature FROM events WHERE workspace_id = ? AND event_id = ?
  `).get(WORKSPACE_ID, EVENT_ID) as Record<string, unknown> | undefined;
  assert.ok(storedEvent);
  assert.equal(hex(storedEvent.headerCbor as Uint8Array), EVENT_HEADER_HEX);
  assert.equal(hex(storedEvent.ciphertext as Uint8Array), EVENT_CIPHERTEXT_HEX);
  assert.equal(storedEvent.hash, EVENT_CIPHERTEXT_SHA256_BASE64_URL);
  assert.equal(storedEvent.signature, EVENT_SIGNATURE_BASE64_URL);

  const caughtUp = await catchUpEvents(
    new Request(`https://sync.test/v1/workspaces/${WORKSPACE_ID}/events?after=42&until=43`),
    env,
    principal,
  ) as { events: Array<Record<string, unknown>> };
  assert.equal(caughtUp.events.length, 1);
  assert.deepEqual(caughtUp.events[0], {
    workspaceSeq: 43,
    eventId: EVENT_ID,
    deviceId: DEVICE_ID,
    deviceSeq: 1,
    keyEpoch: 3,
    headerCbor: encodeBase64Url(eventHeader),
    ciphertext: encodeBase64Url(eventCiphertext),
    ciphertextSha256: EVENT_CIPHERTEXT_SHA256_BASE64_URL,
    signature: EVENT_SIGNATURE_BASE64_URL,
    createdAt: NOW + 1,
  });

  await uploadCheckpoint(
    new Request(`https://sync.test/v1/workspaces/${WORKSPACE_ID}/checkpoints/${CHECKPOINT_ID}`, {
      method: "PUT",
      headers: {
        "X-Shinsou-Header-Cbor": encodeBase64Url(checkpointHeader),
        "X-Shinsou-Ciphertext-Sha256": CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL,
        "X-Shinsou-Device-Signature": CHECKPOINT_SIGNATURE_BASE64_URL,
      },
      body: checkpointCiphertext,
    }),
    env,
    principal,
    CHECKPOINT_ID,
    NOW + 2,
  );

  const r2Object = bucket.onlyObject();
  assert.equal(hex(r2Object.bytes), CHECKPOINT_CIPHERTEXT_HEX);
  assert.equal(r2Object.customMetadata.headerCbor, encodeBase64Url(checkpointHeader));
  assert.equal(r2Object.customMetadata.deviceSignature, CHECKPOINT_SIGNATURE_BASE64_URL);
  assert.equal(r2Object.customMetadata.ciphertextSha256, CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL);

  await commitCheckpoint(
    jsonRequest(
      `https://sync.test/v1/workspaces/${WORKSPACE_ID}/checkpoints/${CHECKPOINT_ID}/commit`,
      { ciphertextSha256: CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL },
    ),
    env,
    principal,
    CHECKPOINT_ID,
    NOW + 3,
  );

  const storedCheckpoint = db.prepare(`
    SELECT authenticated_header_cbor AS headerCbor, ciphertext_sha256 AS hash,
           device_signature AS signature FROM checkpoints
    WHERE workspace_id = ? AND checkpoint_id = ?
  `).get(WORKSPACE_ID, CHECKPOINT_ID) as Record<string, unknown> | undefined;
  assert.ok(storedCheckpoint);
  assert.equal(hex(storedCheckpoint.headerCbor as Uint8Array), CHECKPOINT_HEADER_HEX);
  assert.equal(storedCheckpoint.hash, CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL);
  assert.equal(storedCheckpoint.signature, CHECKPOINT_SIGNATURE_BASE64_URL);

  const download = await downloadCheckpoint(env, principal, CHECKPOINT_ID);
  assert.equal(download.headers.get("X-Shinsou-Header-Cbor"), encodeBase64Url(checkpointHeader));
  assert.equal(
    download.headers.get("X-Shinsou-Ciphertext-Sha256"),
    CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL,
  );
  assert.equal(download.headers.get("X-Shinsou-Device-Signature"), CHECKPOINT_SIGNATURE_BASE64_URL);
  assert.equal(hex(new Uint8Array(await download.arrayBuffer())), CHECKPOINT_CIPHERTEXT_HEX);

  const downloadedMessage = envelopeSignatureMessage(
    "checkpoint",
    decodeBase64Url(download.headers.get("X-Shinsou-Header-Cbor") ?? "", "download_header"),
    decodeBase64Url(
      download.headers.get("X-Shinsou-Ciphertext-Sha256") ?? "",
      "download_hash",
    ),
  );
  assert.equal(
    await verifyEd25519(
      PUBLIC_KEY_BASE64_URL,
      download.headers.get("X-Shinsou-Device-Signature") ?? "",
      downloadedMessage,
    ),
    true,
  );
});

function seedVectorTenant(db: DatabaseSync): void {
  db.prepare(`INSERT INTO installation(instance_id, schema_version, initialized_at, signup_mode)
    VALUES (?, 1, ?, 'invite_only')`).run(INSTANCE_ID, NOW);
  db.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
    VALUES (?, 'Vector user', 'admin', 'active', 'private-default', ?)`).run(USER_ID, NOW);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'KMP vector device', 'ios', ?, ?, 'vector-device-token', 'active', 1, ?)`)
    .run(DEVICE_ID, USER_ID, PUBLIC_KEY_BASE64_URL, PUBLIC_KEY_BASE64_URL, NOW);
  db.prepare(`INSERT INTO workspaces(
    workspace_id, owner_user_id, status, active_key_epoch, head_seq, created_at
  ) VALUES (?, ?, 'active', 3, 42, ?)`).run(WORKSPACE_ID, USER_ID, NOW);
  db.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
    VALUES (?, ?, 'owner', 'active', 1)`).run(WORKSPACE_ID, USER_ID);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
    signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', NULL, ?, 'initial-workspace-claim', '{}', 'fixture-attestation', ?)`)
    .run(DEVICE_ID, WORKSPACE_ID, PUBLIC_KEY_BASE64_URL, NOW);
  db.prepare(`INSERT INTO workspace_capabilities(
    capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
    membership_auth_epoch, key_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 'vector-capability-token', 1, 1, 3, ?, ?)`)
    .run(CAPABILITY_ID, WORKSPACE_ID, DEVICE_ID, NOW + 1_000_000, NOW);
}

function environment(db: DatabaseSync, checkpoints: R2Bucket): Env {
  return {
    DB: sqliteD1(db),
    CHECKPOINTS: checkpoints,
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID,
    REALTIME_ENABLED: "false",
  };
}

function vectorPrincipal(): CapabilityPrincipal {
  return {
    capabilityId: CAPABILITY_ID,
    workspaceId: WORKSPACE_ID,
    deviceId: DEVICE_ID,
    userId: USER_ID,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 3,
    signingPublicKey: PUBLIC_KEY_BASE64_URL,
    expiresAt: NOW + 1_000_000,
  };
}

function jsonRequest(url: string, body: unknown): Request {
  return new Request(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function importFixturePrivateKey(): Promise<CryptoKey> {
  const pkcs8 = hexBytes(ED25519_PKCS8_PREFIX_HEX + PRIVATE_KEY_SEED_HEX);
  return crypto.subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, false, ["sign"]);
}

interface StoredR2Object {
  key: string;
  bytes: Uint8Array;
  customMetadata: Record<string, string>;
}

class MemoryR2Bucket implements R2Bucket {
  private readonly objects = new Map<string, StoredR2Object>();

  onlyObject(): StoredR2Object {
    assert.equal(this.objects.size, 1);
    return [...this.objects.values()][0];
  }

  async get(key: string): Promise<R2ObjectBody | null> {
    const stored = this.objects.get(key);
    if (!stored) return null;
    const bytes = stored.bytes.slice();
    return {
      key,
      size: bytes.byteLength,
      etag: "fixture-etag",
      customMetadata: { ...stored.customMetadata },
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(bytes);
          controller.close();
        },
      }),
      async arrayBuffer() {
        return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      },
    };
  }

  async head(key: string): Promise<Omit<R2ObjectBody, "body" | "arrayBuffer"> | null> {
    const stored = this.objects.get(key);
    if (!stored) return null;
    return {
      key,
      size: stored.bytes.byteLength,
      etag: "fixture-etag",
      customMetadata: { ...stored.customMetadata },
    };
  }

  async put(
    key: string,
    value: ArrayBuffer | Uint8Array | ReadableStream<Uint8Array>,
    options?: { customMetadata?: Record<string, string>; onlyIf?: { etagDoesNotMatch?: string } },
  ): Promise<{ key: string; size: number; etag: string } | null> {
    if (options?.onlyIf?.etagDoesNotMatch === "*" && this.objects.has(key)) return null;
    const bytes = value instanceof Uint8Array
      ? value.slice()
      : value instanceof ArrayBuffer
        ? new Uint8Array(value).slice()
        : new Uint8Array(await new Response(value).arrayBuffer());
    this.objects.set(key, {
      key,
      bytes,
      customMetadata: { ...options?.customMetadata },
    });
    return { key, size: bytes.byteLength, etag: "fixture-etag" };
  }

  async delete(key: string): Promise<void> {
    this.objects.delete(key);
  }
}

function hexBytes(value: string): Uint8Array {
  assert.equal(value.length % 2, 0);
  return Uint8Array.from(
    { length: value.length / 2 },
    (_, index) => Number.parseInt(value.slice(index * 2, index * 2 + 2), 16),
  );
}

function hex(value: Uint8Array): string {
  return Buffer.from(value).toString("hex");
}

const EVENT_ENVELOPE_DOMAIN_HEX = "7368696e736f753a6576656e742d656e76656c6f70653a763100";
const CHECKPOINT_ENVELOPE_DOMAIN_HEX =
  "7368696e736f753a636865636b706f696e742d656e76656c6f70653a763100";

const EVENT_CIPHERTEXT_HEX =
  "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
const EVENT_CIPHERTEXT_SHA256_HEX =
  "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd";
const EVENT_CIPHERTEXT_SHA256_BASE64_URL = "Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0";
const EVENT_SIGNATURE_BASE64_URL =
  "QYN_YY-ikZTBPoDT-BsRebwhCYMVgAr9IykNFb4_Owk5bV3XKmiCbiY0XDo1nWc-YIheROBT80T_CngcAv_-DQ";

const CHECKPOINT_CIPHERTEXT_HEX =
  "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
    "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf";
const CHECKPOINT_CIPHERTEXT_SHA256_HEX =
  "1fba3652b85f13ce1e7ff5eb1971ae6519afd809a0578cb3285daecd3e16b216";
const CHECKPOINT_CIPHERTEXT_SHA256_BASE64_URL = "H7o2UrhfE84ef_XrGXGuZRmv2AmgV4yzKF2uzT4WshY";
const CHECKPOINT_SIGNATURE_BASE64_URL =
  "Wcl5eV6h9mSAZVRaqHv_RGVBi6bLPf9raVJBHxsPT8yRAKpPqp0vMhidS3S1H3j8TT7UjdKdcm7J8FDOUUbvCQ";

const EVENT_HEADER_HEX =
  "ab656e6f6e63657041414543417751464267634943516f4c676576656e744964782435353535353535352d" +
    "353535352d343535352d383535352d353535353535353535353535686465766963654964782434343434" +
    "343434342d343434342d343434342d383434342d343434343434343434343434686b657945706f636803" +
    "69646576696365536571016a696e7374616e63654964782431313131313131312d313131312d343131312d" +
    "383131312d3131313131313131313131316b63697068657253756974657143484143484132305f504f4c59" +
    "313330356b776f726b73706163654964782433333333333333332d333333332d343333332d383333332d33" +
    "33333333333333333333336d736368656d6156657273696f6e016f656e76656c6f706556657273696f6e" +
    "016f70726f746f636f6c56657273696f6e01";

const CHECKPOINT_HEADER_HEX =
  "af656e6f6e63657041414141414141414141414141414141686465766963654964782434343434343434342d343434342d" +
  "343434342d383434342d343434343434343434343434686b657945706f6368036a696e7374616e63654964782431313131" +
  "313131312d313131312d343131312d383131312d3131313131313131313131316b63697068657253756974657143484143" +
  "484132305f504f4c59313330356b636f6d7072657373696f6e6c4c5a345f424c4f434b5f56316b7374617465466f726d" +
  "61746d73796e632d73746174652d76316b776f726b73706163654964782433333333333333332d333333332d343333332d" +
  "383333332d3333333333333333333333336c636865636b706f696e744964782436363636363636362d363636362d343636" +
  "362d383636362d3636363636363636363636366d736368656d6156657273696f6e016f656e76656c6f706556657273696f" +
  "6e016f70726f746f636f6c56657273696f6e0170756e636f6d7072657373656453697a65190400737468726f756768576f" +
  "726b7370616365536571182a781c70726576696f7573537461626c65436865636b706f696e744861736860";
