import test from "node:test";
import assert from "node:assert/strict";
import type { DatabaseSync } from "node:sqlite";
import { loadDeviceKeyEnvelopes } from "../src/api/events.ts";
import { encodeBase64Url, sha256Base64Url } from "../src/crypto/base.ts";
import { encodeCanonicalCbor, type CborValue } from "../src/crypto/cbor.ts";
import type { Env } from "../src/runtime.ts";
import { CREATE_ROTATION_LEASE_SQL } from "../src/storage/sql.ts";
import { database, IDS, numberedGet, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;
const DEVICE_WRAPPED = "device-wrapped-key-evidence";
const RECOVERY_WRAPPED = "recovery-wrapped-key-evidence";

async function committedRotation(
  db: DatabaseSync,
  mutateManifest: (manifest: Record<string, CborValue>) => void = () => {},
): Promise<void> {
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  numberedGet(
    db,
    CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA,
    IDS.workspaceA,
    NOW + 10_000,
    NOW + 1,
    IDS.deviceA,
  );
  const keyCommitment = await sha256Base64Url(new TextEncoder().encode("epoch-key"));
  const manifest: Record<string, CborValue> = {
    rotationId: IDS.rotationA,
    workspaceId: IDS.workspaceA,
    fromEpoch: 1,
    toEpoch: 2,
    proposerDeviceId: IDS.deviceA,
    proposerDeviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyCommitment,
    recipientEnvelopeHashes: {
      [IDS.deviceA]: await sha256Base64Url(new TextEncoder().encode(DEVICE_WRAPPED)),
    },
    recipientAuthEpochs: { [IDS.deviceA]: 1 },
    recoveryEnvelopeHash: await sha256Base64Url(new TextEncoder().encode(RECOVERY_WRAPPED)),
    recoveryAuthEpoch: 1,
    expiresAt: NOW + 10_000,
  };
  mutateManifest(manifest);
  const manifestCbor = encodeCanonicalCbor(manifest);
  db.prepare(`UPDATE key_rotations SET key_commitment = ?, manifest_cbor = ?,
    manifest_signature = 'manifest-signature' WHERE rotation_id = ?`)
    .run(keyCommitment, manifestCbor, IDS.rotationA);
  db.prepare(`INSERT INTO workspace_device_keys(
    workspace_id, key_epoch, device_id, rotation_id, key_commitment,
    wrapped_key, wrapped_by_device_id, signature, created_at
  ) VALUES (?, 2, ?, ?, ?, ?, ?, 'manifest-signature', ?)`)
    .run(
      IDS.workspaceA,
      IDS.deviceA,
      IDS.rotationA,
      keyCommitment,
      DEVICE_WRAPPED,
      IDS.deviceA,
      NOW + 2,
    );
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 2, ?, ?, ?, ?)`)
    .run(IDS.workspaceA, IDS.rotationA, keyCommitment, RECOVERY_WRAPPED, NOW + 2);
  db.prepare("INSERT INTO rotation_commits(rotation_id, committed_at) VALUES (?, ?)")
    .run(IDS.rotationA, NOW + 3);
}

async function load(db: DatabaseSync): Promise<Record<string, unknown>[]> {
  return await loadDeviceKeyEnvelopes(
    { DB: sqliteD1(db) } as Env,
    IDS.workspaceA,
    IDS.deviceA,
  ) as Record<string, unknown>[];
}

async function rejectsWithCode(promise: Promise<unknown>, expected: string): Promise<void> {
  await assert.rejects(promise, (error: unknown) => {
    assert.equal((error as { code?: string }).code, expected);
    return true;
  });
}

test("bootstrap exposes exact committed rotation evidence without reconstructing CBOR", async () => {
  const db = database();
  await committedRotation(db);
  const envelope = (await load(db)).at(-1) as Record<string, unknown>;
  const evidence = envelope.rotationEvidence as Record<string, unknown>;
  assert.equal(evidence.manifestCbor, encodeBase64Url(
    db.prepare("SELECT manifest_cbor FROM key_rotations WHERE rotation_id = ?")
      .get(IDS.rotationA)?.manifest_cbor as Uint8Array,
  ));
  assert.equal(evidence.proposerDeviceId, IDS.deviceA);
  assert.equal(evidence.proposerSigningPublicKey, "sign-a");
  assert.deepEqual(evidence.recipientAuthEpochs, { [IDS.deviceA]: 1 });
  assert.equal(evidence.status, "committed");
});

test("bootstrap fails closed when a committed envelope or commitment is substituted", async () => {
  const envelopeDb = database();
  await committedRotation(envelopeDb);
  envelopeDb.prepare("UPDATE workspace_device_keys SET wrapped_key = 'substituted' WHERE rotation_id = ?")
    .run(IDS.rotationA);
  await rejectsWithCode(load(envelopeDb), "rotation_evidence_recipient_binding_invalid");

  const commitmentDb = database();
  await committedRotation(commitmentDb);
  commitmentDb.prepare("UPDATE workspace_device_keys SET key_commitment = 'substituted' WHERE rotation_id = ?")
    .run(IDS.rotationA);
  await rejectsWithCode(load(commitmentDb), "rotation_evidence_binding_invalid");
});

test("bootstrap rejects a signed manifest that omits a snapshotted recipient", async () => {
  const db = database();
  await committedRotation(db, (manifest) => {
    manifest.recipientEnvelopeHashes = { [IDS.deviceA2]: "A".repeat(43) };
    manifest.recipientAuthEpochs = { [IDS.deviceA2]: 1 };
  });
  await rejectsWithCode(load(db), "rotation_evidence_recipient_binding_invalid");
});

test("bootstrap rejects rotation evidence beyond a rolled-back workspace epoch", async () => {
  const db = database();
  await committedRotation(db);
  db.prepare("UPDATE workspaces SET active_key_epoch = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  await rejectsWithCode(load(db), "rotation_evidence_binding_invalid");
});
