PRAGMA foreign_keys = ON;

-- Protocol/schema v2 keeps encrypted content bodies out of the event/checkpoint plane. The
-- existing workspace committed/reserved counters remain the single quota authority so a client
-- cannot evade limits by spreading bytes across events, checkpoints, and body uploads.
ALTER TABLE quota_profiles
  ADD COLUMN max_blob_bytes INTEGER NOT NULL DEFAULT 134217728
  CHECK (max_blob_bytes > 0);

ALTER TABLE quota_profiles
  ADD COLUMN max_blob_chunk_bytes INTEGER NOT NULL DEFAULT 8388624
  CHECK (max_blob_chunk_bytes > 0);

ALTER TABLE quota_profiles
  ADD COLUMN max_blob_manifest_bytes INTEGER NOT NULL DEFAULT 524288
  CHECK (max_blob_manifest_bytes > 0);

ALTER TABLE quota_profiles
  ADD COLUMN max_blob_chunks INTEGER NOT NULL DEFAULT 1024
  CHECK (max_blob_chunks > 0);

ALTER TABLE usage_daily
  ADD COLUMN blobs_committed INTEGER NOT NULL DEFAULT 0;

ALTER TABLE usage_daily
  ADD COLUMN blob_bytes_committed INTEGER NOT NULL DEFAULT 0;

ALTER TABLE usage_daily
  ADD COLUMN blobs_deleted INTEGER NOT NULL DEFAULT 0;

ALTER TABLE usage_daily
  ADD COLUMN blob_bytes_deleted INTEGER NOT NULL DEFAULT 0;

CREATE TABLE blob_upload_sessions (
  session_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  blob_id TEXT NOT NULL,
  manifest_id TEXT NOT NULL,
  protocol_version INTEGER NOT NULL CHECK (protocol_version = 2),
  schema_version INTEGER NOT NULL CHECK (schema_version = 2),
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  uploader_device_id TEXT NOT NULL REFERENCES devices(device_id),
  initial_capability_id TEXT NOT NULL REFERENCES workspace_capabilities(capability_id),
  chunk_size_bytes INTEGER NOT NULL CHECK (chunk_size_bytes BETWEEN 65536 AND 8388608),
  chunk_plan_sha256 TEXT NOT NULL,
  chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
  total_chunk_bytes INTEGER NOT NULL CHECK (total_chunk_bytes >= 0),
  manifest_ciphertext_sha256 TEXT NOT NULL,
  manifest_byte_size INTEGER NOT NULL CHECK (manifest_byte_size > 0),
  reserved_bytes INTEGER NOT NULL CHECK (
    reserved_bytes = total_chunk_bytes + manifest_byte_size
  ),
  status TEXT NOT NULL CHECK (
    status IN ('staging', 'active', 'committed', 'expired', 'cancelled', 'cleaned')
  ),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  committed_at INTEGER,
  cleaned_at INTEGER,
  orphan_cleanup_chunk_cursor INTEGER NOT NULL DEFAULT -1,
  CHECK (expires_at > created_at),
  CHECK (
    orphan_cleanup_chunk_cursor >= -1 AND orphan_cleanup_chunk_cursor < chunk_count
  )
  ,CHECK ((chunk_count = 0) = (total_chunk_bytes = 0))
);

CREATE UNIQUE INDEX blob_upload_one_active_per_blob
  ON blob_upload_sessions(workspace_id, blob_id)
  WHERE status IN ('staging', 'active');

CREATE INDEX blob_upload_sessions_workspace_status_idx
  ON blob_upload_sessions(workspace_id, status, expires_at);

CREATE TABLE blob_upload_session_chunks (
  session_id TEXT NOT NULL REFERENCES blob_upload_sessions(session_id) ON DELETE CASCADE,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  blob_id TEXT NOT NULL,
  chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
  ciphertext_sha256 TEXT NOT NULL,
  ciphertext_sha256_hex TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0),
  r2_key TEXT NOT NULL,
  PRIMARY KEY(session_id, chunk_index),
  UNIQUE(session_id, ciphertext_sha256, chunk_index)
);

CREATE INDEX blob_upload_chunks_workspace_blob_idx
  ON blob_upload_session_chunks(workspace_id, blob_id, chunk_index);

CREATE TABLE blob_upload_initial_envelopes (
  session_id TEXT PRIMARY KEY REFERENCES blob_upload_sessions(session_id) ON DELETE CASCADE,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  blob_id TEXT NOT NULL,
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  cipher_suite TEXT NOT NULL CHECK (cipher_suite = 'CHACHA20_POLY1305'),
  nonce TEXT NOT NULL,
  wrapped_dek TEXT NOT NULL,
  envelope_sha256 TEXT NOT NULL,
  previous_envelope_sha256 TEXT,
  CHECK (previous_envelope_sha256 IS NULL)
);

CREATE TABLE blob_upload_session_activations (
  session_id TEXT PRIMARY KEY REFERENCES blob_upload_sessions(session_id),
  activated_at INTEGER NOT NULL
);

