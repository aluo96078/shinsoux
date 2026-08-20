PRAGMA foreign_keys = ON;

CREATE TABLE schema_migrations (
  version INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  applied_at INTEGER NOT NULL
);

CREATE TABLE installation (
  instance_id TEXT PRIMARY KEY,
  schema_version INTEGER NOT NULL,
  initialized_at INTEGER NOT NULL,
  signup_mode TEXT NOT NULL CHECK (signup_mode IN ('invite_only', 'disabled'))
);

CREATE TRIGGER installation_singleton_guard
BEFORE INSERT ON installation
WHEN EXISTS (SELECT 1 FROM installation)
BEGIN
  SELECT RAISE(ABORT, 'instance_already_initialized');
END;

CREATE TABLE quota_profiles (
  quota_profile_id TEXT PRIMARY KEY,
  max_users INTEGER NOT NULL CHECK (max_users > 0),
  max_workspaces_per_user INTEGER NOT NULL CHECK (max_workspaces_per_user > 0),
  max_devices_per_user INTEGER NOT NULL CHECK (max_devices_per_user > 0),
  max_workspace_bytes INTEGER NOT NULL CHECK (max_workspace_bytes > 0),
  max_event_bytes INTEGER NOT NULL CHECK (max_event_bytes > 0),
  max_checkpoint_bytes INTEGER NOT NULL CHECK (max_checkpoint_bytes > 0),
  created_at INTEGER NOT NULL
);

INSERT INTO quota_profiles(
  quota_profile_id, max_users, max_workspaces_per_user, max_devices_per_user,
  max_workspace_bytes, max_event_bytes, max_checkpoint_bytes, created_at
) VALUES ('private-default', 25, 1, 10, 262144000, 32768, 33554432, 0);

CREATE TABLE users (
  user_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  instance_role TEXT NOT NULL CHECK (instance_role IN ('admin', 'user')),
  status TEXT NOT NULL CHECK (status IN ('active', 'suspended', 'deleted')),
  quota_profile_id TEXT NOT NULL REFERENCES quota_profiles(quota_profile_id),
  created_at INTEGER NOT NULL
);

CREATE TABLE devices (
  device_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(user_id),
  display_name TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android', 'ios', 'macos', 'windows', 'other')),
  signing_public_key TEXT NOT NULL,
  wrapping_public_key TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('pending', 'active', 'revoked')),
  auth_epoch INTEGER NOT NULL DEFAULT 1 CHECK (auth_epoch > 0),
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER
);

CREATE TRIGGER devices_identity_keys_immutable
BEFORE UPDATE OF signing_public_key, wrapping_public_key, token_hash ON devices
WHEN NEW.signing_public_key <> OLD.signing_public_key
  OR NEW.wrapping_public_key <> OLD.wrapping_public_key
  OR NEW.token_hash <> OLD.token_hash
BEGIN
  SELECT RAISE(ABORT, 'device_identity_immutable');
END;

CREATE INDEX devices_user_status_idx ON devices(user_id, status);

CREATE TABLE workspaces (
  workspace_id TEXT PRIMARY KEY,
  owner_user_id TEXT NOT NULL REFERENCES users(user_id),
  status TEXT NOT NULL CHECK (status IN ('active', 'suspended', 'reset')),
  active_key_epoch INTEGER NOT NULL CHECK (active_key_epoch > 0),
  device_directory_epoch INTEGER NOT NULL DEFAULT 1 CHECK (device_directory_epoch > 0),
  pending_rotation_id TEXT,
  rotation_required INTEGER NOT NULL DEFAULT 0 CHECK (rotation_required IN (0, 1)),
  head_seq INTEGER NOT NULL DEFAULT 0 CHECK (head_seq >= 0),
  gc_before_seq INTEGER NOT NULL DEFAULT 0 CHECK (gc_before_seq >= 0),
  stable_checkpoint_id TEXT,
  candidate_checkpoint_id TEXT,
  committed_bytes INTEGER NOT NULL DEFAULT 0 CHECK (committed_bytes >= 0),
  reserved_bytes INTEGER NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
  created_at INTEGER NOT NULL
);

CREATE INDEX workspaces_owner_idx ON workspaces(owner_user_id, status);

CREATE TABLE memberships (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  user_id TEXT NOT NULL REFERENCES users(user_id),
  role TEXT NOT NULL CHECK (role IN ('owner', 'editor', 'reader')),
  status TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
  auth_epoch INTEGER NOT NULL DEFAULT 1 CHECK (auth_epoch > 0),
  PRIMARY KEY(workspace_id, user_id)
);

CREATE INDEX memberships_user_status_idx ON memberships(user_id, status);

