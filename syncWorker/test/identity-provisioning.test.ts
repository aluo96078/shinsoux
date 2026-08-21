import test from "node:test";
import assert from "node:assert/strict";
import {
  approvePairing,
  claimRecovery,
  createRecoveryChallenge,
  pairingView,
  reconcileRecoveryClaim,
  setupClaim,
} from "../src/api/identity.ts";
import { issueAccessToken } from "../src/auth/service.ts";
import {
  canonicalJson,
  domainSeparatedMessage,
  encodeBase64Url,
  hashSecret,
  publicTokenCommitment,
  sha256Base64Url,
} from "../src/crypto/base.ts";
import type { Env } from "../src/runtime.ts";
import { CREATE_ROTATION_LEASE_SQL } from "../src/storage/sql.ts";
import { database, IDS, numberedGet, seedTenant, sqliteD1 } from "./helpers.ts";

const NOW = 1_000_000;
const PAIRING_ID = "00000000-0000-4000-8000-000000000013";
const PAIRING_SECRET = "cGFpcmluZy1zZWNyZXQtMzItYnl0ZXMtbG9uZyEhISE";
const BLOB_ID = "00000000-0000-4000-8000-000000000030";
const MANIFEST_ID = "00000000-0000-4000-8000-000000000031";
const SESSION_ID = "00000000-0000-4000-8000-000000000032";