CREATE TRIGGER blob_upload_session_activations_guard
BEFORE INSERT ON blob_upload_session_activations
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_upload_sessions session
    JOIN workspaces workspace ON workspace.workspace_id = session.workspace_id
    JOIN workspace_capabilities capability
      ON capability.capability_id = session.initial_capability_id
     AND capability.workspace_id = workspace.workspace_id
     AND capability.device_id = session.uploader_device_id
    JOIN devices device ON device.device_id = capability.device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    JOIN quota_profiles quota ON quota.quota_profile_id = user.quota_profile_id
    WHERE session.session_id = NEW.session_id
      AND session.status = 'staging' AND session.expires_at > NEW.activated_at
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active' AND membership.role IN ('owner', 'editor')
      AND capability.revoked_at IS NULL AND capability.expires_at > NEW.activated_at
      AND capability.device_auth_epoch = device.auth_epoch
      AND capability.membership_auth_epoch = membership.auth_epoch
      AND capability.key_epoch = workspace.active_key_epoch
      AND session.key_epoch = workspace.active_key_epoch
      AND session.chunk_count <= quota.max_blob_chunks
      AND session.total_chunk_bytes <= quota.max_blob_bytes
      AND session.manifest_byte_size <= quota.max_blob_manifest_bytes
      AND session.reserved_bytes <= quota.max_blob_bytes + quota.max_blob_manifest_bytes
      AND workspace.committed_bytes + workspace.reserved_bytes + session.reserved_bytes
            <= quota.max_workspace_bytes
      AND (
        SELECT COUNT(*) FROM blob_upload_session_chunks chunk
        WHERE chunk.session_id = session.session_id
      ) = session.chunk_count
      AND (
        SELECT COALESCE(SUM(chunk.byte_size), 0) FROM blob_upload_session_chunks chunk
        WHERE chunk.session_id = session.session_id
      ) = session.total_chunk_bytes
      AND (session.chunk_count = 0 OR (
        (SELECT MIN(chunk.chunk_index) FROM blob_upload_session_chunks chunk
         WHERE chunk.session_id = session.session_id) = 0
        AND
        (SELECT MAX(chunk.chunk_index) FROM blob_upload_session_chunks chunk
         WHERE chunk.session_id = session.session_id) = session.chunk_count - 1
      ))
      AND NOT EXISTS (
        SELECT 1 FROM blob_upload_session_chunks chunk
        WHERE chunk.session_id = session.session_id
          AND chunk.byte_size > quota.max_blob_chunk_bytes
      )
      AND EXISTS (
        SELECT 1 FROM blob_upload_initial_envelopes envelope
        WHERE envelope.session_id = session.session_id
          AND envelope.workspace_id = session.workspace_id
          AND envelope.blob_id = session.blob_id
          AND envelope.key_epoch = session.key_epoch
          AND envelope.previous_envelope_sha256 IS NULL
      )
      AND NOT EXISTS (
        SELECT 1 FROM encrypted_blob_manifests manifest
        WHERE manifest.workspace_id = session.workspace_id
          AND manifest.blob_id = session.blob_id
          AND manifest.status <> 'deleted'
      )
      AND NOT EXISTS (
        SELECT 1 FROM blob_upload_sessions stale
        WHERE stale.workspace_id = session.workspace_id
          AND stale.blob_id = session.blob_id
          AND stale.session_id <> session.session_id
          AND stale.status IN ('expired', 'cancelled')
      )
  ) THEN RAISE(ABORT, 'blob_upload_session_invalid') END;
END;

CREATE TRIGGER blob_upload_session_activations_apply
AFTER INSERT ON blob_upload_session_activations
BEGIN
  UPDATE blob_upload_sessions SET status = 'active'
  WHERE session_id = NEW.session_id AND status = 'staging';

  UPDATE workspaces
  SET reserved_bytes = reserved_bytes + (
    SELECT reserved_bytes FROM blob_upload_sessions WHERE session_id = NEW.session_id
  )
  WHERE workspace_id = (
    SELECT workspace_id FROM blob_upload_sessions WHERE session_id = NEW.session_id
  );

  INSERT INTO workspace_usage_reservations(
    reservation_id, workspace_id, byte_size, status, expires_at, created_at
  )
  SELECT 'blob:' || session_id, workspace_id, reserved_bytes, 'active', expires_at, created_at
  FROM blob_upload_sessions WHERE session_id = NEW.session_id;
END;

-- Expiry only revokes upload authority. Keep the reservation charged until the bounded R2
-- orphan sweep has deleted and re-HEADed every planned object. This deliberately prefers a
-- conservative quota over letting physical ciphertext escape workspace accounting.
CREATE TRIGGER blob_upload_sessions_release_reservation
AFTER UPDATE OF status ON blob_upload_sessions
WHEN OLD.status IN ('expired', 'cancelled') AND NEW.status = 'cleaned'
BEGIN
  UPDATE workspaces
  SET reserved_bytes = MAX(0, reserved_bytes - OLD.reserved_bytes)
  WHERE workspace_id = OLD.workspace_id;

  UPDATE workspace_usage_reservations SET status = 'released'
  WHERE reservation_id = 'blob:' || OLD.session_id AND status = 'active';
END;

CREATE TABLE blob_chunk_receipts (
  receipt_id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES blob_upload_sessions(session_id),
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  blob_id TEXT NOT NULL,
  chunk_index INTEGER NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0),
  r2_key TEXT NOT NULL,
  uploaded_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
  capability_id TEXT NOT NULL REFERENCES workspace_capabilities(capability_id),
  created_at INTEGER NOT NULL,
  UNIQUE(session_id, chunk_index)
);

CREATE INDEX blob_chunk_receipts_workspace_blob_idx
  ON blob_chunk_receipts(workspace_id, blob_id, chunk_index);

CREATE TRIGGER blob_chunk_receipts_guard
BEFORE INSERT ON blob_chunk_receipts
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_upload_sessions session
    JOIN blob_upload_session_chunks planned
      ON planned.session_id = session.session_id
     AND planned.chunk_index = NEW.chunk_index
    JOIN workspaces workspace ON workspace.workspace_id = session.workspace_id
    JOIN workspace_capabilities capability
      ON capability.capability_id = NEW.capability_id
     AND capability.workspace_id = workspace.workspace_id
     AND capability.device_id = NEW.uploaded_by_device_id
    JOIN devices device ON device.device_id = capability.device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    WHERE session.session_id = NEW.session_id
      AND session.workspace_id = NEW.workspace_id AND session.blob_id = NEW.blob_id
      AND session.status = 'active' AND session.expires_at > NEW.created_at
      AND session.uploader_device_id = NEW.uploaded_by_device_id
      AND planned.ciphertext_sha256 = NEW.ciphertext_sha256
      AND planned.byte_size = NEW.byte_size AND planned.r2_key = NEW.r2_key
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND workspace.active_key_epoch = session.key_epoch
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active' AND membership.role IN ('owner', 'editor')
      AND capability.revoked_at IS NULL AND capability.expires_at > NEW.created_at
      AND capability.device_auth_epoch = device.auth_epoch
      AND capability.membership_auth_epoch = membership.auth_epoch
      AND capability.key_epoch = workspace.active_key_epoch
  ) THEN RAISE(ABORT, 'blob_chunk_receipt_invalid') END;