CREATE TABLE device_key_attestations (
  device_id TEXT PRIMARY KEY REFERENCES devices(device_id),
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  attestation_type TEXT NOT NULL CHECK (attestation_type IN ('initial', 'pairing', 'recovery')),
  attestor_device_id TEXT REFERENCES devices(device_id),
  attestor_public_key TEXT NOT NULL,
  signature_domain TEXT NOT NULL,
  manifest_json TEXT NOT NULL,
  signature TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX device_key_attestations_workspace_idx
  ON device_key_attestations(workspace_id, device_id);

CREATE TRIGGER device_key_attestations_no_update
BEFORE UPDATE ON device_key_attestations
BEGIN
  SELECT RAISE(ABORT, 'device_attestation_immutable');
END;

CREATE TRIGGER device_key_attestations_no_delete
BEFORE DELETE ON device_key_attestations
BEGIN
  SELECT RAISE(ABORT, 'device_attestation_immutable');
END;

CREATE TABLE recovery_credentials (
  user_id TEXT PRIMARY KEY REFERENCES users(user_id),
  signing_public_key TEXT NOT NULL,
  wrapping_public_key TEXT NOT NULL,
  auth_epoch INTEGER NOT NULL DEFAULT 1 CHECK (auth_epoch > 0),
  rotated_at INTEGER NOT NULL
);

CREATE TABLE workspace_device_keys (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  key_epoch INTEGER NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  rotation_id TEXT NOT NULL,
  key_commitment TEXT NOT NULL,
  wrapped_key TEXT NOT NULL,
  wrapped_by_device_id TEXT NOT NULL,
  signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, rotation_id, device_id)
);

CREATE INDEX workspace_device_keys_device_idx
  ON workspace_device_keys(device_id, workspace_id, key_epoch, rotation_id);

CREATE TABLE workspace_recovery_keys (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  key_epoch INTEGER NOT NULL,
  rotation_id TEXT NOT NULL,
  key_commitment TEXT NOT NULL,
  wrapped_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, rotation_id)
);

CREATE TABLE invites (
  invite_id TEXT PRIMARY KEY,
  created_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
  secret_hash TEXT NOT NULL UNIQUE,
  display_name_hint TEXT,
  expires_at INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active', 'redeemed', 'revoked', 'expired')),
  redeemed_by_user_id TEXT,
  created_at INTEGER NOT NULL,
  redeemed_at INTEGER
);

CREATE TABLE invite_redemptions (
  invite_id TEXT PRIMARY KEY REFERENCES invites(invite_id),
  user_id TEXT NOT NULL UNIQUE,
  redeemed_at INTEGER NOT NULL
);

CREATE TRIGGER invite_redemptions_guard
BEFORE INSERT ON invite_redemptions
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM invites i
    WHERE i.invite_id = NEW.invite_id
      AND i.status = 'active'
      AND i.expires_at > NEW.redeemed_at
  ) THEN RAISE(ABORT, 'invite_invalid') END;

  SELECT CASE WHEN (
    SELECT COUNT(*) FROM users WHERE status <> 'deleted'
  ) > (
    SELECT max_users FROM quota_profiles WHERE quota_profile_id = 'private-default'
  ) THEN RAISE(ABORT, 'user_quota_exceeded') END;
END;

CREATE TRIGGER invite_redemptions_consume
AFTER INSERT ON invite_redemptions
BEGIN
  UPDATE invites
  SET status = 'redeemed', redeemed_by_user_id = NEW.user_id, redeemed_at = NEW.redeemed_at
  WHERE invite_id = NEW.invite_id;
END;

CREATE TABLE pairing_sessions (
  pairing_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  sponsor_device_id TEXT NOT NULL REFERENCES devices(device_id),
  secret_hash TEXT NOT NULL UNIQUE,
  transcript_nonce TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('open', 'candidate', 'approved', 'expired', 'cancelled')),
  expires_at INTEGER NOT NULL,
  candidate_device_id TEXT,
  candidate_display_name TEXT,
  candidate_platform TEXT,
  candidate_signing_public_key TEXT,
  candidate_wrapping_public_key TEXT,
  candidate_token_hash TEXT,
  candidate_submitted_at INTEGER,
  approval_signature TEXT,
  approved_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE INDEX pairing_workspace_status_idx ON pairing_sessions(workspace_id, status);

CREATE TABLE pairing_activations (
  pairing_id TEXT PRIMARY KEY REFERENCES pairing_sessions(pairing_id),
  candidate_device_id TEXT NOT NULL UNIQUE,
  envelope_count INTEGER NOT NULL CHECK (envelope_count > 0),
  activated_at INTEGER NOT NULL
);

