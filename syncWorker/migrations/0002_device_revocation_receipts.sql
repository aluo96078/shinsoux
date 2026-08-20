PRAGMA foreign_keys = ON;

CREATE TABLE device_revocation_workspaces (
  revocation_id TEXT NOT NULL REFERENCES device_revocations(revocation_id),
  workspace_id TEXT NOT NULL REFERENCES workspaces(workspace_id),
  revoked_at_key_epoch INTEGER NOT NULL CHECK (revoked_at_key_epoch > 0),
  directory_epoch_after_revocation INTEGER NOT NULL CHECK (directory_epoch_after_revocation > 0),
  PRIMARY KEY(revocation_id, workspace_id)
);

CREATE INDEX device_revocation_workspaces_workspace_idx
  ON device_revocation_workspaces(workspace_id, revocation_id);

-- Recreate the apply trigger so the immutable workspace receipt bindings are captured by the
-- same statement that revokes credentials and marks every workspace for rotation.
DROP TRIGGER device_revocations_apply;

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

  INSERT INTO device_revocation_workspaces(
    revocation_id, workspace_id, revoked_at_key_epoch, directory_epoch_after_revocation
  )
  SELECT NEW.revocation_id, w.workspace_id, w.active_key_epoch, w.device_directory_epoch
  FROM memberships m
  JOIN devices d ON d.user_id = m.user_id
  JOIN workspaces w ON w.workspace_id = m.workspace_id
  WHERE d.device_id = NEW.target_device_id AND m.status = 'active';
END;

INSERT INTO schema_migrations(version, name, applied_at)
VALUES (2, 'durable_device_revocation_receipts', 0);
