PRAGMA foreign_keys = ON;

-- A reset starts a new device-directory lineage without deleting immutable historical
-- attestations. Existing rows remain available to the operator/audit trail, but only devices
-- enrolled in the workspace's current generation are exposed to sync clients.
ALTER TABLE devices
  ADD COLUMN directory_generation INTEGER NOT NULL DEFAULT 1
  CHECK (directory_generation > 0);

ALTER TABLE workspaces
  ADD COLUMN directory_generation INTEGER NOT NULL DEFAULT 1
  CHECK (directory_generation > 0);

CREATE TRIGGER devices_assign_directory_generation
AFTER INSERT ON devices
WHEN EXISTS (
  SELECT 1 FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
  WHERE m.user_id = NEW.user_id AND m.status = 'active'
)
BEGIN
  UPDATE devices
  SET directory_generation = (
    SELECT MAX(w.directory_generation)
    FROM memberships m JOIN workspaces w ON w.workspace_id = m.workspace_id
    WHERE m.user_id = NEW.user_id AND m.status = 'active'
  )
  WHERE device_id = NEW.device_id;
END;

CREATE TABLE emergency_workspace_resets (
  reset_id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  owner_user_id TEXT NOT NULL REFERENCES users(user_id),
  confirmation TEXT NOT NULL,
  r2_prefix TEXT NOT NULL,
  handoff_secret_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (
    status IN ('requested', 'purge_pending', 'handoff_ready', 'complete')
  ),
  previous_key_epoch INTEGER NOT NULL CHECK (previous_key_epoch > 0),
  previous_directory_generation INTEGER NOT NULL CHECK (previous_directory_generation > 0),
  previous_head_seq INTEGER NOT NULL CHECK (previous_head_seq >= 0),
  previous_committed_bytes INTEGER NOT NULL CHECK (previous_committed_bytes >= 0),
  handoff_expires_at INTEGER NOT NULL,
  requested_at INTEGER NOT NULL,
  r2_purged_at INTEGER,
  handoff_consumed_at INTEGER,
  CHECK (confirmation = 'RESET EMPTY WORKSPACE ' || workspace_id),
  CHECK (r2_prefix = 'workspaces/' || workspace_id || '/'),
  CHECK (handoff_expires_at > requested_at)
);

CREATE UNIQUE INDEX emergency_workspace_one_active_reset
  ON emergency_workspace_resets(workspace_id)
  WHERE status IN ('requested', 'purge_pending', 'handoff_ready');

CREATE TABLE emergency_workspace_reset_purges (
  reset_id TEXT PRIMARY KEY REFERENCES emergency_workspace_resets(reset_id),
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  r2_prefix TEXT NOT NULL,
  deleted_object_count INTEGER NOT NULL CHECK (deleted_object_count >= 0),
  verified_remaining_count INTEGER NOT NULL CHECK (verified_remaining_count = 0),
  purged_at INTEGER NOT NULL
);

CREATE TABLE emergency_workspace_handoffs (
  reset_id TEXT PRIMARY KEY REFERENCES emergency_workspace_resets(reset_id),
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  owner_user_id TEXT NOT NULL REFERENCES users(user_id),
  device_id TEXT NOT NULL UNIQUE REFERENCES devices(device_id),
  handoff_secret_hash TEXT NOT NULL,
  key_commitment TEXT NOT NULL,
  consumed_at INTEGER NOT NULL
);