CREATE TRIGGER pairing_activations_guard
BEFORE INSERT ON pairing_activations
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM pairing_sessions p
    JOIN devices sponsor ON sponsor.device_id = p.sponsor_device_id
    WHERE p.pairing_id = NEW.pairing_id
      AND p.status = 'candidate'
      AND p.expires_at > NEW.activated_at
      AND p.candidate_device_id = NEW.candidate_device_id
      AND sponsor.status = 'active'
  ) THEN RAISE(ABORT, 'pairing_invalid') END;

  SELECT CASE WHEN (
    SELECT COUNT(DISTINCT required_epoch) FROM (
      SELECT active_key_epoch AS required_epoch
      FROM workspaces w JOIN pairing_sessions p ON p.workspace_id = w.workspace_id
      WHERE p.pairing_id = NEW.pairing_id
      UNION
      SELECT c.key_epoch AS required_epoch
      FROM checkpoints c JOIN pairing_sessions p ON p.workspace_id = c.workspace_id
      WHERE p.pairing_id = NEW.pairing_id AND c.status = 'stable'
      UNION
      SELECT e.key_epoch AS required_epoch
      FROM events e JOIN pairing_sessions p ON p.workspace_id = e.workspace_id
      WHERE p.pairing_id = NEW.pairing_id
    )
  ) <> NEW.envelope_count THEN RAISE(ABORT, 'pairing_incomplete_keyring') END;

  SELECT CASE WHEN (
    SELECT COUNT(*) FROM devices d
    JOIN pairing_sessions p ON p.candidate_device_id = NEW.candidate_device_id
    JOIN devices sponsor ON sponsor.device_id = p.sponsor_device_id
    WHERE d.user_id = sponsor.user_id AND d.status = 'active'
  ) > (
    SELECT q.max_devices_per_user FROM pairing_sessions p
    JOIN devices sponsor ON sponsor.device_id = p.sponsor_device_id
    JOIN users u ON u.user_id = sponsor.user_id
    JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
    WHERE p.pairing_id = NEW.pairing_id
  ) THEN RAISE(ABORT, 'device_quota_exceeded') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM device_key_attestations a
    WHERE a.device_id = NEW.candidate_device_id AND a.attestation_type = 'pairing'
  ) THEN RAISE(ABORT, 'device_attestation_missing') END;
END;

CREATE TRIGGER pairing_activations_finish
AFTER INSERT ON pairing_activations
BEGIN
  UPDATE pairing_sessions
  SET status = 'approved', approved_at = NEW.activated_at
  WHERE pairing_id = NEW.pairing_id;

  UPDATE workspaces SET device_directory_epoch = device_directory_epoch + 1
  WHERE workspace_id = (
    SELECT workspace_id FROM pairing_sessions WHERE pairing_id = NEW.pairing_id
  );
END;