END;

CREATE TABLE encrypted_blob_manifests (
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  blob_id TEXT NOT NULL,
  manifest_id TEXT NOT NULL,
  session_id TEXT NOT NULL UNIQUE REFERENCES blob_upload_sessions(session_id),
  protocol_version INTEGER NOT NULL CHECK (protocol_version = 2),
  schema_version INTEGER NOT NULL CHECK (schema_version = 2),
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  private_manifest_nonce TEXT NOT NULL,
  chunk_plan_sha256 TEXT NOT NULL,
  chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
  chunk_size_bytes INTEGER NOT NULL CHECK (chunk_size_bytes BETWEEN 65536 AND 8388608),
  total_chunk_bytes INTEGER NOT NULL CHECK (total_chunk_bytes >= 0),
  manifest_ciphertext_sha256 TEXT NOT NULL,
  manifest_byte_size INTEGER NOT NULL CHECK (manifest_byte_size > 0),
  manifest_r2_key TEXT NOT NULL UNIQUE,
  uploader_device_id TEXT NOT NULL REFERENCES devices(device_id),
  current_envelope_epoch INTEGER NOT NULL CHECK (current_envelope_epoch > 0),
  status TEXT NOT NULL CHECK (
    status IN ('candidate', 'committed', 'tombstoned', 'deleting', 'deleted')
  ),
  created_at INTEGER NOT NULL,
  committed_at INTEGER,
  deleted_at INTEGER,
  PRIMARY KEY(workspace_id, blob_id)
  ,UNIQUE(workspace_id, manifest_id)
);

CREATE INDEX encrypted_blob_manifests_workspace_status_idx
  ON encrypted_blob_manifests(workspace_id, status, created_at);

CREATE TABLE blob_dek_envelopes (
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  envelope_version INTEGER NOT NULL CHECK (envelope_version = 1),
  cipher_suite TEXT NOT NULL CHECK (cipher_suite = 'CHACHA20_POLY1305'),
  nonce TEXT NOT NULL,
  wrapped_dek TEXT NOT NULL,
  envelope_sha256 TEXT NOT NULL,
  previous_envelope_sha256 TEXT,
  evidence_checkpoint_id TEXT,
  evidence_checkpoint_sha256 TEXT,
  evidence_through_seq INTEGER,
  created_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
  authorization_capability_id TEXT REFERENCES workspace_capabilities(capability_id),
  authorization_device_auth_epoch INTEGER CHECK (
    authorization_device_auth_epoch IS NULL OR authorization_device_auth_epoch > 0
  ),
  authorization_membership_auth_epoch INTEGER CHECK (
    authorization_membership_auth_epoch IS NULL OR authorization_membership_auth_epoch > 0
  ),
  authorization_key_epoch INTEGER CHECK (
    authorization_key_epoch IS NULL OR authorization_key_epoch > 0
  ),
  authorization_role TEXT CHECK (
    authorization_role IS NULL OR authorization_role IN ('owner', 'editor')
  ),
  status TEXT NOT NULL CHECK (status IN ('current', 'retained')),
  created_at INTEGER NOT NULL,
  PRIMARY KEY(workspace_id, blob_id, key_epoch),
  UNIQUE(workspace_id, blob_id, envelope_sha256),
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id),
  CHECK (
    (evidence_checkpoint_id IS NULL AND evidence_checkpoint_sha256 IS NULL AND
      evidence_through_seq IS NULL) OR
    (evidence_checkpoint_id IS NOT NULL AND evidence_checkpoint_sha256 IS NOT NULL AND
      evidence_through_seq IS NOT NULL AND evidence_through_seq >= 0)
  ),
  CHECK (
    (previous_envelope_sha256 IS NULL AND authorization_capability_id IS NULL AND
      authorization_device_auth_epoch IS NULL AND
      authorization_membership_auth_epoch IS NULL AND authorization_key_epoch IS NULL AND
      authorization_role IS NULL) OR
    (previous_envelope_sha256 IS NOT NULL AND authorization_capability_id IS NOT NULL AND
      authorization_device_auth_epoch IS NOT NULL AND
      authorization_membership_auth_epoch IS NOT NULL AND authorization_key_epoch IS NOT NULL AND
      authorization_role IS NOT NULL)
  )
);

-- A re-wrap is a forward-only compare-and-swap from the one current envelope. Requiring the
-- retained schema-v2 checkpoint inside D1 closes the race between an epoch change and concurrent
-- re-wrap requests; the first valid insert wins and lower/foreign chains cannot demote it.
CREATE TRIGGER blob_dek_envelopes_rewrap_guard
BEFORE INSERT ON blob_dek_envelopes
WHEN NEW.previous_envelope_sha256 IS NOT NULL
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM encrypted_blob_manifests manifest
    JOIN workspaces workspace ON workspace.workspace_id = manifest.workspace_id
    JOIN blob_dek_envelopes current_envelope
      ON current_envelope.workspace_id = manifest.workspace_id
     AND current_envelope.blob_id = manifest.blob_id
     AND current_envelope.key_epoch = manifest.current_envelope_epoch
     AND current_envelope.status = 'current'
    JOIN checkpoints checkpoint
      ON checkpoint.workspace_id = manifest.workspace_id
     AND checkpoint.checkpoint_id = NEW.evidence_checkpoint_id
     AND checkpoint.ciphertext_sha256 = NEW.evidence_checkpoint_sha256
     AND checkpoint.through_seq = NEW.evidence_through_seq
    JOIN workspace_capabilities capability
      ON capability.capability_id = NEW.authorization_capability_id
     AND capability.workspace_id = manifest.workspace_id
     AND capability.device_id = NEW.created_by_device_id
    JOIN devices device ON device.device_id = capability.device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = manifest.workspace_id
     AND membership.user_id = device.user_id
    WHERE manifest.workspace_id = NEW.workspace_id AND manifest.blob_id = NEW.blob_id
      AND manifest.status IN ('committed', 'tombstoned')
      AND NEW.status = 'current' AND NEW.key_epoch > manifest.current_envelope_epoch
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND workspace.active_key_epoch = NEW.key_epoch
      AND device.status = 'active' AND device.auth_epoch = NEW.authorization_device_auth_epoch
      AND user.status = 'active'
      AND membership.status = 'active' AND membership.role IN ('owner', 'editor')
      AND membership.role = NEW.authorization_role
      AND membership.auth_epoch = NEW.authorization_membership_auth_epoch
      AND capability.revoked_at IS NULL AND capability.expires_at > NEW.created_at
      AND capability.device_auth_epoch = NEW.authorization_device_auth_epoch
      AND capability.device_auth_epoch = device.auth_epoch
      AND capability.membership_auth_epoch = NEW.authorization_membership_auth_epoch
      AND capability.membership_auth_epoch = membership.auth_epoch
      AND capability.key_epoch = NEW.authorization_key_epoch
      AND capability.key_epoch = workspace.active_key_epoch
      AND NEW.authorization_key_epoch = NEW.key_epoch
      AND current_envelope.envelope_sha256 = NEW.previous_envelope_sha256
      AND checkpoint.status = 'stable' AND checkpoint.schema_version = 2
      AND checkpoint.key_epoch = NEW.key_epoch
  ) THEN RAISE(ABORT, 'blob_envelope_rewrap_invalid') END;