CREATE TRIGGER emergency_workspace_resets_guard
BEFORE INSERT ON emergency_workspace_resets
BEGIN
  SELECT CASE WHEN NEW.status <> 'requested'
    THEN RAISE(ABORT, 'emergency_reset_invalid_status') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM workspaces w
    JOIN users owner ON owner.user_id = w.owner_user_id
    JOIN memberships membership
      ON membership.workspace_id = w.workspace_id AND membership.user_id = owner.user_id
    WHERE w.workspace_id = NEW.workspace_id
      AND w.owner_user_id = NEW.owner_user_id
      AND w.status IN ('active', 'suspended')
      AND owner.status = 'active'
      AND membership.status = 'active' AND membership.role = 'owner'
      AND w.active_key_epoch = NEW.previous_key_epoch
      AND w.directory_generation = NEW.previous_directory_generation
      AND w.head_seq = NEW.previous_head_seq
      AND w.committed_bytes = NEW.previous_committed_bytes
  ) THEN RAISE(ABORT, 'emergency_reset_workspace_changed') END;

  -- The supported private-workspace handoff has one active owner membership. Refusing shared
  -- identity scope prevents a workspace reset from revoking credentials used by another tenant.
  SELECT CASE WHEN (
    SELECT COUNT(*) FROM memberships
    WHERE workspace_id = NEW.workspace_id AND status = 'active'
  ) <> 1 THEN RAISE(ABORT, 'emergency_reset_shared_workspace_unsupported') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM memberships
    WHERE user_id = NEW.owner_user_id AND workspace_id <> NEW.workspace_id AND status = 'active'
  ) THEN RAISE(ABORT, 'emergency_reset_cross_workspace_identity') END;

  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM emergency_workspace_resets existing
    WHERE existing.workspace_id = NEW.workspace_id
      AND existing.status IN ('requested', 'purge_pending', 'handoff_ready')
  ) THEN RAISE(ABORT, 'emergency_reset_already_active') END;
END;