CREATE TABLE auth_challenges (
  challenge_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  challenge_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE INDEX auth_challenge_device_idx ON auth_challenges(device_id, expires_at);

CREATE TABLE access_tokens (
  access_token_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  token_hash TEXT NOT NULL UNIQUE,
  device_auth_epoch INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE INDEX access_tokens_device_idx ON access_tokens(device_id, expires_at);

CREATE TABLE workspace_capabilities (
  capability_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  token_hash TEXT NOT NULL UNIQUE,
  device_auth_epoch INTEGER NOT NULL,
  membership_auth_epoch INTEGER NOT NULL,
  key_epoch INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE INDEX workspace_capabilities_lookup_idx
  ON workspace_capabilities(token_hash, expires_at);

CREATE TABLE control_request_nonces (
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  nonce_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(device_id, nonce_hash)
);

CREATE TABLE device_revocations (
  revocation_id TEXT PRIMARY KEY,
  actor_device_id TEXT NOT NULL REFERENCES devices(device_id),
  target_device_id TEXT NOT NULL REFERENCES devices(device_id),
  created_at INTEGER NOT NULL
);

CREATE TRIGGER device_revocations_guard
BEFORE INSERT ON device_revocations
BEGIN
  SELECT CASE WHEN NEW.actor_device_id = NEW.target_device_id
    THEN RAISE(ABORT, 'cannot_revoke_current_device') END;
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM devices actor JOIN devices target ON actor.user_id = target.user_id
    WHERE actor.device_id = NEW.actor_device_id AND actor.status = 'active'
      AND target.device_id = NEW.target_device_id AND target.status = 'active'
  ) THEN RAISE(ABORT, 'device_revoke_forbidden') END;
END;

CREATE TRIGGER device_revocations_apply
AFTER INSERT ON device_revocations
BEGIN
  UPDATE devices
  SET status = 'revoked', auth_epoch = auth_epoch + 1, revoked_at = NEW.created_at
  WHERE device_id = NEW.target_device_id;

  UPDATE access_tokens SET revoked_at = NEW.created_at
  WHERE device_id = NEW.target_device_id AND revoked_at IS NULL;

  UPDATE workspace_capabilities SET revoked_at = NEW.created_at
  WHERE device_id = NEW.target_device_id AND revoked_at IS NULL;

  -- Any leased manifest captured the now-stale recipient set. Abort it in the same
  -- transaction so the surviving device can immediately acquire a fresh lease.
  UPDATE key_rotations SET status = 'aborted'
  WHERE status = 'leased' AND workspace_id IN (
    SELECT m.workspace_id FROM memberships m
    JOIN devices d ON d.user_id = m.user_id
    WHERE d.device_id = NEW.target_device_id AND m.status = 'active'
  );

  UPDATE workspaces SET pending_rotation_id = NULL
  WHERE pending_rotation_id IN (
    SELECT rotation_id FROM key_rotations WHERE status = 'aborted'
  );

  UPDATE workspaces
  SET rotation_required = 1,
      device_directory_epoch = device_directory_epoch + 1
  WHERE workspace_id IN (
    SELECT m.workspace_id FROM memberships m
    JOIN devices d ON d.user_id = m.user_id
    WHERE d.device_id = NEW.target_device_id AND m.status = 'active'
  );
END;

CREATE TABLE key_rotations (
  rotation_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  from_epoch INTEGER NOT NULL,
  to_epoch INTEGER NOT NULL,
  proposer_device_id TEXT NOT NULL REFERENCES devices(device_id),
  proposer_device_auth_epoch INTEGER NOT NULL,
  membership_auth_epoch INTEGER NOT NULL,
  key_commitment TEXT,
  manifest_cbor TEXT,
  manifest_signature TEXT,
  status TEXT NOT NULL CHECK (status IN ('leased', 'committed', 'aborted', 'expired')),
  lease_expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  committed_at INTEGER
);

CREATE UNIQUE INDEX key_rotations_one_active_lease
  ON key_rotations(workspace_id) WHERE status = 'leased';

CREATE TABLE key_rotation_recipients (
  rotation_id TEXT NOT NULL REFERENCES key_rotations(rotation_id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  device_auth_epoch INTEGER NOT NULL,
  wrapping_public_key TEXT NOT NULL,
  PRIMARY KEY(rotation_id, device_id)
);

CREATE TRIGGER key_rotations_snapshot_recipients
AFTER INSERT ON key_rotations
WHEN NEW.status = 'leased'
BEGIN
  INSERT INTO key_rotation_recipients(
    rotation_id, device_id, device_auth_epoch, wrapping_public_key
  )
  SELECT NEW.rotation_id, d.device_id, d.auth_epoch, d.wrapping_public_key
  FROM memberships m JOIN devices d ON d.user_id = m.user_id
  WHERE m.workspace_id = NEW.workspace_id AND m.status = 'active' AND d.status = 'active';

  UPDATE workspaces SET pending_rotation_id = NEW.rotation_id
  WHERE workspace_id = NEW.workspace_id;
END;

CREATE TABLE key_rotation_acks (
  rotation_id TEXT NOT NULL REFERENCES key_rotations(rotation_id),
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  key_commitment TEXT NOT NULL,
  signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(rotation_id, device_id)
);

CREATE TABLE rotation_commits (
  rotation_id TEXT PRIMARY KEY REFERENCES key_rotations(rotation_id),
  committed_at INTEGER NOT NULL
);

CREATE TRIGGER rotation_commits_guard
BEFORE INSERT ON rotation_commits
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM key_rotations r
    JOIN workspaces w ON w.workspace_id = r.workspace_id
    JOIN memberships m ON m.workspace_id = r.workspace_id
    JOIN devices d ON d.device_id = r.proposer_device_id AND d.user_id = m.user_id
    WHERE r.rotation_id = NEW.rotation_id AND r.status = 'leased'
      AND r.lease_expires_at > NEW.committed_at
      AND w.pending_rotation_id = r.rotation_id
      AND w.active_key_epoch = r.from_epoch
      AND d.status = 'active' AND d.auth_epoch = r.proposer_device_auth_epoch
      AND m.status = 'active' AND m.auth_epoch = r.membership_auth_epoch
      AND r.to_epoch = r.from_epoch + 1
      AND r.key_commitment IS NOT NULL
      AND r.manifest_cbor IS NOT NULL AND r.manifest_signature IS NOT NULL
  ) THEN RAISE(ABORT, 'rotation_lease_invalid') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM key_rotation_recipients rr
    JOIN devices d ON d.device_id = rr.device_id
    WHERE rr.rotation_id = NEW.rotation_id
      AND (d.status <> 'active' OR d.auth_epoch <> rr.device_auth_epoch)
  ) THEN RAISE(ABORT, 'rotation_recipient_changed') END;

  SELECT CASE WHEN (
    SELECT COUNT(*) FROM key_rotation_recipients WHERE rotation_id = NEW.rotation_id
  ) <> (
    SELECT COUNT(*) FROM key_rotations r
    JOIN memberships m ON m.workspace_id = r.workspace_id AND m.status = 'active'
    JOIN devices d ON d.user_id = m.user_id AND d.status = 'active'
    WHERE r.rotation_id = NEW.rotation_id
  ) THEN RAISE(ABORT, 'rotation_recipient_changed') END;

  SELECT CASE WHEN (
    SELECT COUNT(*) FROM key_rotation_recipients WHERE rotation_id = NEW.rotation_id
  ) <> (
    SELECT COUNT(*) FROM workspace_device_keys WHERE rotation_id = NEW.rotation_id
  ) THEN RAISE(ABORT, 'rotation_envelopes_incomplete') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM key_rotation_recipients rr
    LEFT JOIN workspace_device_keys k
      ON k.rotation_id = rr.rotation_id AND k.device_id = rr.device_id
    JOIN key_rotations r ON r.rotation_id = rr.rotation_id
    WHERE rr.rotation_id = NEW.rotation_id
      AND (k.device_id IS NULL OR k.key_commitment <> r.key_commitment OR k.key_epoch <> r.to_epoch)
  ) THEN RAISE(ABORT, 'rotation_envelope_mismatch') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM workspace_recovery_keys rk JOIN key_rotations r ON r.rotation_id = rk.rotation_id
    WHERE rk.rotation_id = NEW.rotation_id
      AND rk.key_commitment = r.key_commitment AND rk.key_epoch = r.to_epoch
  ) THEN RAISE(ABORT, 'rotation_recovery_envelope_missing') END;
END;

