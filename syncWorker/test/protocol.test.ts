import test from "node:test";
import assert from "node:assert/strict";
import {
  CHECKPOINT_COMPRESSION,
  checkpointObjectKey,
  classifyDeviceSequence,
  LIMITS,
  parseCheckpointHeader,
  parseEventHeader,
  validateCheckpointReplayAck,
  validateRecipientSet,
} from "../src/protocol.ts";
import { decodeCanonicalCbor, encodeCanonicalCbor } from "../src/crypto/cbor.ts";
import {
  decodeBase64Url,
  encodeBase64Url,
  sha256Base64Url,
  timingSafeEqual,
  verifyEd25519,
} from "../src/crypto/base.ts";
import { ApiError, readBytes } from "../src/http.ts";
import { IDS } from "./helpers.ts";

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
    throughWorkspaceSeq: 42,
    keyEpoch: 3,
    previousStableCheckpointHash: "",
    stateFormat: "sync-state-v1",
    compression: CHECKPOINT_COMPRESSION,
    uncompressedSize: 1024,
    ...overrides,
  };
}

test("deterministic CBOR sorts map keys by RFC 8949 canonical order", () => {
  const encoded = encodeCanonicalCbor({ aa: 2, b: 1, a: 0 });
  assert.equal(Buffer.from(encoded).toString("hex"), "a361610061620162616102");
  assert.deepEqual({ ...decodeCanonicalCbor(encoded) as object }, { a: 0, b: 1, aa: 2 });
});

test("non-minimal CBOR integer encoding is rejected", () => {
  assert.throws(
    () => decodeCanonicalCbor(Uint8Array.of(0x18, 0x01)),
    (error: unknown) => error instanceof ApiError && error.code === "non_canonical_cbor_length",
  );
});

test("base64url decoding is canonical and timing comparison handles unequal sizes", () => {
  const value = encodeBase64Url(Uint8Array.of(0, 1, 254, 255));
  assert.equal(value, "AAH-_w");
  assert.deepEqual(decodeBase64Url(value), Uint8Array.of(0, 1, 254, 255));
  assert.equal(timingSafeEqual(Uint8Array.of(1), Uint8Array.of(1, 0)), false);
  assert.throws(() => decodeBase64Url("AA=="), ApiError);
});

test("event header round-trips raw canonical bytes", () => {
  const headerBytes = encodeCanonicalCbor({
    envelopeVersion: 1,
    protocolVersion: 1,
    schemaVersion: 1,
    cipherSuite: "CHACHA20_POLY1305",
    nonce: encodeBase64Url(new Uint8Array(12)),
    instanceId: IDS.instance,
    workspaceId: IDS.workspaceA,
    eventId: IDS.eventA,
    deviceId: IDS.deviceA,
    deviceSeq: 42,
    keyEpoch: 3,
  });
  const header = parseEventHeader(headerBytes);
  assert.equal(header.deviceSeq, 42);
  assert.equal(header.keyEpoch, 3);
  assert.equal(header.workspaceId, IDS.workspaceA);
});

test("protocol v1 rejects cipher suites clients do not implement", () => {
  const aesEvent = encodeCanonicalCbor({
    envelopeVersion: 1,
    protocolVersion: 1,
    schemaVersion: 1,
    cipherSuite: "AES_256_GCM",
    nonce: encodeBase64Url(new Uint8Array(12)),
    instanceId: IDS.instance,
    workspaceId: IDS.workspaceA,
    eventId: IDS.eventA,
    deviceId: IDS.deviceA,
    deviceSeq: 1,
    keyEpoch: 1,
  });
  assert.throws(
    () => parseEventHeader(aesEvent),
    (error: unknown) => error instanceof ApiError && error.code === "unsupported_cipher_suite",
  );
  assert.throws(
    () => parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues({ cipherSuite: "AES_256_GCM" }))),
    (error: unknown) => error instanceof ApiError && error.code === "unsupported_cipher_suite",
  );
});

test("checkpoint header authenticates its exact compression contract", () => {
  const header = parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues()));
  assert.equal(header.compression, CHECKPOINT_COMPRESSION);
  assert.equal(header.uncompressedSize, 1024);

  for (const omittedField of ["compression", "uncompressedSize"]) {
    const incomplete: Record<string, string | number> = checkpointHeaderValues();
    delete incomplete[omittedField];
    assert.throws(
      () => parseCheckpointHeader(encodeCanonicalCbor(incomplete)),
      (error: unknown) => error instanceof ApiError && error.code === "unexpected_header_fields",
    );
  }
  assert.throws(
    () => parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues({ extra: "field" }))),
    (error: unknown) => error instanceof ApiError && error.code === "unexpected_header_fields",
  );
});

test("checkpoint header accepts only the fixed compression algorithm", () => {
  for (const compression of ["", "NONE", "LZ4_FRAME_V1", "lz4_block_v1"]) {
    assert.throws(
      () => parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues({ compression }))),
      (error: unknown) => error instanceof ApiError && error.code === "unsupported_checkpoint_compression",
    );
  }
});