CREATE TRIGGER emergency_workspace_resets_apply
AFTER INSERT ON emergency_workspace_resets
BEGIN
  -- Closing the workspace is the first logical transition. Every statement in this trigger is
  -- part of the INSERT transaction, so clients can never observe a half-cleared active workspace.
  UPDATE workspaces
  SET status = 'reset', pending_rotation_id = NULL, rotation_required = 0
  WHERE workspace_id = NEW.workspace_id;

  UPDATE devices
  SET status = 'revoked', auth_epoch = auth_epoch + 1, revoked_at = NEW.requested_at
  WHERE user_id = NEW.owner_user_id AND status IN ('pending', 'active');

  UPDATE access_tokens
  SET revoked_at = COALESCE(revoked_at, NEW.requested_at)
  WHERE device_id IN (SELECT device_id FROM devices WHERE user_id = NEW.owner_user_id);

  UPDATE workspace_capabilities
  SET revoked_at = COALESCE(revoked_at, NEW.requested_at)
  WHERE workspace_id = NEW.workspace_id;

  UPDATE auth_challenges
  SET consumed_at = COALESCE(consumed_at, NEW.requested_at)
  WHERE device_id IN (SELECT device_id FROM devices WHERE user_id = NEW.owner_user_id);

  DELETE FROM control_request_nonces
  WHERE device_id IN (SELECT device_id FROM devices WHERE user_id = NEW.owner_user_id);

  UPDATE invites SET status = 'revoked'
  WHERE status = 'active' AND created_by_device_id IN (
    SELECT device_id FROM devices WHERE user_id = NEW.owner_user_id
  );

  DELETE FROM pairing_activations WHERE pairing_id IN (
    SELECT pairing_id FROM pairing_sessions WHERE workspace_id = NEW.workspace_id
  );
  DELETE FROM pairing_sessions WHERE workspace_id = NEW.workspace_id;

  DELETE FROM key_rotation_acks WHERE rotation_id IN (
    SELECT rotation_id FROM key_rotations WHERE workspace_id = NEW.workspace_id
  );
  DELETE FROM rotation_commits WHERE rotation_id IN (
    SELECT rotation_id FROM key_rotations WHERE workspace_id = NEW.workspace_id
  );
  DELETE FROM key_rotation_recipients WHERE rotation_id IN (
    SELECT rotation_id FROM key_rotations WHERE workspace_id = NEW.workspace_id
  );
  DELETE FROM workspace_device_keys WHERE workspace_id = NEW.workspace_id;
  DELETE FROM workspace_recovery_keys WHERE workspace_id = NEW.workspace_id;
  DELETE FROM key_rotations WHERE workspace_id = NEW.workspace_id;

  DELETE FROM checkpoint_promotions WHERE workspace_id = NEW.workspace_id;
  DELETE FROM checkpoint_acks WHERE workspace_id = NEW.workspace_id;
  DELETE FROM workspace_gc_state WHERE workspace_id = NEW.workspace_id;
  DELETE FROM event_gc_runs WHERE workspace_id = NEW.workspace_id;
  DELETE FROM checkpoints WHERE workspace_id = NEW.workspace_id;
  DELETE FROM workspace_usage_reservations WHERE workspace_id = NEW.workspace_id;
  DELETE FROM checkpoint_leases WHERE workspace_id = NEW.workspace_id;

  DELETE FROM events WHERE workspace_id = NEW.workspace_id;
  DELETE FROM event_receipts WHERE workspace_id = NEW.workspace_id;
  DELETE FROM device_stream_state WHERE workspace_id = NEW.workspace_id;
  DELETE FROM event_rate_windows WHERE scope_key = 'workspace:' || NEW.workspace_id;
  DELETE FROM usage_daily WHERE workspace_id = NEW.workspace_id;
  DELETE FROM recovery_claim_key_envelopes WHERE workspace_id = NEW.workspace_id;

  DELETE FROM recovery_credentials WHERE user_id = NEW.owner_user_id;
  UPDATE recovery_challenges
  SET consumed_at = COALESCE(consumed_at, NEW.requested_at)
  WHERE user_id = NEW.owner_user_id;

  UPDATE memberships
  SET auth_epoch = auth_epoch + 1
  WHERE workspace_id = NEW.workspace_id AND user_id = NEW.owner_user_id;

  UPDATE workspaces
  SET active_key_epoch = 1,
      directory_generation = directory_generation + 1,
      device_directory_epoch = device_directory_epoch + 1,
      head_seq = 0,
      gc_before_seq = 0,
      stable_checkpoint_id = NULL,
      candidate_checkpoint_id = NULL,
      committed_bytes = 0,
      reserved_bytes = 0
  WHERE workspace_id = NEW.workspace_id;

  UPDATE emergency_workspace_resets SET status = 'purge_pending'
  WHERE reset_id = NEW.reset_id AND status = 'requested';

  INSERT INTO audit_events(audit_id, actor_device_id, action, target_id, created_at)
  VALUES ('emergency-reset-request:' || NEW.reset_id, NULL,
          'emergency_workspace_reset_requested', NEW.workspace_id, NEW.requested_at);
END;

CREATE TRIGGER emergency_workspace_reset_purges_guard
BEFORE INSERT ON emergency_workspace_reset_purges
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM emergency_workspace_resets reset
    JOIN workspaces workspace ON workspace.workspace_id = reset.workspace_id
    WHERE reset.reset_id = NEW.reset_id
      AND reset.workspace_id = NEW.workspace_id
      AND reset.r2_prefix = NEW.r2_prefix
      AND reset.status = 'purge_pending'
      AND workspace.status = 'reset'
      AND NEW.verified_remaining_count = 0
  ) THEN RAISE(ABORT, 'emergency_reset_purge_invalid') END;
END;

CREATE TRIGGER emergency_workspace_reset_purges_apply
AFTER INSERT ON emergency_workspace_reset_purges
BEGIN
  UPDATE emergency_workspace_resets
  SET status = 'handoff_ready', r2_purged_at = NEW.purged_at
  WHERE reset_id = NEW.reset_id AND status = 'purge_pending';

  INSERT INTO audit_events(audit_id, actor_device_id, action, target_id, created_at)
  VALUES ('emergency-reset-purge:' || NEW.reset_id, NULL,
          'emergency_workspace_r2_purge_verified', NEW.workspace_id, NEW.purged_at);
END;