END;

CREATE TRIGGER blob_dek_envelopes_promote
AFTER INSERT ON blob_dek_envelopes
BEGIN
  UPDATE blob_dek_envelopes SET status = 'retained'
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND key_epoch <> NEW.key_epoch AND status = 'current';

  UPDATE encrypted_blob_manifests SET current_envelope_epoch = NEW.key_epoch
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND current_envelope_epoch <= NEW.key_epoch;
END;

CREATE TABLE blob_manifest_commits (
  receipt_id TEXT NOT NULL UNIQUE,
  session_id TEXT PRIMARY KEY REFERENCES blob_upload_sessions(session_id),
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  manifest_id TEXT NOT NULL,
  manifest_ciphertext_sha256 TEXT NOT NULL,
  chunk_plan_sha256 TEXT NOT NULL,
  capability_id TEXT NOT NULL REFERENCES workspace_capabilities(capability_id),
  committed_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
  committed_at INTEGER NOT NULL,
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id)
);

CREATE TRIGGER blob_manifest_commits_guard
BEFORE INSERT ON blob_manifest_commits
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_upload_sessions session
    JOIN encrypted_blob_manifests manifest
      ON manifest.workspace_id = session.workspace_id
     AND manifest.blob_id = session.blob_id
     AND manifest.session_id = session.session_id
    JOIN workspaces workspace ON workspace.workspace_id = session.workspace_id
    JOIN workspace_capabilities capability
      ON capability.capability_id = NEW.capability_id
     AND capability.workspace_id = workspace.workspace_id
     AND capability.device_id = NEW.committed_by_device_id
    JOIN devices device ON device.device_id = capability.device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    WHERE session.session_id = NEW.session_id
      AND session.workspace_id = NEW.workspace_id AND session.blob_id = NEW.blob_id
      AND session.manifest_id = NEW.manifest_id
      AND session.status = 'active' AND session.expires_at > NEW.committed_at
      AND session.uploader_device_id = NEW.committed_by_device_id
      AND session.manifest_ciphertext_sha256 = NEW.manifest_ciphertext_sha256
      AND session.chunk_plan_sha256 = NEW.chunk_plan_sha256
      AND manifest.status = 'candidate'
      AND manifest.manifest_id = NEW.manifest_id
      AND manifest.manifest_ciphertext_sha256 = NEW.manifest_ciphertext_sha256
      AND manifest.chunk_plan_sha256 = NEW.chunk_plan_sha256
      AND manifest.key_epoch = session.key_epoch
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND workspace.active_key_epoch = session.key_epoch
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active' AND membership.role IN ('owner', 'editor')
      AND capability.revoked_at IS NULL AND capability.expires_at > NEW.committed_at
      AND capability.device_auth_epoch = device.auth_epoch
      AND capability.membership_auth_epoch = membership.auth_epoch
      AND capability.key_epoch = workspace.active_key_epoch
      AND (
        SELECT COUNT(*) FROM blob_chunk_receipts receipt
        WHERE receipt.session_id = session.session_id
      ) = session.chunk_count
      AND (
        SELECT COALESCE(SUM(receipt.byte_size), 0) FROM blob_chunk_receipts receipt
        WHERE receipt.session_id = session.session_id
      ) = session.total_chunk_bytes
      AND NOT EXISTS (
        SELECT 1 FROM blob_upload_session_chunks planned
        LEFT JOIN blob_chunk_receipts receipt
          ON receipt.session_id = planned.session_id
         AND receipt.chunk_index = planned.chunk_index
         AND receipt.ciphertext_sha256 = planned.ciphertext_sha256
         AND receipt.byte_size = planned.byte_size
         AND receipt.r2_key = planned.r2_key
        WHERE planned.session_id = session.session_id AND receipt.receipt_id IS NULL
      )
      AND EXISTS (
        SELECT 1 FROM blob_dek_envelopes envelope
        WHERE envelope.workspace_id = manifest.workspace_id
          AND envelope.blob_id = manifest.blob_id
          AND envelope.key_epoch = manifest.key_epoch
          AND envelope.status = 'current'
      )
  ) THEN RAISE(ABORT, 'blob_manifest_commit_invalid') END;
END;

CREATE TRIGGER blob_manifest_commits_apply
AFTER INSERT ON blob_manifest_commits
BEGIN
  UPDATE encrypted_blob_manifests
  SET status = 'committed', committed_at = NEW.committed_at
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND status = 'candidate';

  UPDATE blob_upload_sessions
  SET status = 'committed', committed_at = NEW.committed_at
  WHERE session_id = NEW.session_id AND status = 'active';

  UPDATE workspaces
  SET reserved_bytes = MAX(0, reserved_bytes - (
        SELECT reserved_bytes FROM blob_upload_sessions WHERE session_id = NEW.session_id
      )),
      committed_bytes = committed_bytes + (
        SELECT reserved_bytes FROM blob_upload_sessions WHERE session_id = NEW.session_id
      )
  WHERE workspace_id = NEW.workspace_id;

  UPDATE workspace_usage_reservations SET status = 'settled'
  WHERE reservation_id = 'blob:' || NEW.session_id AND status = 'active';

  INSERT INTO usage_daily(
    workspace_id, day, blobs_committed, blob_bytes_committed
  )
  SELECT workspace_id, strftime('%Y-%m-%d', NEW.committed_at / 1000, 'unixepoch'),
         1, reserved_bytes
  FROM blob_upload_sessions WHERE session_id = NEW.session_id
  ON CONFLICT(workspace_id, day) DO UPDATE SET
    blobs_committed = blobs_committed + 1,
    blob_bytes_committed = blob_bytes_committed + excluded.blob_bytes_committed;