CREATE TRIGGER rotation_commits_apply
AFTER INSERT ON rotation_commits
BEGIN
  UPDATE key_rotations SET status = 'committed', committed_at = NEW.committed_at
  WHERE rotation_id = NEW.rotation_id;

  UPDATE workspaces
  SET active_key_epoch = (
        SELECT to_epoch FROM key_rotations WHERE rotation_id = NEW.rotation_id
      ),
      pending_rotation_id = NULL,
      rotation_required = 0
  WHERE workspace_id = (
    SELECT workspace_id FROM key_rotations WHERE rotation_id = NEW.rotation_id
  );
END;

CREATE TABLE events (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  workspace_seq INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  device_seq INTEGER NOT NULL CHECK (device_seq > 0),
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  authenticated_header_cbor BLOB NOT NULL,
  ciphertext BLOB NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  signature TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0),
  created_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, workspace_seq),
  UNIQUE(workspace_id, event_id),
  UNIQUE(workspace_id, device_id, device_seq)
);

CREATE INDEX events_workspace_seq_idx ON events(workspace_id, workspace_seq);
CREATE INDEX events_workspace_created_idx ON events(workspace_id, created_at);

CREATE TABLE device_stream_state (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  committed_device_seq INTEGER NOT NULL CHECK (committed_device_seq >= 0),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, device_id)
);

CREATE TABLE event_receipts (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  device_seq INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  workspace_seq INTEGER NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, device_id, device_seq)
);

CREATE TABLE event_rate_windows (
  scope_key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  event_count INTEGER NOT NULL,
  PRIMARY KEY(scope_key, window_start)
);

CREATE TRIGGER events_commit_side_effects
AFTER INSERT ON events
BEGIN
  UPDATE workspaces
  SET head_seq = NEW.workspace_seq, committed_bytes = committed_bytes + NEW.byte_size
  WHERE workspace_id = NEW.workspace_id AND head_seq = NEW.workspace_seq - 1;

  SELECT CASE WHEN changes() <> 1 THEN RAISE(ABORT, 'workspace_head_race') END;

  INSERT INTO device_stream_state(workspace_id, device_id, committed_device_seq, updated_at)
  VALUES (NEW.workspace_id, NEW.device_id, NEW.device_seq, NEW.created_at)
  ON CONFLICT(workspace_id, device_id) DO UPDATE SET
    committed_device_seq = excluded.committed_device_seq,
    updated_at = excluded.updated_at;

  INSERT INTO event_receipts(
    workspace_id, device_id, device_seq, event_id, workspace_seq, ciphertext_sha256, created_at
  ) VALUES (
    NEW.workspace_id, NEW.device_id, NEW.device_seq, NEW.event_id,
    NEW.workspace_seq, NEW.ciphertext_sha256, NEW.created_at
  );
END;

CREATE TABLE checkpoint_leases (
  lease_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  checkpoint_id TEXT NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  through_seq INTEGER NOT NULL,
  key_epoch INTEGER NOT NULL,
  uploader_device_id TEXT NOT NULL REFERENCES devices(device_id),
  capability_id TEXT NOT NULL REFERENCES workspace_capabilities(capability_id),
  reserved_bytes INTEGER NOT NULL CHECK (reserved_bytes > 0),
  actual_byte_size INTEGER,
  r2_key TEXT,
  status TEXT NOT NULL CHECK (status IN ('active', 'uploaded', 'committed', 'expired', 'cancelled')),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(workspace_id, checkpoint_id, ciphertext_sha256)
);

CREATE UNIQUE INDEX checkpoint_one_active_lease
  ON checkpoint_leases(workspace_id) WHERE status IN ('active', 'uploaded');

CREATE TABLE workspace_usage_reservations (
  reservation_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  byte_size INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active', 'settled', 'released')),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TRIGGER checkpoint_leases_reserve
AFTER INSERT ON checkpoint_leases
BEGIN
  UPDATE workspaces SET reserved_bytes = reserved_bytes + NEW.reserved_bytes
  WHERE workspace_id = NEW.workspace_id;
  INSERT INTO workspace_usage_reservations(
    reservation_id, workspace_id, byte_size, status, expires_at, created_at
  ) VALUES (NEW.lease_id, NEW.workspace_id, NEW.reserved_bytes, 'active', NEW.expires_at, NEW.created_at);
END;

CREATE TRIGGER checkpoint_leases_release
AFTER UPDATE OF status ON checkpoint_leases
WHEN OLD.status IN ('active', 'uploaded') AND NEW.status IN ('expired', 'cancelled')
BEGIN
  UPDATE workspaces SET reserved_bytes = MAX(0, reserved_bytes - OLD.reserved_bytes)
  WHERE workspace_id = OLD.workspace_id;
  UPDATE workspace_usage_reservations SET status = 'released'
  WHERE reservation_id = OLD.lease_id AND status = 'active';
END;

CREATE TABLE checkpoints (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  through_seq INTEGER NOT NULL,
  checkpoint_id TEXT NOT NULL,
  key_epoch INTEGER NOT NULL,
  authenticated_header_cbor BLOB NOT NULL,
  envelope_version INTEGER NOT NULL,
  schema_version INTEGER NOT NULL,
  cipher_suite TEXT NOT NULL,
  nonce TEXT NOT NULL,
  previous_stable_sha256 TEXT,
  r2_key TEXT NOT NULL UNIQUE,
  ciphertext_sha256 TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0),
  uploader_device_id TEXT NOT NULL REFERENCES devices(device_id),
  device_signature TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('candidate', 'stable', 'rejected', 'gc_pending', 'deleted')),
  created_at INTEGER NOT NULL,
  promoted_at INTEGER,
  PRIMARY KEY(workspace_id, checkpoint_id),
  UNIQUE(workspace_id, checkpoint_id, ciphertext_sha256)
);