test("checkpoint header bounds the authenticated uncompressed size", () => {
  for (const uncompressedSize of [0, -1, LIMITS.checkpointUncompressed + 1]) {
    assert.throws(
      () => parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues({ uncompressedSize }))),
      (error: unknown) => error instanceof ApiError && error.code === "invalid_header_uncompressedSize",
    );
  }
  assert.throws(
    () => parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues({ uncompressedSize: "1024" }))),
    (error: unknown) => error instanceof ApiError && error.code === "invalid_header_uncompressedSize",
  );
  assert.equal(
    parseCheckpointHeader(encodeCanonicalCbor(checkpointHeaderValues({
      uncompressedSize: LIMITS.checkpointUncompressed,
    }))).uncompressedSize,
    LIMITS.checkpointUncompressed,
  );
});

test("Ed25519 verification accepts the matching message only", async () => {
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const publicRaw = new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey));
  const message = new TextEncoder().encode("signed event");
  const signature = new Uint8Array(await crypto.subtle.sign("Ed25519", keys.privateKey, message));
  assert.equal(await verifyEd25519(encodeBase64Url(publicRaw), encodeBase64Url(signature), message), true);
  assert.equal(await verifyEd25519(
    encodeBase64Url(publicRaw),
    encodeBase64Url(signature),
    new TextEncoder().encode("tampered"),
  ), false);
});

test("device sequence classifier keeps GC-safe receipts authoritative", () => {
  assert.deepEqual(classifyDeviceSequence(0, 1, null, "h1"), { kind: "append" });
  assert.deepEqual(
    classifyDeviceSequence(7, 7, { ciphertextSha256: "h1", workspaceSeq: 99 }, "h1"),
    { kind: "duplicate", workspaceSeq: 99 },
  );
  assert.deepEqual(
    classifyDeviceSequence(7, 7, { ciphertextSha256: "h1", workspaceSeq: 99 }, "changed"),
    { kind: "replay_or_corruption" },
  );
  assert.deepEqual(classifyDeviceSequence(7, 9, null, "h"), {
    kind: "sequence_gap",
    expectedDeviceSeq: 8,
  });
});

test("checkpoint ack rejects a validator that omits a replayed event", () => {
  const facts = {
    checkpointId: IDS.checkpointA,
    checkpointHash: "hash",
    throughSeq: 10,
    expectedReplayFromSeq: 5,
    replayFromSeq: 5,
    replayedThroughSeq: 10,
    replayedEventCount: 4,
    authoritativeEventCount: 5,
    previousStableCheckpointId: IDS.checkpointB,
    previousStableHash: "old",
    assertedPreviousStableCheckpointId: IDS.checkpointB,
    assertedPreviousStableHash: "old",
  };
  assert.throws(
    () => validateCheckpointReplayAck(facts),
    (error: unknown) => error instanceof ApiError && error.code === "checkpoint_replay_incomplete",
  );
  validateCheckpointReplayAck({ ...facts, replayedEventCount: 5 });
});

test("rotation recipient comparison includes auth epochs", () => {
  validateRecipientSet(
    [{ deviceId: IDS.deviceA, authEpoch: 1 }],
    [{ deviceId: IDS.deviceA, authEpoch: 1 }],
  );
  assert.throws(
    () => validateRecipientSet(
      [{ deviceId: IDS.deviceA, authEpoch: 1 }],
      [{ deviceId: IDS.deviceA, authEpoch: 2 }],
    ),
    (error: unknown) => error instanceof ApiError && error.code === "rotation_recipient_set_changed",
  );
});

test("R2 keys can only be built from validated identities and hashes", () => {
  const key = checkpointObjectKey(IDS.workspaceA, 123, IDS.checkpointA, "a".repeat(64));
  assert.equal(
    key,
    `workspaces/${IDS.workspaceA}/checkpoints/v1/123/${IDS.checkpointA}-${"a".repeat(64)}.bin`,
  );
  assert.throws(
    () => checkpointObjectKey("../../other", 1, IDS.checkpointA, "a".repeat(64)),
    ApiError,
  );
});

test("streaming body reader enforces its hard cap without trusting Content-Length", async () => {
  const request = new Request("https://example.test", {
    method: "POST",
    body: new Uint8Array(17),
    // Node requires duplex for streaming bodies only; Uint8Array remains portable.
  });
  await assert.rejects(() => readBytes(request, 16), (error: unknown) =>
    error instanceof ApiError && error.code === "body_too_large");
});

test("sha256 helper returns a 32-byte canonical digest", async () => {
  const digest = await sha256Base64Url(new TextEncoder().encode("abc"));
  assert.equal(decodeBase64Url(digest).byteLength, 32);
  assert.equal(digest, "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0");
});