END;

CREATE TABLE blob_tombstones (
  tombstone_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  manifest_id TEXT NOT NULL,
  manifest_ciphertext_sha256 TEXT NOT NULL,
  reference_through_seq INTEGER NOT NULL CHECK (reference_through_seq >= 0),
  evidence_checkpoint_id TEXT,
  evidence_checkpoint_sha256 TEXT,
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  created_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
  status TEXT NOT NULL CHECK (status IN ('waiting', 'cancelled', 'deleting', 'deleted')),
  execute_after INTEGER NOT NULL,
  requested_created_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  cancelled_at INTEGER,
  deleted_at INTEGER,
  UNIQUE(workspace_id, blob_id, manifest_id, reference_through_seq),
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id),
  CHECK (execute_after > created_at),
  CHECK (
    (evidence_checkpoint_id IS NULL AND evidence_checkpoint_sha256 IS NULL) OR
    (evidence_checkpoint_id IS NOT NULL AND evidence_checkpoint_sha256 IS NOT NULL)
  )
);

CREATE INDEX blob_tombstones_workspace_status_idx
  ON blob_tombstones(workspace_id, status, execute_after);

-- A cancelled removal boundary remains as audit history while a later remove/revive cycle may
-- create a new boundary. Only one boundary can authorize acknowledgement or deletion at a time.
CREATE UNIQUE INDEX blob_tombstones_one_active_idx
  ON blob_tombstones(workspace_id, blob_id)
  WHERE status IN ('waiting', 'deleting');

CREATE TRIGGER blob_tombstones_guard
BEFORE INSERT ON blob_tombstones
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM encrypted_blob_manifests manifest
    JOIN workspaces workspace ON workspace.workspace_id = manifest.workspace_id
    JOIN devices device ON device.device_id = NEW.created_by_device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    WHERE manifest.workspace_id = NEW.workspace_id AND manifest.blob_id = NEW.blob_id
      AND manifest.manifest_id = NEW.manifest_id
      AND manifest.manifest_ciphertext_sha256 = NEW.manifest_ciphertext_sha256
      AND manifest.status = 'committed'
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND workspace.active_key_epoch = NEW.key_epoch
      AND manifest.current_envelope_epoch = workspace.active_key_epoch
      AND NEW.reference_through_seq <= workspace.head_seq
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active' AND membership.role IN ('owner', 'editor')
  ) THEN RAISE(ABORT, 'blob_tombstone_invalid') END;
END;

CREATE TRIGGER blob_tombstones_apply
AFTER INSERT ON blob_tombstones
BEGIN
  UPDATE encrypted_blob_manifests SET status = 'tombstoned'
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND manifest_id = NEW.manifest_id
    AND manifest_ciphertext_sha256 = NEW.manifest_ciphertext_sha256
    AND status = 'committed';

  SELECT CASE WHEN changes() <> 1 THEN RAISE(ABORT, 'blob_tombstone_invalid') END;
END;

CREATE TABLE blob_tombstone_acks (
  tombstone_id TEXT NOT NULL REFERENCES blob_tombstones(tombstone_id),
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  validator_device_id TEXT NOT NULL REFERENCES devices(device_id),
  evidence_checkpoint_id TEXT NOT NULL,
  evidence_checkpoint_sha256 TEXT NOT NULL,
  through_seq INTEGER NOT NULL CHECK (through_seq >= 0),
  device_signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(tombstone_id, validator_device_id),
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id)
);

CREATE TRIGGER blob_tombstone_acks_guard
BEFORE INSERT ON blob_tombstone_acks
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_tombstones tombstone
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    JOIN devices device ON device.device_id = NEW.validator_device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    JOIN checkpoints checkpoint
      ON checkpoint.workspace_id = tombstone.workspace_id
     AND checkpoint.checkpoint_id = NEW.evidence_checkpoint_id
     AND checkpoint.ciphertext_sha256 = NEW.evidence_checkpoint_sha256
     AND checkpoint.through_seq = NEW.through_seq
    WHERE tombstone.tombstone_id = NEW.tombstone_id
      AND tombstone.workspace_id = NEW.workspace_id AND tombstone.blob_id = NEW.blob_id
      AND tombstone.status = 'waiting'
      AND NEW.through_seq >= tombstone.reference_through_seq
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND checkpoint.status = 'stable' AND checkpoint.schema_version = 2
      AND checkpoint.key_epoch = workspace.active_key_epoch
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active'
  ) THEN RAISE(ABORT, 'blob_tombstone_ack_invalid') END;
END;

CREATE TRIGGER blob_tombstone_acks_record_evidence
AFTER INSERT ON blob_tombstone_acks
BEGIN
  UPDATE blob_tombstones
  SET evidence_checkpoint_id = COALESCE(evidence_checkpoint_id, NEW.evidence_checkpoint_id),
      evidence_checkpoint_sha256 = COALESCE(evidence_checkpoint_sha256, NEW.evidence_checkpoint_sha256)
  WHERE tombstone_id = NEW.tombstone_id;
END;