CREATE INDEX checkpoints_workspace_status_seq_idx
  ON checkpoints(workspace_id, status, through_seq DESC);

CREATE TABLE checkpoint_acks (
  workspace_id TEXT NOT NULL,
  checkpoint_id TEXT NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  validator_device_id TEXT NOT NULL REFERENCES devices(device_id),
  validation_version INTEGER NOT NULL,
  replay_from_seq INTEGER NOT NULL,
  replayed_through_seq INTEGER NOT NULL,
  replayed_event_count INTEGER NOT NULL,
  previous_stable_checkpoint_id TEXT,
  previous_stable_sha256 TEXT,
  signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, checkpoint_id, ciphertext_sha256, validator_device_id),
  FOREIGN KEY(workspace_id, checkpoint_id)
    REFERENCES checkpoints(workspace_id, checkpoint_id)
);

CREATE TABLE checkpoint_promotions (
  workspace_id TEXT NOT NULL,
  checkpoint_id TEXT NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  promoted_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, checkpoint_id, ciphertext_sha256),
  FOREIGN KEY(workspace_id, checkpoint_id)
    REFERENCES checkpoints(workspace_id, checkpoint_id)
);

CREATE TABLE workspace_gc_state (
  workspace_id TEXT PRIMARY KEY REFERENCES workspaces(workspace_id),
  target_seq INTEGER NOT NULL,
  execute_after INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('waiting', 'running', 'complete')),
  updated_at INTEGER NOT NULL
);

CREATE TABLE event_gc_runs (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  target_seq INTEGER NOT NULL,
  executed_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, target_seq)
);

CREATE TRIGGER event_gc_runs_guard
BEFORE INSERT ON event_gc_runs
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM workspace_gc_state g
    WHERE g.workspace_id = NEW.workspace_id AND g.status = 'waiting'
      AND g.target_seq = NEW.target_seq AND g.execute_after <= NEW.executed_at
      AND (
        SELECT COUNT(*) FROM checkpoints c
        WHERE c.workspace_id = NEW.workspace_id AND c.status = 'stable'
      ) >= 2
      AND NEW.target_seq <= (
        SELECT recovery.through_seq FROM checkpoints recovery
        WHERE recovery.workspace_id = NEW.workspace_id AND recovery.status = 'stable'
        ORDER BY recovery.through_seq DESC, recovery.promoted_at DESC
        LIMIT 1 OFFSET 1
      )
  ) THEN RAISE(ABORT, 'event_gc_not_safe') END;
END;

CREATE TRIGGER event_gc_runs_apply
AFTER INSERT ON event_gc_runs
BEGIN
  UPDATE workspaces
  SET committed_bytes = MAX(0, committed_bytes - COALESCE((
        SELECT SUM(byte_size) FROM events
        WHERE workspace_id = NEW.workspace_id AND workspace_seq <= NEW.target_seq
      ), 0)),
      gc_before_seq = MAX(gc_before_seq, NEW.target_seq)
  WHERE workspace_id = NEW.workspace_id;

  DELETE FROM events
  WHERE workspace_id = NEW.workspace_id AND workspace_seq <= NEW.target_seq;

  UPDATE workspace_gc_state SET status = 'complete', updated_at = NEW.executed_at
  WHERE workspace_id = NEW.workspace_id AND target_seq = NEW.target_seq;
END;

CREATE TRIGGER checkpoint_promotions_guard
BEFORE INSERT ON checkpoint_promotions
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM checkpoints c JOIN workspaces w ON w.workspace_id = c.workspace_id
    WHERE c.workspace_id = NEW.workspace_id AND c.checkpoint_id = NEW.checkpoint_id
      AND c.ciphertext_sha256 = NEW.ciphertext_sha256 AND c.status = 'candidate'
      AND w.candidate_checkpoint_id = c.checkpoint_id
      AND EXISTS (
        SELECT 1 FROM checkpoint_acks a
        WHERE a.workspace_id = c.workspace_id AND a.checkpoint_id = c.checkpoint_id
          AND a.ciphertext_sha256 = c.ciphertext_sha256
      )
  ) THEN RAISE(ABORT, 'checkpoint_not_promotable') END;
END;

CREATE TRIGGER checkpoint_promotions_apply
AFTER INSERT ON checkpoint_promotions
BEGIN
  UPDATE checkpoints SET status = 'stable', promoted_at = NEW.promoted_at
  WHERE workspace_id = NEW.workspace_id AND checkpoint_id = NEW.checkpoint_id
    AND ciphertext_sha256 = NEW.ciphertext_sha256;

  UPDATE workspaces
  SET stable_checkpoint_id = NEW.checkpoint_id, candidate_checkpoint_id = NULL
  WHERE workspace_id = NEW.workspace_id;