CREATE TRIGGER emergency_workspace_handoffs_guard
BEFORE INSERT ON emergency_workspace_handoffs
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM emergency_workspace_resets reset
    JOIN workspaces workspace ON workspace.workspace_id = reset.workspace_id
    JOIN users owner ON owner.user_id = reset.owner_user_id
    JOIN memberships membership
      ON membership.workspace_id = reset.workspace_id AND membership.user_id = reset.owner_user_id
    WHERE reset.reset_id = NEW.reset_id
      AND reset.workspace_id = NEW.workspace_id
      AND reset.owner_user_id = NEW.owner_user_id
      AND reset.handoff_secret_hash = NEW.handoff_secret_hash
      AND reset.status = 'handoff_ready'
      AND reset.handoff_expires_at > NEW.consumed_at
      AND workspace.status = 'reset'
      AND workspace.active_key_epoch = 1
      AND workspace.head_seq = 0 AND workspace.gc_before_seq = 0
      AND workspace.committed_bytes = 0 AND workspace.reserved_bytes = 0
      AND owner.status = 'active'
      AND membership.status = 'active' AND membership.role = 'owner'
  ) THEN RAISE(ABORT, 'emergency_handoff_invalid') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM devices device
    WHERE device.device_id = NEW.device_id
      AND device.user_id = NEW.owner_user_id
      AND device.status = 'active'
      AND device.directory_generation = (
        SELECT directory_generation FROM workspaces WHERE workspace_id = NEW.workspace_id
      )
  ) THEN RAISE(ABORT, 'emergency_handoff_device_invalid') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM device_key_attestations attestation
    WHERE attestation.device_id = NEW.device_id
      AND attestation.workspace_id = NEW.workspace_id
      AND attestation.attestation_type = 'initial'
      AND attestation.signature_domain = 'initial-workspace-claim'
  ) THEN RAISE(ABORT, 'emergency_handoff_attestation_missing') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM workspace_device_keys device_key
    WHERE device_key.workspace_id = NEW.workspace_id
      AND device_key.device_id = NEW.device_id
      AND device_key.key_epoch = 1
      AND device_key.rotation_id = 'emergency:' || NEW.reset_id
      AND device_key.key_commitment = NEW.key_commitment
  ) THEN RAISE(ABORT, 'emergency_handoff_key_missing') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM workspace_recovery_keys recovery_key
    WHERE recovery_key.workspace_id = NEW.workspace_id
      AND recovery_key.key_epoch = 1
      AND recovery_key.rotation_id = 'emergency:' || NEW.reset_id
      AND recovery_key.key_commitment = NEW.key_commitment
  ) THEN RAISE(ABORT, 'emergency_handoff_recovery_key_missing') END;

  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM recovery_credentials credential
    WHERE credential.user_id = NEW.owner_user_id AND credential.auth_epoch = 1
  ) THEN RAISE(ABORT, 'emergency_handoff_recovery_credential_missing') END;
END;

CREATE TRIGGER emergency_workspace_handoffs_apply
AFTER INSERT ON emergency_workspace_handoffs
BEGIN
  UPDATE workspaces
  SET status = 'active', rotation_required = 0, pending_rotation_id = NULL,
      device_directory_epoch = device_directory_epoch + 1
  WHERE workspace_id = NEW.workspace_id AND status = 'reset';

  UPDATE emergency_workspace_resets
  SET status = 'complete', handoff_consumed_at = NEW.consumed_at
  WHERE reset_id = NEW.reset_id AND status = 'handoff_ready';

  INSERT INTO audit_events(audit_id, actor_device_id, action, target_id, created_at)
  VALUES ('emergency-reset-handoff:' || NEW.reset_id, NEW.device_id,
          'emergency_workspace_handoff_completed', NEW.workspace_id, NEW.consumed_at);
END;

INSERT INTO schema_migrations(version, name, applied_at)
VALUES (4, 'emergency_workspace_reset', 0);