function insertLiveBlobManifest(
  db: ReturnType<typeof database>,
  currentEnvelopeEpoch: number,
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
    uploader_device_id, current_envelope_epoch, status, created_at, committed_at
  ) VALUES (?, ?, ?, ?, 2, 2, 1, 'private-nonce', 'chunk-plan-hash', 0, 65536, 0,
    'manifest-ciphertext-hash', 1, 'blob-manifests/identity-required-epoch', ?, ?,
    'committed', ?, ?)`).run(
    IDS.workspaceA,
    BLOB_ID,
    MANIFEST_ID,
    SESSION_ID,
    IDS.deviceA,
    currentEnvelopeEpoch,
    NOW,
    NOW,
  );
}

test("initial claim requires a Recovery-root co-signature over the historical sender identity", async () => {
  const db = database();
  const deviceKeys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const recoveryKeys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const signingPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", deviceKeys.publicKey)),
  );
  const recoverySigningPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", recoveryKeys.publicKey)),
  );
  const wrappingPublicKey = encodeBase64Url(new Uint8Array(32).fill(31));
  const recoveryWrappingPublicKey = encodeBase64Url(new Uint8Array(32).fill(32));
  const bootstrapSecret = encodeBase64Url(new Uint8Array(32).fill(33));
  const deviceToken = encodeBase64Url(new Uint8Array(32).fill(34));
  const keyCommitment = encodeBase64Url(new Uint8Array(32).fill(35));
  const deviceWrappedKey = "initial-device-wrapped-key-0000000001";
  const recoveryWrappedKey = "initial-recovery-wrapped-key-0000001";
  const encoder = new TextEncoder();
  const recoveryTrustManifest = canonicalJson({
    instanceId: IDS.instance,
    userId: IDS.userA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    signingPublicKey,
    wrappingPublicKey,
    recoverySigningPublicKey,
    recoveryWrappingPublicKey,
  });
  const recoveryDeviceTrustSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    recoveryKeys.privateKey,
    domainSeparatedMessage("initial-device-recovery-trust", encoder.encode(recoveryTrustManifest)),
  )));
  const claimManifest = canonicalJson({
    instanceId: IDS.instance,
    userId: IDS.userA,
    workspaceId: IDS.workspaceA,
    deviceId: IDS.deviceA,
    signingPublicKey,
    wrappingPublicKey,
    deviceTokenHash: await publicTokenCommitment(deviceToken, "device_token"),
    keyEpoch: 1,
    keyCommitment,
    deviceWrappedKeyHash: await sha256Base64Url(encoder.encode(deviceWrappedKey)),
    recoverySigningPublicKey,
    recoveryWrappingPublicKey,
    recoveryWrappedKeyHash: await sha256Base64Url(encoder.encode(recoveryWrappedKey)),
    recoveryDeviceTrustSignature,
  });
  const claimSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    deviceKeys.privateKey,
    domainSeparatedMessage("initial-workspace-claim", encoder.encode(claimManifest)),
  )));
  const initialKeys = {
    keyCommitment,
    deviceWrappedKey,
    deviceEnvelopeSignature: "s".repeat(86),
    recoverySigningPublicKey,
    recoveryWrappingPublicKey,
    recoveryWrappedKey,
    recoveryDeviceTrustSignature,
  };
  const body = (trustSignature: string) => ({
    bootstrapSecret,
    userId: IDS.userA,
    workspaceId: IDS.workspaceA,
    displayName: "User A",
    device: {
      deviceId: IDS.deviceA,
      displayName: "A phone",
      platform: "ios",
      signingPublicKey,
      wrappingPublicKey,
      deviceToken,
    },
    initialKeys: { ...initialKeys, recoveryDeviceTrustSignature: trustSignature },
    claimSignature,
  });
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: bootstrapSecret,
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
  };

  const missingTrust = body(recoveryDeviceTrustSignature);
  const {
    recoveryDeviceTrustSignature: _omittedRecoveryDeviceTrustSignature,
    ...initialKeysWithoutRecoveryTrust
  } = missingTrust.initialKeys;
  await assert.rejects(
    setupClaim(new Request("https://sync.test/v1/setup/claim", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...missingTrust, initialKeys: initialKeysWithoutRecoveryTrust }),
    }), env, NOW),
    (error: unknown) => (error as { code?: string }).code === "invalid_recoveryDeviceTrustSignature",
  );
  await assert.rejects(
    setupClaim(new Request("https://sync.test/v1/setup/claim", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body(encodeBase64Url(new Uint8Array(64)))),
    }), env, NOW),
    (error: unknown) =>
      (error as { code?: string }).code === "recovery_device_trust_signature_invalid",
  );
  await setupClaim(new Request("https://sync.test/v1/setup/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body(recoveryDeviceTrustSignature)),
  }), env, NOW);
  const stored = db.prepare(`SELECT manifest_json AS manifestJson FROM device_key_attestations
    WHERE device_id = ?`).get(IDS.deviceA) as { manifestJson: string };
  assert.equal(JSON.parse(stored.manifestJson).recoveryDeviceTrustSignature, recoveryDeviceTrustSignature);
});

async function pairingEnvironment(): Promise<{ db: ReturnType<typeof database>; env: Env }> {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET active_key_epoch = 3, head_seq = 17 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  insertLiveBlobManifest(db, 1);
  const secretHash = await hashSecret(PAIRING_SECRET, "p".repeat(32), "pairing");
  db.prepare(`INSERT INTO pairing_sessions(
    pairing_id, workspace_id, sponsor_device_id, secret_hash, transcript_nonce,
    status, expires_at, candidate_device_id, candidate_display_name, candidate_platform,
    candidate_signing_public_key, candidate_wrapping_public_key, candidate_token_hash,
    candidate_submitted_at, created_at
  ) VALUES (?, ?, ?, ?, 'bm9uY2UtbG9uZy1lbm91Z2gtMzItYnl0ZXM', 'candidate', ?, ?, 'New phone', 'android',
            'candidate-signing', 'candidate-wrapping', 'candidate-token-hash', ?, ?)`)
    .run(PAIRING_ID, IDS.workspaceA, IDS.deviceA, secretHash, NOW + 60_000, IDS.deviceA2, NOW, NOW);
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
  };
  return { db, env };
}

test("pairing key requirements are available only to the authenticated sponsor", async () => {
  const { env } = await pairingEnvironment();
  const sponsor = await pairingView(env, PAIRING_ID, IDS.deviceA, null, NOW) as Record<string, unknown>;
  const requirements = sponsor.keyRequirements as Record<string, unknown>;
  assert.deepEqual(requirements.requiredKeyEpochs, [1, 3]);
  assert.equal(requirements.activeKeyEpoch, 3);
  assert.equal(requirements.headSeq, 17);
  assert.equal(sponsor.sponsorSigningPublicKey, "sign-a");
  assert.equal(sponsor.sponsorWrappingPublicKey, "wrap-a");
  const transcript = canonicalJson({
    pairingId: PAIRING_ID,
    workspaceId: IDS.workspaceA,
    sponsorDeviceId: IDS.deviceA,
    sponsorSigningPublicKey: "sign-a",
    sponsorWrappingPublicKey: "wrap-a",
    transcriptNonce: "bm9uY2UtbG9uZy1lbm91Z2gtMzItYnl0ZXM",
    candidateDeviceId: IDS.deviceA2,
    candidateDisplayName: "New phone",
    candidatePlatform: "android",
    candidateSigningPublicKey: "candidate-signing",
    candidateWrappingPublicKey: "candidate-wrapping",
    candidateTokenHash: "candidate-token-hash",
    expiresAt: NOW + 60_000,
  });
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(transcript));
  const expectedCode = String(new DataView(digest).getUint32(0, false) % 1_000_000).padStart(6, "0");
  assert.equal(sponsor.shortCode, expectedCode);

  const candidate = await pairingView(env, PAIRING_ID, null, PAIRING_SECRET, NOW) as Record<string, unknown>;
  assert.equal(candidate.keyRequirements, undefined);
  await assert.rejects(
    pairingView(env, PAIRING_ID, IDS.deviceB, null, NOW),
    (error: unknown) => (error as { status?: number; code?: string }).code === "pairing_forbidden",
  );
});

test("pairing approval rejects a keyring that omits a live blob envelope epoch", async () => {
  const controlNow = 1_700_000_000_000;
  const { db, env } = await pairingEnvironment();
  const keys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const signingPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", keys.publicKey)),
  );
  db.exec("DROP TRIGGER devices_identity_keys_immutable");
  db.prepare("UPDATE devices SET signing_public_key = ? WHERE device_id = ?")
    .run(signingPublicKey, IDS.deviceA);
  db.prepare("UPDATE pairing_sessions SET expires_at = ? WHERE pairing_id = ?")
    .run(controlNow + 60_000, PAIRING_ID);
  const accessToken = encodeBase64Url(new Uint8Array(32).fill(41));
  db.prepare(`INSERT INTO access_tokens(
    access_token_id, device_id, token_hash, device_auth_epoch, expires_at, created_at
  ) VALUES ('00000000-0000-4000-8000-000000000040', ?, ?, 1, ?, ?)`).run(
    IDS.deviceA,
    await hashSecret(accessToken, env.TOKEN_PEPPER, "access-token"),
    controlNow + 60_000,
    controlNow,
  );
  const path = `/v1/pairings/${PAIRING_ID}/approve`;
  const body = JSON.stringify({
    approved: true,
    keyEnvelopes: [{
      keyEpoch: 3,
      keyCommitment: "k".repeat(43),
      wrappedKey: "w".repeat(32),
      signature: "s".repeat(80),
    }],
    approvalSignature: "a".repeat(80),
  });
  const nonce = encodeBase64Url(new Uint8Array(24).fill(42));
  const bodyHash = await sha256Base64Url(new TextEncoder().encode(body));
  const controlMessage = domainSeparatedMessage(
    "control-request",
    new TextEncoder().encode(
      `POST\n${path}\n${controlNow}\n${nonce}\n${bodyHash}\n${IDS.deviceA}`,
    ),
  );
  const controlSignature = encodeBase64Url(new Uint8Array(
    await crypto.subtle.sign("Ed25519", keys.privateKey, controlMessage),
  ));
  const request = new Request(`https://sync.test${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
      "X-Shinsou-Timestamp": String(controlNow),
      "X-Shinsou-Nonce": nonce,
      "X-Shinsou-Signature": controlSignature,
    },
    body,
  });

  await assert.rejects(
    approvePairing(request, env, PAIRING_ID, controlNow),
    (error: unknown) => (error as { code?: string }).code === "pairing_incomplete_keyring",
  );
});

