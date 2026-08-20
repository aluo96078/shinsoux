import test from "node:test";
import assert from "node:assert/strict";
import { loadWorkspaceDeviceDirectory } from "../src/api/device-directory.ts";
import { listDevices } from "../src/api/identity.ts";
import { catchUpEvents, workspaceBootstrap } from "../src/api/events.ts";
import {
  canonicalJson,
  domainSeparatedMessage,
  encodeBase64Url,
  hashSecret,
  verifyEd25519,
} from "../src/crypto/base.ts";
import { utf8Bytes } from "../src/http.ts";
import type { CapabilityPrincipal, Env } from "../src/runtime.ts";
import { APPEND_EVENT_SQL } from "../src/storage/sql.ts";
import { database, IDS, numberedGet, seedTenant, sqliteD1 } from "./helpers.ts";

function envForDirectory(db: ReturnType<typeof database>): Env {
  return {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "unused",
    TOKEN_PEPPER: "unused",
    RATE_LIMIT_SALT: "unused",
    INSTANCE_ID: IDS.instance,
  };
}

test("bootstrap directory returns both immutable public keys and signed enrollment evidence", async () => {
  const db = database();
  seedTenant(db);
  const manifest = canonicalJson({
    deviceId: IDS.deviceA,
    signingPublicKey: "sign-a",
    wrappingPublicKey: "wrap-a",
    tokenCommitment: "token-commitment",
  });
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', ?, 'signature', 1)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA, manifest);
  const directory = await loadWorkspaceDeviceDirectory(envForDirectory(db), IDS.workspaceA);
  assert.equal(directory.version, 1);
  assert.equal(directory.devices.length, 1);
  assert.equal(directory.devices[0].signingPublicKey, "sign-a");
  assert.equal(directory.devices[0].wrappingPublicKey, "wrap-a");
  assert.equal(directory.devices[0].attestation.manifestJson, manifest);
  assert.match(directory.hash, /^[A-Za-z0-9_-]{43}$/);
});

test("sender subset keeps the full pinned directory hash", async () => {
  const db = database();
  seedTenant(db);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', '{}', 'signature', 1)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA);
  const env = envForDirectory(db);
  const full = await loadWorkspaceDeviceDirectory(env, IDS.workspaceA);
  const subset = await loadWorkspaceDeviceDirectory(env, IDS.workspaceA, new Set([IDS.deviceA]));
  assert.equal(subset.hash, full.hash);
  assert.equal(subset.version, full.version);
  assert.equal(subset.allDeviceCount, full.allDeviceCount);
});

test("device public keys and enrollment attestation cannot be rewritten", () => {
  const db = database();
  seedTenant(db);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', '{}', 'signature', 1)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA);
  assert.throws(
    () => db.prepare("UPDATE devices SET signing_public_key = 'substituted' WHERE device_id = ?").run(IDS.deviceA),
    /device_identity_immutable/,
  );
  assert.throws(
    () => db.prepare("UPDATE device_key_attestations SET signature = 'forged' WHERE device_id = ?").run(IDS.deviceA),
    /device_attestation_immutable/,
  );
});

test("a pairing/recovery attestation is verifiable using an already pinned attestor key", async () => {
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const publicKey = encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)));
  const manifest = canonicalJson({
    candidateDeviceId: IDS.deviceA2,
    candidateSigningPublicKey: "candidate-signing-key",
    candidateWrappingPublicKey: "candidate-wrapping-key",
    tokenCommitment: "candidate-token-commitment",
  });
  const message = domainSeparatedMessage("pairing-approval", utf8Bytes(manifest));
  const signature = encodeBase64Url(new Uint8Array(
    await crypto.subtle.sign("Ed25519", keys.privateKey, message),
  ));
  assert.equal(await verifyEd25519(publicKey, signature, message), true);
  assert.equal(await verifyEd25519(
    publicKey,
    signature,
    domainSeparatedMessage("pairing-approval", utf8Bytes(`${manifest}tampered`)),
  ), false);
});

test("GET devices exposes both public keys with their immutable attestation", async () => {
  const db = database();
  seedTenant(db);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', '{}', 'signature', 1)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA);
  const env = envForDirectory(db);
  env.TOKEN_PEPPER = "test-pepper";
  const accessToken = encodeBase64Url(new Uint8Array(32).fill(7));
  const tokenHash = await hashSecret(accessToken, env.TOKEN_PEPPER, "access-token");
  db.prepare(`INSERT INTO access_tokens(
    access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 1, 2000000, 1)`)
    .run(IDS.challenge, IDS.deviceA, tokenHash);
  const result = await listDevices(new Request("https://sync.test/v1/devices", {
    headers: { Authorization: `Bearer ${accessToken}` },
  }), env, 1_000_000) as { devices: Array<Record<string, unknown>> };
  assert.equal(result.devices[0].signingPublicKey, "sign-a");
  assert.equal(result.devices[0].wrappingPublicKey, "wrap-a");
  assert.equal((result.devices[0].attestation as Record<string, unknown>).signatureDomain, "initial-workspace-claim");
});

test("catch-up returns an attested sender subset and bootstrap returns the full pinned directory", async () => {
  const db = database();
  seedTenant(db);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', '{}', 'signature', 1)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA);
  numberedGet(
    db,
    APPEND_EVENT_SQL,
    IDS.workspaceA,
    IDS.eventA,
    IDS.deviceA,
    1,
    1,
    Uint8Array.of(0xa0),
    Uint8Array.of(1, 2, 3),
    "event-hash",
    "event-signature",
    3,
    1_000_001,
    IDS.capabilityA,
  );
  const env = envForDirectory(db);
  const principal: CapabilityPrincipal = {
    capabilityId: IDS.capabilityA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 1,
    signingPublicKey: "sign-a",
    expiresAt: 2_000_000,
  };
  const page = await catchUpEvents(
    new Request(`https://sync.test/v1/workspaces/${IDS.workspaceA}/events?after=0`),
    env,
    principal,
  ) as Record<string, unknown>;
  assert.equal((page.senderDevices as unknown[]).length, 1);
  assert.equal(page.deviceDirectoryVersion, 1);
  assert.match(String(page.deviceDirectoryHash), /^[A-Za-z0-9_-]{43}$/);
  const bootstrap = await workspaceBootstrap(env, principal) as Record<string, unknown>;
  const directory = bootstrap.deviceDirectory as { devices: unknown[]; hash: string };
  assert.equal(directory.devices.length, 1);
  assert.equal(directory.hash, page.deviceDirectoryHash);
});
