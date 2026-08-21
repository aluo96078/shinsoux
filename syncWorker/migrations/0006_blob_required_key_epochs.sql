-- Pairing and Recovery claims must preserve every workspace key that can still unwrap an
-- encrypted body. Migration 0001 predates the blob body plane, so replace both authoritative
-- fail-closed guards.
DROP TRIGGER pairing_activations_guard;

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
      UNION
      SELECT manifest.current_envelope_epoch AS required_epoch
      FROM encrypted_blob_manifests manifest
      JOIN pairing_sessions p ON p.workspace_id = manifest.workspace_id
      WHERE p.pairing_id = NEW.pairing_id
        AND manifest.status IN ('committed', 'tombstoned', 'deleting')
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

DROP TRIGGER recovery_claims_guard;

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
      UNION
      SELECT manifest.workspace_id, manifest.current_envelope_epoch AS key_epoch
      FROM encrypted_blob_manifests manifest
      JOIN memberships m ON m.workspace_id = manifest.workspace_id
      JOIN workspaces w ON w.workspace_id = manifest.workspace_id
      WHERE m.user_id = NEW.user_id AND m.status = 'active' AND w.status = 'active'
        AND manifest.status IN ('committed', 'tombstoned', 'deleting')
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
                    AND e.key_epoch = staged.key_epoch) OR
            EXISTS (
              SELECT 1 FROM encrypted_blob_manifests manifest
              WHERE manifest.workspace_id = w.workspace_id
                AND manifest.current_envelope_epoch = staged.key_epoch
                AND manifest.status IN ('committed', 'tombstoned', 'deleting')
            )
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