test("recovery challenge returns every key epoch required from retained base to head", async () => {
  const db = database();
  seedTenant(db, NOW);
  db.prepare("UPDATE workspaces SET active_key_epoch = 3 WHERE workspace_id = ?")
    .run(IDS.workspaceA);
  insertLiveBlobManifest(db, 1);
  for (const epoch of [1, 2, 3]) {
    db.prepare(`INSERT INTO workspace_recovery_keys(
      workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    ) VALUES (?, ?, ?, ?, ?, ?)`).run(
      IDS.workspaceA,
      epoch,
      `recovery-epoch-${epoch}`,
      `commitment-${epoch}`,
      `wrapped-recovery-key-${epoch}`,
      NOW + epoch,
    );
  }
  db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, previous_stable_sha256,
    r2_key, ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at, promoted_at
  ) VALUES (?, 0, ?, 3, x'a0', 1, 1, 'CHACHA20_POLY1305', 'nonce', NULL,
    'checkpoint/recovery-base', 'checkpoint-hash', 1, ?, 'signature', 'stable', ?, ?)`).run(
    IDS.workspaceA,
    IDS.checkpointA,
    IDS.deviceA,
    NOW,
    NOW,
  );
  db.prepare(`INSERT INTO events(
    workspace_id, workspace_seq, event_id, device_id, device_seq, key_epoch,
    authenticated_header_cbor, ciphertext, ciphertext_sha256, signature, byte_size, created_at
  ) VALUES (?, 1, ?, ?, 1, 2, x'a0', x'01', 'event-hash', 'signature', 1, ?)`).run(
    IDS.workspaceA,
    IDS.eventA,
    IDS.deviceA,
    NOW + 1,
  );
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
  };
  const result = await createRecoveryChallenge(new Request("https://sync.test/v1/recovery/challenge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: IDS.userA }),
  }), env, NOW) as {
    workspaces: Array<{
      workspaceId: string;
      keyEpoch: number;
      retainedKeyEnvelopes: Array<{ keyEpoch: number }>;
    }>;
  };

  assert.equal(result.workspaces.length, 1);
  assert.equal(result.workspaces[0].workspaceId, IDS.workspaceA);
  assert.equal(result.workspaces[0].keyEpoch, 3);
  assert.deepEqual(result.workspaces[0].retainedKeyEnvelopes.map((entry) => entry.keyEpoch), [1, 2]);

  db.prepare(`UPDATE encrypted_blob_manifests SET current_envelope_epoch = 3
    WHERE workspace_id = ? AND blob_id = ?`).run(IDS.workspaceA, BLOB_ID);
  const afterRewrap = await createRecoveryChallenge(new Request("https://sync.test/v1/recovery/challenge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: IDS.userA }),
  }), env, NOW + 2) as typeof result;
  assert.deepEqual(
    afterRewrap.workspaces[0].retainedKeyEnvelopes.map((entry) => entry.keyEpoch),
    [2],
  );

  db.prepare(`UPDATE encrypted_blob_manifests
    SET status = 'deleted', current_envelope_epoch = 1, deleted_at = ?
    WHERE workspace_id = ? AND blob_id = ?`).run(NOW + 3, IDS.workspaceA, BLOB_ID);
  const afterDeletion = await createRecoveryChallenge(new Request("https://sync.test/v1/recovery/challenge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: IDS.userA }),
  }), env, NOW + 4) as typeof result;
  assert.deepEqual(
    afterDeletion.workspaces[0].retainedKeyEnvelopes.map((entry) => entry.keyEpoch),
    [2],
  );
});

test("recovery claim commits exactly once and returns a successful receipt", async () => {
  const db = database();
  seedTenant(db, NOW);
  const recoveryKeys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const recoveryPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", recoveryKeys.publicKey)),
  );
  const replacementRecoveryKeys = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  db.prepare("UPDATE recovery_credentials SET signing_public_key = ? WHERE user_id = ?")
    .run(recoveryPublicKey, IDS.userA);
  const challenge = encodeBase64Url(new Uint8Array(32).fill(7));
  const challengeHash = await hashSecret(challenge, "p".repeat(32), "recovery-challenge");
  db.prepare(`INSERT INTO recovery_challenges(
    challenge_id, user_id, challenge_hash, expires_at, created_at
  ) VALUES (?, ?, ?, ?, ?)`).run(IDS.challenge, IDS.userA, challengeHash, NOW + 60_000, NOW);
  const keyCommitment = encodeBase64Url(new Uint8Array(32).fill(8));
  const retainedKeyCommitment = encodeBase64Url(new Uint8Array(32).fill(14));
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 1, 'initial-retained', ?, 'old-retained-recovery-wrapped-key-0001', ?)`).run(
    IDS.workspaceA,
    retainedKeyCommitment,
    NOW,
  );
  db.prepare(`INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) VALUES (?, 2, 'initial-active', ?, 'old-active-recovery-wrapped-key-00002', ?)`).run(
    IDS.workspaceA,
    keyCommitment,
    NOW + 1,
  );
  db.prepare("UPDATE workspaces SET active_key_epoch = 2 WHERE workspace_id = ?").run(IDS.workspaceA);
  insertLiveBlobManifest(db, 1);
  db.prepare(`INSERT INTO checkpoints(
    workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
    envelope_version, schema_version, cipher_suite, nonce, previous_stable_sha256,
    r2_key, ciphertext_sha256, byte_size, uploader_device_id, device_signature,
    status, created_at, promoted_at
  ) VALUES (?, 0, ?, 2, x'a0', 1, 1, 'CHACHA20_POLY1305', 'nonce', NULL,
    'checkpoint/claim-recovery-base', 'claim-checkpoint-hash', 1, ?, 'signature', 'stable', ?, ?)`).run(
    IDS.workspaceA,
    IDS.checkpointA,
    IDS.deviceA,
    NOW,
    NOW,
  );
  db.prepare("UPDATE workspaces SET rotation_required = 1 WHERE workspace_id = ?").run(IDS.workspaceA);
  numberedGet(
    db,
    CREATE_ROTATION_LEASE_SQL,
    IDS.rotationA,
    IDS.workspaceA,
    NOW + 10_000,
    NOW,
    IDS.deviceA,
  );

  const deviceToken = encodeBase64Url(new Uint8Array(32).fill(9));
  const signingPublicKey = encodeBase64Url(new Uint8Array(32).fill(10));
  const wrappingPublicKey = encodeBase64Url(new Uint8Array(32).fill(11));
  const newRecoverySigningPublicKey = encodeBase64Url(
    new Uint8Array(await crypto.subtle.exportKey("raw", replacementRecoveryKeys.publicKey)),
  );
  const newRecoveryWrappingPublicKey = encodeBase64Url(new Uint8Array(32).fill(13));
  const deviceWrappedKey = "device-wrapped-key-value-0000000001";
  const recoveryWrappedKey = "recovery-wrapped-key-value-0000001";
  const retainedRecoveryWrappedKey = "retained-recovery-wrapped-key-000001";
  const deviceEnvelopeSignature = "s".repeat(80);
  const recoveryLineage = canonicalJson({
    instanceId: IDS.instance,
    userId: IDS.userA,
    challengeId: IDS.challenge,
    deviceId: IDS.deviceA2,
    deviceSigningPublicKey: signingPublicKey,
    deviceWrappingPublicKey: wrappingPublicKey,
    previousRecoverySigningPublicKey: recoveryPublicKey,
    newRecoverySigningPublicKey,
    newRecoveryWrappingPublicKey,
  });
  const replacementRecoveryTrustSignature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    replacementRecoveryKeys.privateKey,
    domainSeparatedMessage("recovery-lineage", new TextEncoder().encode(recoveryLineage)),
  )));
  const envelope = {
    workspaceId: IDS.workspaceA,
    keyEpoch: 2,
    keyCommitment,
    deviceWrappedKey,
    deviceEnvelopeSignature,
    recoveryKeyEnvelopes: [
      { keyEpoch: 1, keyCommitment: retainedKeyCommitment, recoveryWrappedKey: retainedRecoveryWrappedKey },
      { keyEpoch: 2, keyCommitment, recoveryWrappedKey },
    ],
  };
  const encoder = new TextEncoder();
  const attestation = canonicalJson({
    instanceId: IDS.instance,
    userId: IDS.userA,
    challengeId: IDS.challenge,
    challengeCommitment: await publicTokenCommitment(challenge, "recovery_challenge"),
    device: {
      deviceId: IDS.deviceA2,
      displayName: "Recovered phone",
      platform: "ios",
      signingPublicKey,
      wrappingPublicKey,
      deviceTokenHash: await publicTokenCommitment(deviceToken, "device_token"),
    },
    previousRecoverySigningPublicKey: recoveryPublicKey,
    newRecoverySigningPublicKey,
    newRecoveryWrappingPublicKey,
    replacementRecoveryTrustSignature,
    workspaceEnvelopes: [{
      workspaceId: IDS.workspaceA,
      keyEpoch: 2,
      keyCommitment,
      deviceWrappedKeyHash: await sha256Base64Url(encoder.encode(deviceWrappedKey)),
      deviceEnvelopeSignature,
      recoveryKeyEnvelopes: [
        {
          keyEpoch: 1,
          keyCommitment: retainedKeyCommitment,
          recoveryWrappedKeyHash: await sha256Base64Url(encoder.encode(retainedRecoveryWrappedKey)),
        },
        {
          keyEpoch: 2,
          keyCommitment,
          recoveryWrappedKeyHash: await sha256Base64Url(encoder.encode(recoveryWrappedKey)),
        },
      ],
    }],
  });
  const signature = encodeBase64Url(new Uint8Array(await crypto.subtle.sign(
    "Ed25519",
    recoveryKeys.privateKey,
    domainSeparatedMessage("recovery-claim", encoder.encode(attestation)),
  )));
  const env: Env = {
    DB: sqliteD1(db),
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
  };
  const receipt = await claimRecovery(new Request("https://sync.test/v1/recovery/claim", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      manifest: {
        instanceId: IDS.instance,
        userId: IDS.userA,
        challengeId: IDS.challenge,
        challenge,
        device: {
          deviceId: IDS.deviceA2,
          displayName: "Recovered phone",
          platform: "ios",
          signingPublicKey,
          wrappingPublicKey,
          deviceToken,
        },
        previousRecoverySigningPublicKey: recoveryPublicKey,
        newRecoverySigningPublicKey,
        newRecoveryWrappingPublicKey,
        replacementRecoveryTrustSignature,
        workspaceEnvelopes: [envelope],
      },
      signature,
    }),
  }), env, NOW + 1) as {
    deviceId: string;
    rotationRequiredWorkspaces: string[];
    workspaceBindings: Array<{
      workspaceId: string;
      deviceAuthEpoch: number;
      membershipAuthEpoch: number;
      activeKeyEpoch: number;
    }>;
  };

  assert.equal(receipt.deviceId, IDS.deviceA2);
  assert.deepEqual(receipt.rotationRequiredWorkspaces, [IDS.workspaceA]);
  assert.deepEqual(receipt.workspaceBindings, [{
    workspaceId: IDS.workspaceA,
    deviceAuthEpoch: 1,
    membershipAuthEpoch: 1,
    activeKeyEpoch: 2,
  }]);
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM recovery_claims").get()?.count, 1);
  assert.equal(db.prepare("SELECT status FROM devices WHERE device_id = ?").get(IDS.deviceA)?.status, "revoked");
  assert.equal(db.prepare("SELECT status FROM devices WHERE device_id = ?").get(IDS.deviceA2)?.status, "active");
  assert.equal(db.prepare("SELECT status FROM key_rotations WHERE rotation_id = ?").get(IDS.rotationA)?.status, "aborted");
  assert.equal(
    db.prepare("SELECT pending_rotation_id FROM workspaces WHERE workspace_id = ?").get(IDS.workspaceA)?.pending_rotation_id,
    null,
  );
  assert.equal(
    db.prepare("SELECT auth_epoch FROM recovery_credentials WHERE user_id = ?").get(IDS.userA)?.auth_epoch,
    2,
  );
  assert.equal(
    db.prepare(`SELECT GROUP_CONCAT(key_epoch || ':' || wrapped_key, '|') AS keyring
      FROM (SELECT key_epoch, wrapped_key FROM workspace_recovery_keys
        WHERE workspace_id = ? AND rotation_id LIKE 'recovery:%'
        ORDER BY key_epoch)`)
      .get(IDS.workspaceA)?.keyring,
    `1:${retainedRecoveryWrappedKey}|2:${recoveryWrappedKey}`,
  );

  const secondLoss = await createRecoveryChallenge(new Request("https://sync.test/v1/recovery/challenge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: IDS.userA }),
  }), env, NOW + 2) as { workspaces: Array<{
    keyEpoch: number;
    recoveryWrappedKey: string;
    retainedKeyEnvelopes: Array<{ keyEpoch: number; recoveryWrappedKey: string }>;
  }> };
  assert.equal(secondLoss.workspaces[0].keyEpoch, 2);
  assert.equal(secondLoss.workspaces[0].recoveryWrappedKey, recoveryWrappedKey);
  assert.deepEqual(secondLoss.workspaces[0].retainedKeyEnvelopes, [{
    keyEpoch: 1,
    keyCommitment: retainedKeyCommitment,
    recoveryWrappedKey: retainedRecoveryWrappedKey,
  }]);

  // Simulate a committed POST whose response was lost: the replacement device authenticates and
  // receives the exact durable receipt, including the replacement root it must activate locally.
  const access = await issueAccessToken(env, { deviceId: IDS.deviceA2, authEpoch: 1 }, NOW + 2);
  const reconciled = await reconcileRecoveryClaim(new Request("https://sync.test/v1/recovery/claim", {
    headers: { Authorization: `Bearer ${access.accessToken}` },
  }), env, NOW + 4) as Record<string, unknown>;
  assert.equal(reconciled.deviceId, IDS.deviceA2);
  assert.equal(reconciled.newRecoverySigningPublicKey, newRecoverySigningPublicKey);
  assert.equal(reconciled.newRecoveryWrappingPublicKey, newRecoveryWrappingPublicKey);
  assert.deepEqual(reconciled.rotationRequiredWorkspaces, [IDS.workspaceA]);
  assert.deepEqual(
    (reconciled.workspaceBindings as Array<Record<string, unknown>>).map((entry) => ({ ...entry })),
    receipt.workspaceBindings,
  );
});
