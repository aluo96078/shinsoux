import test from "node:test";
import assert from "node:assert/strict";
import { workspaceBootstrap } from "../src/api/events.ts";
import { loadRequiredWorkspaceKeyEpochs } from "../src/api/key-epochs.ts";
import type { CapabilityPrincipal, Env } from "../src/runtime.ts";
import { database, IDS, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;
const BLOB_ID = "00000000-0000-4000-8000-000000000030";
const MANIFEST_ID = "00000000-0000-4000-8000-000000000031";
const SESSION_ID = "00000000-0000-4000-8000-000000000032";

function insertEncryptedBlobManifest(
  db: ReturnType<typeof database>,
  currentEnvelopeEpoch: number,
  status: "committed" | "tombstoned" | "deleting" | "deleted" = "committed",
): void {
  db.prepare(`INSERT INTO blob_upload_sessions(
    session_id, workspace_id, blob_id, manifest_id, protocol_version, schema_version,
    key_epoch, uploader_device_id, initial_capability_id, chunk_size_bytes,
    chunk_plan_sha256, chunk_count, total_chunk_bytes, manifest_ciphertext_sha256,
    manifest_byte_size, reserved_bytes, status, expires_at, created_at, committed_at
  ) VALUES (?, ?, ?, ?, 2, 2, 1, ?, ?, 65536, 'chunk-plan-hash', 0, 0,
    'manifest-ciphertext-hash', 1, 1, 'committed', ?, ?, ?)`).run(
    SESSION_ID,
    IDS.workspaceA,
    BLOB_ID,
    MANIFEST_ID,
    IDS.deviceA,
    IDS.capabilityA,
    NOW + 60_000,
    NOW,
    NOW,
  );
  db.prepare(`INSERT INTO encrypted_blob_manifests(
    workspace_id, blob_id, manifest_id, session_id, protocol_version, schema_version,
    key_epoch, private_manifest_nonce, chunk_plan_sha256, chunk_count, chunk_size_bytes,
    total_chunk_bytes, manifest_ciphertext_sha256, manifest_byte_size, manifest_r2_key,
    uploader_device_id, current_envelope_epoch, status, created_at, committed_at, deleted_at
  ) VALUES (?, ?, ?, ?, 2, 2, 1, 'private-nonce', 'chunk-plan-hash', 0, 65536, 0,
    'manifest-ciphertext-hash', 1, 'blob-manifests/required-epoch', ?, ?, ?, ?, ?, ?)`).run(
    IDS.workspaceA,
    BLOB_ID,
    MANIFEST_ID,
    SESSION_ID,
    IDS.deviceA,
    currentEnvelopeEpoch,
    status,
    NOW,
    status === "committed" ? NOW : NOW - 1,
    status === "deleted" ? NOW : null,
  );
}

function environment(db: ReturnType<typeof database>): Env {
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

function principal(): CapabilityPrincipal {
  return {
    capabilityId: IDS.capabilityA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    userId: IDS.userA,
    role: "owner",
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    keyEpoch: 2,
    signingPublicKey: "sign-a",
    expiresAt: 2_000_000,
  };
}

test("bootstrap retains live blob envelope epochs and releases them after re-wrap or final deletion", async () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET active_key_epoch = 2 WHERE workspace_id = ?").run(IDS.workspaceA);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id,
    attestor_public_key, signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'initial', ?, 'sign-a', 'initial-workspace-claim', '{}', 'signature', ?)`)
    .run(IDS.deviceA, IDS.workspaceA, IDS.deviceA, NOW);
  insertEncryptedBlobManifest(db, 1);
  const env = environment(db);

  const bootstrap = await workspaceBootstrap(env, principal()) as { requiredKeyEpochs: number[] };
  assert.deepEqual(bootstrap.requiredKeyEpochs, [1, 2]);

  for (const status of ["tombstoned", "deleting"] as const) {
    db.prepare("UPDATE encrypted_blob_manifests SET status = ? WHERE workspace_id = ? AND blob_id = ?")
      .run(status, IDS.workspaceA, BLOB_ID);
    assert.deepEqual(await loadRequiredWorkspaceKeyEpochs(env.DB, IDS.workspaceA), [1, 2]);
  }

  db.prepare(`UPDATE encrypted_blob_manifests
    SET status = 'committed', current_envelope_epoch = 2
    WHERE workspace_id = ? AND blob_id = ?`).run(IDS.workspaceA, BLOB_ID);
  assert.deepEqual(await loadRequiredWorkspaceKeyEpochs(env.DB, IDS.workspaceA), [2]);

  db.prepare(`UPDATE encrypted_blob_manifests
    SET status = 'deleted', current_envelope_epoch = 1, deleted_at = ?
    WHERE workspace_id = ? AND blob_id = ?`).run(NOW + 1, IDS.workspaceA, BLOB_ID);
  assert.deepEqual(await loadRequiredWorkspaceKeyEpochs(env.DB, IDS.workspaceA), [2]);
});

test("authoritative pairing activation requires and accepts the live blob envelope epoch", () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET active_key_epoch = 3 WHERE workspace_id = ?").run(IDS.workspaceA);
  insertEncryptedBlobManifest(db, 1);
  const pairingId = "00000000-0000-4000-8000-000000000033";
  db.prepare(`INSERT INTO pairing_sessions(
    pairing_id, workspace_id, sponsor_device_id, secret_hash, transcript_nonce,
    status, expires_at, candidate_device_id, candidate_display_name, candidate_platform,
    candidate_signing_public_key, candidate_wrapping_public_key, candidate_token_hash,
    candidate_submitted_at, created_at
  ) VALUES (?, ?, ?, 'pairing-secret-hash', 'transcript-nonce', 'candidate', ?, ?,
    'New phone', 'android', 'candidate-signing', 'candidate-wrapping',
    'candidate-token-hash', ?, ?)`).run(
    pairingId,
    IDS.workspaceA,
    IDS.deviceA,
    NOW + 60_000,
    IDS.deviceA2,
    NOW,
    NOW,
  );
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'New phone', 'android', 'candidate-signing', 'candidate-wrapping',
    'candidate-token-hash', 'active', 1, ?)`).run(IDS.deviceA2, IDS.userA, NOW);
  db.prepare(`INSERT INTO device_key_attestations(
    device_id, workspace_id, attestation_type, attestor_device_id, attestor_public_key,
    signature_domain, manifest_json, signature, created_at
  ) VALUES (?, ?, 'pairing', ?, 'sign-a', 'pairing-approval', '{}', 'signature', ?)`).run(
    IDS.deviceA2,
    IDS.workspaceA,
    IDS.deviceA,
    NOW,
  );

  assert.throws(
    () => db.prepare(`INSERT INTO pairing_activations(
      pairing_id, candidate_device_id, envelope_count, activated_at
    ) VALUES (?, ?, 1, ?)`).run(pairingId, IDS.deviceA2, NOW + 1),
    /pairing_incomplete_keyring/,
  );
  assert.doesNotThrow(() => db.prepare(`INSERT INTO pairing_activations(
    pairing_id, candidate_device_id, envelope_count, activated_at
  ) VALUES (?, ?, 2, ?)`).run(pairingId, IDS.deviceA2, NOW + 2));
});
