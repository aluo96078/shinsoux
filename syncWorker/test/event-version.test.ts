import assert from "node:assert/strict";
import test from "node:test";
import { appendEvent } from "../src/api/events.ts";
import { encodeBase64Url, sha256Base64Url } from "../src/crypto/base.ts";
import { encodeCanonicalCbor } from "../src/crypto/cbor.ts";
import { ApiError } from "../src/http.ts";
import type { CapabilityPrincipal, Env } from "../src/runtime.ts";
import { IDS } from "./helpers.ts";

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

async function eventRequest(protocolVersion: number, schemaVersion: number): Promise<Request> {
  const ciphertext = Uint8Array.of(1, 2, 3);
  const headerCbor = encodeCanonicalCbor({
    envelopeVersion: 1,
    protocolVersion,
    schemaVersion,
    cipherSuite: "CHACHA20_POLY1305",
    nonce: encodeBase64Url(new Uint8Array(12)),
    instanceId: IDS.instance,
    workspaceId: IDS.workspaceA,
    eventId: IDS.eventA,
    deviceId: IDS.deviceA,
    deviceSeq: 1,
    keyEpoch: 1,
  });
  return new Request(`https://sync.test/v1/workspaces/${IDS.workspaceA}/events`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      headerCbor: encodeBase64Url(headerCbor),
      ciphertext: encodeBase64Url(ciphertext),
      ciphertextSha256: await sha256Base64Url(ciphertext),
      signature: "A".repeat(86),
    }),
  });
}

test("event append rejects out-of-range protocol and schema writers before D1", async () => {
  let storageAccessed = false;
  const env = {
    INSTANCE_ID: IDS.instance,
    DB: {
      prepare() {
        storageAccessed = true;
        throw new Error("D1 must not be reached for an incompatible writer");
      },
    },
  } as unknown as Env;

  for (const [protocolVersion, schemaVersion, expectedCode] of [
    [2, 1, "protocol_write_version_incompatible"],
    [1, 2, "schema_write_version_incompatible"],
  ] as const) {
    await assert.rejects(
      appendEvent(await eventRequest(protocolVersion, schemaVersion), env, principal),
      (error: unknown) => error instanceof ApiError && error.status === 409 && error.code === expectedCode,
    );
  }
  assert.equal(storageAccessed, false);
});