-- A rotation invalidates the old acknowledgement for GC purposes, but not the immutable
-- tombstone boundary. Let the same device replace its acknowledgement with strictly newer
-- retained evidence: either a later key epoch or a later watermark in the same epoch.
CREATE TRIGGER blob_tombstone_acks_refresh_guard
BEFORE UPDATE OF evidence_checkpoint_id, evidence_checkpoint_sha256, through_seq,
  device_signature, created_at ON blob_tombstone_acks
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_tombstones tombstone
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    JOIN devices device ON device.device_id = NEW.validator_device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    JOIN checkpoints checkpoint
      ON checkpoint.workspace_id = tombstone.workspace_id
     AND checkpoint.checkpoint_id = NEW.evidence_checkpoint_id
     AND checkpoint.ciphertext_sha256 = NEW.evidence_checkpoint_sha256
     AND checkpoint.through_seq = NEW.through_seq
    JOIN checkpoints old_checkpoint
      ON old_checkpoint.workspace_id = OLD.workspace_id
     AND old_checkpoint.checkpoint_id = OLD.evidence_checkpoint_id
     AND old_checkpoint.ciphertext_sha256 = OLD.evidence_checkpoint_sha256
     AND old_checkpoint.through_seq = OLD.through_seq
    WHERE tombstone.tombstone_id = NEW.tombstone_id
      AND tombstone.workspace_id = NEW.workspace_id AND tombstone.blob_id = NEW.blob_id
      AND NEW.tombstone_id = OLD.tombstone_id
      AND NEW.workspace_id = OLD.workspace_id AND NEW.blob_id = OLD.blob_id
      AND NEW.validator_device_id = OLD.validator_device_id
      AND tombstone.status = 'waiting'
      AND NEW.through_seq >= tombstone.reference_through_seq
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND checkpoint.status = 'stable' AND checkpoint.schema_version = 2
      AND checkpoint.key_epoch = workspace.active_key_epoch
      AND (
        checkpoint.key_epoch > old_checkpoint.key_epoch OR
        (checkpoint.key_epoch = old_checkpoint.key_epoch AND NEW.through_seq > OLD.through_seq)
      )
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active'
  ) THEN RAISE(ABORT, 'blob_tombstone_ack_invalid') END;
END;

CREATE TRIGGER blob_tombstone_acks_refresh_evidence
AFTER UPDATE OF evidence_checkpoint_id, evidence_checkpoint_sha256, through_seq ON blob_tombstone_acks
BEGIN
  UPDATE blob_tombstones
  SET evidence_checkpoint_id = NEW.evidence_checkpoint_id,
      evidence_checkpoint_sha256 = NEW.evidence_checkpoint_sha256
  WHERE tombstone_id = NEW.tombstone_id;
END;

CREATE TABLE blob_tombstone_revivals (
  tombstone_id TEXT NOT NULL REFERENCES blob_tombstones(tombstone_id),
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  manifest_id TEXT NOT NULL,
  validator_device_id TEXT NOT NULL REFERENCES devices(device_id),
  evidence_checkpoint_id TEXT NOT NULL,
  evidence_checkpoint_sha256 TEXT NOT NULL,
  through_seq INTEGER NOT NULL CHECK (through_seq >= 0),
  device_signature TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('cancelled', 'reupload_required')),
  created_at INTEGER NOT NULL,
  PRIMARY KEY(tombstone_id, validator_device_id),
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id)
);

-- The Worker cannot decrypt checkpoint state. This trigger proves that an active device signed
-- a strictly newer retained checkpoint identity, and makes cancellation race atomically with the
-- GC claim's WAITING -> DELETING transition.
CREATE TRIGGER blob_tombstone_revivals_guard
BEFORE INSERT ON blob_tombstone_revivals
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_tombstones tombstone
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    JOIN devices device ON device.device_id = NEW.validator_device_id
    JOIN users user ON user.user_id = device.user_id
    JOIN memberships membership
      ON membership.workspace_id = workspace.workspace_id
     AND membership.user_id = device.user_id
    JOIN checkpoints checkpoint
      ON checkpoint.workspace_id = tombstone.workspace_id
     AND checkpoint.checkpoint_id = NEW.evidence_checkpoint_id
     AND checkpoint.ciphertext_sha256 = NEW.evidence_checkpoint_sha256
     AND checkpoint.through_seq = NEW.through_seq
    WHERE tombstone.tombstone_id = NEW.tombstone_id
      AND tombstone.workspace_id = NEW.workspace_id AND tombstone.blob_id = NEW.blob_id
      AND tombstone.manifest_id = NEW.manifest_id
      AND NEW.through_seq > tombstone.reference_through_seq
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND checkpoint.status = 'stable' AND checkpoint.schema_version = 2
      AND checkpoint.key_epoch = workspace.active_key_epoch
      AND device.status = 'active' AND user.status = 'active'
      AND membership.status = 'active' AND membership.role IN ('owner', 'editor')
      AND (
        (NEW.outcome = 'cancelled' AND (
          (tombstone.status = 'waiting' AND EXISTS (
            SELECT 1 FROM encrypted_blob_manifests manifest
            WHERE manifest.workspace_id = tombstone.workspace_id
              AND manifest.blob_id = tombstone.blob_id
              AND manifest.manifest_id = tombstone.manifest_id
              AND manifest.manifest_ciphertext_sha256 = tombstone.manifest_ciphertext_sha256
              AND manifest.status = 'tombstoned'
          )) OR
          tombstone.status = 'cancelled'
        )) OR
        (NEW.outcome = 'reupload_required' AND (
          (tombstone.status = 'deleting' AND EXISTS (
            SELECT 1 FROM blob_gc_claims claim
            WHERE claim.tombstone_id = tombstone.tombstone_id
              AND claim.workspace_id = tombstone.workspace_id
              AND claim.blob_id = tombstone.blob_id
              AND claim.status IN ('deleting', 'complete')
          )) OR
          (tombstone.status = 'deleted' AND EXISTS (
            SELECT 1 FROM blob_gc_receipts receipt
            WHERE receipt.tombstone_id = tombstone.tombstone_id
              AND receipt.workspace_id = tombstone.workspace_id
              AND receipt.blob_id = tombstone.blob_id
              AND receipt.manifest_ciphertext_sha256 = tombstone.manifest_ciphertext_sha256
              AND receipt.verified_remaining_count = 0
          ))
        ))
      )
  ) THEN RAISE(ABORT, 'blob_tombstone_revival_invalid') END;
END;