END;

CREATE TRIGGER checkpoints_commit_candidate
AFTER INSERT ON checkpoints
WHEN NEW.status = 'candidate'
BEGIN
  UPDATE checkpoint_leases SET status = 'committed'
  WHERE workspace_id = NEW.workspace_id AND checkpoint_id = NEW.checkpoint_id
    AND ciphertext_sha256 = NEW.ciphertext_sha256 AND status = 'uploaded';

  UPDATE workspaces
  SET candidate_checkpoint_id = NEW.checkpoint_id,
      reserved_bytes = MAX(0, reserved_bytes - (
        SELECT reserved_bytes FROM checkpoint_leases
        WHERE workspace_id = NEW.workspace_id AND checkpoint_id = NEW.checkpoint_id
          AND ciphertext_sha256 = NEW.ciphertext_sha256
      )),
      committed_bytes = committed_bytes + NEW.byte_size
  WHERE workspace_id = NEW.workspace_id;

  UPDATE workspace_usage_reservations SET status = 'settled'
  WHERE reservation_id = (
    SELECT lease_id FROM checkpoint_leases
    WHERE workspace_id = NEW.workspace_id AND checkpoint_id = NEW.checkpoint_id
      AND ciphertext_sha256 = NEW.ciphertext_sha256
  );
END;

CREATE TRIGGER checkpoints_delete_accounting
AFTER UPDATE OF status ON checkpoints
WHEN OLD.status IN ('gc_pending', 'rejected') AND NEW.status = 'deleted'
BEGIN
  UPDATE workspaces SET committed_bytes = MAX(0, committed_bytes - OLD.byte_size)
  WHERE workspace_id = OLD.workspace_id;
END;

CREATE TABLE recovery_challenges (
  challenge_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(user_id),
  challenge_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  created_at INTEGER NOT NULL
);

-- Replacement-root wraps are staged inside the same D1 batch as the claim. The claim trigger
-- compares this exact set with every epoch needed by active state, retained stable checkpoints,
-- or the event tail before any staged wrap becomes authoritative.
CREATE TABLE recovery_claim_key_envelopes (
  claim_id TEXT NOT NULL,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  key_epoch INTEGER NOT NULL CHECK(key_epoch > 0),
  rotation_id TEXT NOT NULL,
  key_commitment TEXT NOT NULL,
  wrapped_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(claim_id, workspace_id, key_epoch),
  UNIQUE(workspace_id, rotation_id)
);

CREATE TABLE recovery_claims (
  claim_id TEXT PRIMARY KEY,
  challenge_id TEXT NOT NULL UNIQUE REFERENCES recovery_challenges(challenge_id),
  user_id TEXT NOT NULL REFERENCES users(user_id),
  new_device_id TEXT NOT NULL UNIQUE REFERENCES devices(device_id),
  workspace_envelope_count INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE recovery_claim_workspaces (
  claim_id TEXT NOT NULL REFERENCES recovery_claims(claim_id) ON DELETE CASCADE,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  device_auth_epoch INTEGER NOT NULL CHECK(device_auth_epoch > 0),
  membership_auth_epoch INTEGER NOT NULL CHECK(membership_auth_epoch > 0),
  active_key_epoch INTEGER NOT NULL CHECK(active_key_epoch > 0),
  PRIMARY KEY(claim_id, workspace_id)
);

CREATE TRIGGER recovery_claims_guard
BEFORE INSERT ON recovery_claims
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM recovery_challenges c
    WHERE c.challenge_id = NEW.challenge_id AND c.user_id = NEW.user_id
      AND c.consumed_at IS NULL AND c.expires_at > NEW.created_at
  ) THEN RAISE(ABORT, 'recovery_challenge_invalid') END;

  SELECT CASE WHEN NEW.workspace_envelope_count <> (
    SELECT COUNT(*) FROM memberships
    WHERE user_id = NEW.user_id AND status = 'active'
  ) THEN RAISE(ABORT, 'recovery_keyring_incomplete') END;

  SELECT CASE WHEN (
    SELECT COUNT(*) FROM recovery_claim_key_envelopes staged
    WHERE staged.claim_id = NEW.claim_id
  ) <> (
    SELECT COUNT(*) FROM (
      SELECT w.workspace_id, w.active_key_epoch AS key_epoch
      FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
      WHERE m.user_id = NEW.user_id AND m.status = 'active' AND w.status = 'active'
      UNION
      SELECT c.workspace_id, c.key_epoch FROM checkpoints c
      JOIN memberships m ON m.workspace_id = c.workspace_id
      JOIN workspaces w ON w.workspace_id = c.workspace_id
      WHERE m.user_id = NEW.user_id AND m.status = 'active' AND w.status = 'active'
        AND c.status = 'stable'
      UNION
      SELECT e.workspace_id, e.key_epoch FROM events e
      JOIN memberships m ON m.workspace_id = e.workspace_id
      JOIN workspaces w ON w.workspace_id = e.workspace_id
      WHERE m.user_id = NEW.user_id AND m.status = 'active' AND w.status = 'active'
    ) required
  ) THEN RAISE(ABORT, 'recovery_keyring_incomplete') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM recovery_claim_key_envelopes staged
    WHERE staged.claim_id = NEW.claim_id AND (
      NOT EXISTS (
        SELECT 1 FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
        WHERE m.user_id = NEW.user_id AND m.status = 'active' AND w.status = 'active'
          AND w.workspace_id = staged.workspace_id
          AND (
            staged.key_epoch = w.active_key_epoch OR
            EXISTS (SELECT 1 FROM checkpoints c WHERE c.workspace_id = w.workspace_id
                    AND c.key_epoch = staged.key_epoch AND c.status = 'stable') OR
            EXISTS (SELECT 1 FROM events e WHERE e.workspace_id = w.workspace_id
                    AND e.key_epoch = staged.key_epoch)
          )
      ) OR NOT EXISTS (
        SELECT 1 FROM workspace_recovery_keys current
        WHERE current.workspace_id = staged.workspace_id
          AND current.key_epoch = staged.key_epoch
          AND current.key_commitment = staged.key_commitment
          AND current.rotation_id = (
            SELECT latest.rotation_id FROM workspace_recovery_keys latest
            WHERE latest.workspace_id = staged.workspace_id
              AND latest.key_epoch = staged.key_epoch
            ORDER BY latest.created_at DESC, latest.rotation_id DESC LIMIT 1
          )
      )
    )
  ) THEN RAISE(ABORT, 'recovery_keyring_incomplete') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
    WHERE m.user_id = NEW.user_id AND m.status = 'active' AND w.status = 'active'
      AND NOT EXISTS (
        SELECT 1 FROM workspace_device_keys device_key
        WHERE device_key.workspace_id = w.workspace_id
          AND device_key.device_id = NEW.new_device_id
          AND device_key.key_epoch = w.active_key_epoch
          AND device_key.key_commitment = (
            SELECT current.key_commitment FROM workspace_recovery_keys current
            WHERE current.workspace_id = w.workspace_id
              AND current.key_epoch = w.active_key_epoch
            ORDER BY current.created_at DESC, current.rotation_id DESC LIMIT 1
          )
      )
  ) THEN RAISE(ABORT, 'recovery_keyring_incomplete') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM device_key_attestations a
    WHERE a.device_id = NEW.new_device_id AND a.attestation_type = 'recovery'
  ) THEN RAISE(ABORT, 'device_attestation_missing') END;