CREATE TRIGGER blob_tombstone_revivals_apply
AFTER INSERT ON blob_tombstone_revivals
WHEN NEW.outcome = 'cancelled'
BEGIN
  UPDATE blob_tombstones
  SET status = 'cancelled', cancelled_at = COALESCE(cancelled_at, NEW.created_at)
  WHERE tombstone_id = NEW.tombstone_id AND status = 'waiting';

  UPDATE encrypted_blob_manifests SET status = 'committed'
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND manifest_id = NEW.manifest_id AND status = 'tombstoned';
END;

CREATE TABLE blob_gc_claims (
  claim_id TEXT PRIMARY KEY,
  tombstone_id TEXT NOT NULL UNIQUE REFERENCES blob_tombstones(tombstone_id),
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  object_list_sha256 TEXT NOT NULL,
  object_count INTEGER NOT NULL CHECK (object_count > 0),
  status TEXT NOT NULL CHECK (status IN ('deleting', 'complete')),
  claimed_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
  capability_id TEXT NOT NULL REFERENCES workspace_capabilities(capability_id),
  device_auth_epoch INTEGER NOT NULL CHECK (device_auth_epoch > 0),
  membership_auth_epoch INTEGER NOT NULL CHECK (membership_auth_epoch > 0),
  key_epoch INTEGER NOT NULL CHECK (key_epoch > 0),
  authorized_role TEXT NOT NULL CHECK (authorized_role IN ('owner', 'editor')),
  claimed_at INTEGER NOT NULL,
  completed_at INTEGER,
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id)
);

CREATE TABLE blob_gc_receipts (
  claim_id TEXT PRIMARY KEY REFERENCES blob_gc_claims(claim_id),
  tombstone_id TEXT NOT NULL UNIQUE REFERENCES blob_tombstones(tombstone_id),
  workspace_id TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  manifest_ciphertext_sha256 TEXT NOT NULL,
  object_list_sha256 TEXT NOT NULL,
  deleted_object_count INTEGER NOT NULL CHECK (deleted_object_count > 0),
  deleted_ciphertext_bytes INTEGER NOT NULL CHECK (deleted_ciphertext_bytes > 0),
  verified_remaining_count INTEGER NOT NULL CHECK (verified_remaining_count = 0),
  completed_at INTEGER NOT NULL,
  FOREIGN KEY(workspace_id, blob_id)
    REFERENCES encrypted_blob_manifests(workspace_id, blob_id)
);

-- The D1 claim is the irreversible GC authorization point. Recheck the recovery boundary,
-- safety window and every currently active device in the same transaction before any R2 delete.
CREATE TRIGGER blob_gc_claims_guard
BEFORE INSERT ON blob_gc_claims
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM blob_tombstones tombstone
    JOIN encrypted_blob_manifests manifest
      ON manifest.workspace_id = tombstone.workspace_id AND manifest.blob_id = tombstone.blob_id
    JOIN workspaces workspace ON workspace.workspace_id = tombstone.workspace_id
    JOIN workspace_capabilities capability
      ON capability.capability_id = NEW.capability_id
     AND capability.workspace_id = tombstone.workspace_id
     AND capability.device_id = NEW.claimed_by_device_id
    JOIN devices claimant_device ON claimant_device.device_id = capability.device_id
    JOIN users claimant_user ON claimant_user.user_id = claimant_device.user_id
    JOIN memberships claimant_membership
      ON claimant_membership.workspace_id = tombstone.workspace_id
     AND claimant_membership.user_id = claimant_device.user_id
    WHERE tombstone.tombstone_id = NEW.tombstone_id
      AND tombstone.workspace_id = NEW.workspace_id AND tombstone.blob_id = NEW.blob_id
      AND tombstone.status = 'waiting' AND tombstone.execute_after <= NEW.claimed_at
      AND manifest.status = 'tombstoned'
      AND manifest.manifest_id = tombstone.manifest_id
      AND manifest.manifest_ciphertext_sha256 = tombstone.manifest_ciphertext_sha256
      AND workspace.status = 'active' AND workspace.rotation_required = 0
      AND manifest.current_envelope_epoch = workspace.active_key_epoch
      AND claimant_device.status = 'active'
      AND claimant_device.auth_epoch = NEW.device_auth_epoch
      AND claimant_user.status = 'active'
      AND claimant_membership.status = 'active'
      AND claimant_membership.role IN ('owner', 'editor')
      AND claimant_membership.role = NEW.authorized_role
      AND claimant_membership.auth_epoch = NEW.membership_auth_epoch
      AND capability.revoked_at IS NULL AND capability.expires_at > NEW.claimed_at
      AND capability.device_auth_epoch = NEW.device_auth_epoch
      AND capability.device_auth_epoch = claimant_device.auth_epoch
      AND capability.membership_auth_epoch = NEW.membership_auth_epoch
      AND capability.membership_auth_epoch = claimant_membership.auth_epoch
      AND capability.key_epoch = NEW.key_epoch
      AND capability.key_epoch = workspace.active_key_epoch
      AND workspace.gc_before_seq >= tombstone.reference_through_seq
      AND NOT EXISTS (
        SELECT 1
        FROM devices active_device
        JOIN users active_user ON active_user.user_id = active_device.user_id
        JOIN memberships active_membership ON active_membership.user_id = active_device.user_id
        WHERE active_membership.workspace_id = workspace.workspace_id
          AND active_membership.status = 'active'
          AND active_user.status = 'active' AND active_device.status = 'active'
          AND NOT EXISTS (
            SELECT 1
            FROM blob_tombstone_acks ack
            JOIN checkpoints checkpoint
              ON checkpoint.workspace_id = ack.workspace_id
             AND checkpoint.checkpoint_id = ack.evidence_checkpoint_id
             AND checkpoint.ciphertext_sha256 = ack.evidence_checkpoint_sha256
             AND checkpoint.through_seq = ack.through_seq
            WHERE ack.tombstone_id = tombstone.tombstone_id
              AND ack.validator_device_id = active_device.device_id
              AND ack.workspace_id = tombstone.workspace_id
              AND ack.blob_id = tombstone.blob_id
              AND ack.through_seq >= tombstone.reference_through_seq
              AND checkpoint.status = 'stable' AND checkpoint.schema_version = 2
              AND checkpoint.key_epoch = workspace.active_key_epoch
          )
      )
  ) THEN RAISE(ABORT, 'blob_gc_claim_invalid') END;
END;

CREATE TRIGGER blob_gc_claims_apply
AFTER INSERT ON blob_gc_claims
BEGIN
  UPDATE blob_tombstones SET status = 'deleting'
  WHERE tombstone_id = NEW.tombstone_id AND status = 'waiting';
  SELECT CASE WHEN changes() <> 1 THEN RAISE(ABORT, 'blob_gc_claim_invalid') END;

  UPDATE encrypted_blob_manifests SET status = 'deleting'
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND status = 'tombstoned';
  SELECT CASE WHEN changes() <> 1 THEN RAISE(ABORT, 'blob_gc_claim_invalid') END;
END;

CREATE TRIGGER blob_gc_receipts_guard
BEFORE INSERT ON blob_gc_receipts
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM blob_gc_claims claim
    JOIN blob_tombstones tombstone ON tombstone.tombstone_id = claim.tombstone_id
    JOIN encrypted_blob_manifests manifest
      ON manifest.workspace_id = claim.workspace_id AND manifest.blob_id = claim.blob_id
    WHERE claim.claim_id = NEW.claim_id AND claim.tombstone_id = NEW.tombstone_id
      AND claim.workspace_id = NEW.workspace_id AND claim.blob_id = NEW.blob_id
      AND claim.status = 'deleting' AND tombstone.status = 'deleting'
      AND manifest.status = 'deleting'
      AND manifest.manifest_ciphertext_sha256 = NEW.manifest_ciphertext_sha256
      AND claim.object_list_sha256 = NEW.object_list_sha256
      AND claim.object_count = NEW.deleted_object_count
      AND manifest.total_chunk_bytes + manifest.manifest_byte_size = NEW.deleted_ciphertext_bytes
      AND NEW.verified_remaining_count = 0
  ) THEN RAISE(ABORT, 'blob_gc_completion_invalid') END;
END;

CREATE TRIGGER blob_gc_receipts_apply
AFTER INSERT ON blob_gc_receipts
BEGIN
  UPDATE blob_gc_claims SET status = 'complete', completed_at = NEW.completed_at
  WHERE claim_id = NEW.claim_id AND status = 'deleting';

  UPDATE blob_tombstones SET status = 'deleted', deleted_at = NEW.completed_at
  WHERE tombstone_id = NEW.tombstone_id AND status = 'deleting';

  UPDATE encrypted_blob_manifests SET status = 'deleted', deleted_at = NEW.completed_at
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id
    AND status = 'deleting';

  -- Keep the GC receipt as the idempotency authority, but remove every encrypted DEK copy
  -- together with the body. The initial and retained tables contain the same logical envelope
  -- at commit time and must not outlive successful body GC.
  DELETE FROM blob_dek_envelopes
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id;

  DELETE FROM blob_upload_initial_envelopes
  WHERE workspace_id = NEW.workspace_id AND blob_id = NEW.blob_id;

  UPDATE workspaces
  SET committed_bytes = MAX(0, committed_bytes - NEW.deleted_ciphertext_bytes)
  WHERE workspace_id = NEW.workspace_id;

  INSERT INTO usage_daily(
    workspace_id, day, blobs_deleted, blob_bytes_deleted
  )
  VALUES (
    NEW.workspace_id,
    strftime('%Y-%m-%d', NEW.completed_at / 1000, 'unixepoch'),
    1,
    NEW.deleted_ciphertext_bytes
  )
  ON CONFLICT(workspace_id, day) DO UPDATE SET
    blobs_deleted = blobs_deleted + 1,
    blob_bytes_deleted = blob_bytes_deleted + excluded.blob_bytes_deleted;
END;

-- A second workspace rotation cannot overtake the previous epoch's low-priority body re-wraps,
-- including tombstoned bodies whose safety/ack window has not reached an irreversible GC claim.
-- Without this atomic guard a response-lost epoch-N envelope could become unreadable as soon as
-- epoch N+1 is committed. The first rotation remains valid because every initial body envelope
-- is necessarily at rotation.from_epoch.
CREATE TRIGGER blob_rotation_commits_envelopes_guard
BEFORE INSERT ON rotation_commits
BEGIN
  SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM key_rotations rotation
    JOIN encrypted_blob_manifests manifest
      ON manifest.workspace_id = rotation.workspace_id
    WHERE rotation.rotation_id = NEW.rotation_id
      AND manifest.status IN ('committed', 'tombstoned')
      AND manifest.current_envelope_epoch <> rotation.from_epoch
  ) THEN RAISE(ABORT, 'blob_rotation_envelopes_incomplete') END;
END;

-- Emergency reset already purges the exact workspace R2 prefix. Clear the matching v2 metadata
-- in the same reset transaction when the directory generation advances.
CREATE TRIGGER blob_body_plane_emergency_reset_cleanup
AFTER UPDATE OF directory_generation ON workspaces
WHEN NEW.directory_generation > OLD.directory_generation AND NEW.status = 'reset'
BEGIN
  DELETE FROM blob_gc_receipts WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_gc_claims WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_tombstone_revivals WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_tombstone_acks WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_tombstones WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_manifest_commits WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_dek_envelopes WHERE workspace_id = NEW.workspace_id;
  DELETE FROM encrypted_blob_manifests WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_chunk_receipts WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_upload_session_activations
  WHERE session_id IN (
    SELECT session_id FROM blob_upload_sessions WHERE workspace_id = NEW.workspace_id
  );
  DELETE FROM blob_upload_initial_envelopes WHERE workspace_id = NEW.workspace_id;
  DELETE FROM blob_upload_session_chunks WHERE workspace_id = NEW.workspace_id;
  DELETE FROM workspace_usage_reservations
  WHERE reservation_id IN (
    SELECT 'blob:' || session_id FROM blob_upload_sessions WHERE workspace_id = NEW.workspace_id
  );
  DELETE FROM blob_upload_sessions WHERE workspace_id = NEW.workspace_id;
END;

INSERT INTO schema_migrations(version, name, applied_at)
VALUES (5, 'encrypted_blob_body_plane_v2', 0);