END;

CREATE TRIGGER recovery_claims_apply
AFTER INSERT ON recovery_claims
BEGIN
  INSERT INTO workspace_recovery_keys(
    workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
  ) SELECT workspace_id, key_epoch, rotation_id, key_commitment, wrapped_key, created_at
    FROM recovery_claim_key_envelopes WHERE claim_id = NEW.claim_id;

  DELETE FROM recovery_claim_key_envelopes WHERE claim_id = NEW.claim_id;

  UPDATE recovery_challenges SET consumed_at = NEW.created_at
  WHERE challenge_id = NEW.challenge_id;

  UPDATE devices
  SET status = 'revoked', auth_epoch = auth_epoch + 1, revoked_at = NEW.created_at
  WHERE user_id = NEW.user_id AND device_id <> NEW.new_device_id AND status = 'active';

  UPDATE access_tokens SET revoked_at = NEW.created_at
  WHERE device_id IN (
    SELECT device_id FROM devices WHERE user_id = NEW.user_id AND device_id <> NEW.new_device_id
  ) AND revoked_at IS NULL;

  UPDATE workspace_capabilities SET revoked_at = NEW.created_at
  WHERE device_id IN (
    SELECT device_id FROM devices WHERE user_id = NEW.user_id AND device_id <> NEW.new_device_id
  ) AND revoked_at IS NULL;

  UPDATE key_rotations SET status = 'aborted'
  WHERE status = 'leased' AND workspace_id IN (
    SELECT workspace_id FROM memberships WHERE user_id = NEW.user_id AND status = 'active'
  );

  UPDATE workspaces SET pending_rotation_id = NULL
  WHERE pending_rotation_id IN (
    SELECT rotation_id FROM key_rotations WHERE status = 'aborted'
  );

  UPDATE workspaces
  SET rotation_required = 1,
      device_directory_epoch = device_directory_epoch + 1
  WHERE workspace_id IN (
    SELECT workspace_id FROM memberships WHERE user_id = NEW.user_id AND status = 'active'
  );
END;

CREATE TABLE usage_daily (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  day TEXT NOT NULL,
  events_written INTEGER NOT NULL DEFAULT 0,
  event_bytes_written INTEGER NOT NULL DEFAULT 0,
  checkpoints_written INTEGER NOT NULL DEFAULT 0,
  checkpoint_bytes_written INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(workspace_id, day)
);

CREATE TABLE edge_rate_windows (
  scope_hash TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  request_count INTEGER NOT NULL,
  PRIMARY KEY(scope_hash, window_start)
);

CREATE TABLE audit_events (
  audit_id TEXT PRIMARY KEY,
  actor_device_id TEXT,
  action TEXT NOT NULL,
  target_id TEXT,
  created_at INTEGER NOT NULL
);

INSERT INTO schema_migrations(version, name, applied_at)
VALUES (1, 'initial_cross_platform_sync', 0);
