export const APPEND_EVENT_SQL = `
INSERT INTO events(
  workspace_id, workspace_seq, event_id, device_id, device_seq, key_epoch,
  authenticated_header_cbor, ciphertext, ciphertext_sha256, signature, byte_size, created_at
)
SELECT
  w.workspace_id, w.head_seq + 1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11
FROM workspaces w
JOIN workspace_capabilities c
  ON c.capability_id = ?12 AND c.workspace_id = w.workspace_id AND c.device_id = ?3
JOIN devices d ON d.device_id = c.device_id
JOIN users u ON u.user_id = d.user_id
JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
LEFT JOIN device_stream_state s
  ON s.workspace_id = w.workspace_id AND s.device_id = d.device_id
WHERE w.workspace_id = ?1
  AND w.status = 'active' AND w.rotation_required = 0
  AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
  AND c.revoked_at IS NULL AND c.expires_at > ?11
  AND c.device_auth_epoch = d.auth_epoch
  AND c.membership_auth_epoch = m.auth_epoch
  AND c.key_epoch = w.active_key_epoch AND ?5 = w.active_key_epoch
  AND ?4 = COALESCE(s.committed_device_seq, 0) + 1
  AND ?10 <= q.max_event_bytes
  AND w.committed_bytes + w.reserved_bytes + ?10 <= q.max_workspace_bytes
RETURNING workspace_seq AS workspaceSeq
`;

export const CREATE_CHECKPOINT_LEASE_SQL = `
INSERT INTO checkpoint_leases(
  lease_id, workspace_id, checkpoint_id, ciphertext_sha256, through_seq, key_epoch,
  uploader_device_id, capability_id, reserved_bytes, status, expires_at, created_at
)
SELECT ?1, w.workspace_id, ?3, ?4, w.head_seq, w.active_key_epoch,
       c.device_id, c.capability_id, ?5, 'active', ?6, ?7
FROM workspaces w
JOIN workspace_capabilities c ON c.capability_id = ?8 AND c.workspace_id = w.workspace_id
JOIN devices d ON d.device_id = c.device_id
JOIN users u ON u.user_id = d.user_id
JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
WHERE w.workspace_id = ?2 AND w.status = 'active' AND w.rotation_required = 0
  AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
  AND c.revoked_at IS NULL AND c.expires_at > ?7
  AND c.device_auth_epoch = d.auth_epoch AND c.membership_auth_epoch = m.auth_epoch
  AND c.key_epoch = w.active_key_epoch
  AND w.candidate_checkpoint_id IS NULL
  AND w.head_seq = ?9
  AND ?5 <= q.max_checkpoint_bytes
  AND w.committed_bytes + w.reserved_bytes + ?5 <= q.max_workspace_bytes
  AND NOT EXISTS (
    SELECT 1 FROM checkpoint_leases existing
    WHERE existing.workspace_id = w.workspace_id AND existing.status IN ('active', 'uploaded')
  )
RETURNING lease_id, through_seq, key_epoch, expires_at
`;

export const COMMIT_CHECKPOINT_SQL = `
INSERT INTO checkpoints(
  workspace_id, through_seq, checkpoint_id, key_epoch, authenticated_header_cbor,
  envelope_version, schema_version, cipher_suite, nonce, previous_stable_sha256,
  r2_key, ciphertext_sha256, byte_size, uploader_device_id, device_signature, status, created_at
)
SELECT l.workspace_id, l.through_seq, l.checkpoint_id, l.key_epoch, ?4,
       ?5, ?6, ?7, ?8, ?9, l.r2_key, l.ciphertext_sha256,
       l.actual_byte_size, l.uploader_device_id, ?10, 'candidate', ?11
FROM checkpoint_leases l
JOIN workspaces w ON w.workspace_id = l.workspace_id
JOIN workspace_capabilities c ON c.capability_id = ?12
JOIN devices d ON d.device_id = c.device_id
JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
WHERE l.workspace_id = ?1 AND l.checkpoint_id = ?2 AND l.ciphertext_sha256 = ?3
  AND l.status = 'uploaded' AND l.expires_at > ?11
  AND l.uploader_device_id = c.device_id
  AND l.r2_key IS NOT NULL AND l.actual_byte_size IS NOT NULL
  AND w.candidate_checkpoint_id IS NULL AND w.rotation_required = 0
  AND w.active_key_epoch = l.key_epoch AND c.key_epoch = w.active_key_epoch
  AND c.revoked_at IS NULL AND c.expires_at > ?11
  AND d.status = 'active' AND c.device_auth_epoch = d.auth_epoch
  AND m.status = 'active' AND c.membership_auth_epoch = m.auth_epoch
RETURNING checkpoint_id, through_seq, ciphertext_sha256, status
`;

export const MARK_CHECKPOINT_UPLOADED_SQL = `
UPDATE checkpoint_leases AS lease
SET status = 'uploaded', actual_byte_size = ?4, r2_key = ?5
WHERE workspace_id = ?1 AND checkpoint_id = ?2 AND ciphertext_sha256 = ?3
  AND status = 'active' AND expires_at > ?6 AND uploader_device_id = ?7
  AND EXISTS (
    SELECT 1 FROM workspaces workspace
    WHERE workspace.workspace_id = lease.workspace_id
      AND workspace.status = 'active' AND workspace.rotation_required = 0
  )
RETURNING lease_id AS leaseId
`;

export const CANCEL_ROTATION_BLOCKED_CHECKPOINT_LEASE_SQL = `
UPDATE checkpoint_leases AS lease
SET status = 'cancelled'
WHERE lease_id = ?1 AND status IN ('active', 'uploaded')
  AND EXISTS (
    SELECT 1 FROM workspaces workspace
    WHERE workspace.workspace_id = lease.workspace_id
      AND workspace.rotation_required = 1
  )
RETURNING r2_key AS r2Key
`;

export const CREATE_ROTATION_LEASE_SQL = `
INSERT INTO key_rotations(
  rotation_id, workspace_id, from_epoch, to_epoch, proposer_device_id,
  proposer_device_auth_epoch, membership_auth_epoch, status, lease_expires_at, created_at
)
SELECT ?1, w.workspace_id, w.active_key_epoch, w.active_key_epoch + 1, d.device_id,
       d.auth_epoch, m.auth_epoch, 'leased', ?3, ?4
FROM workspaces w
JOIN devices d ON d.device_id = ?5
JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
WHERE w.workspace_id = ?2 AND w.status = 'active' AND w.rotation_required = 1
  AND d.status = 'active' AND m.status = 'active'
  AND w.pending_rotation_id IS NULL
RETURNING rotation_id, from_epoch, to_epoch, lease_expires_at
`;

export const CREATE_CAPABILITY_SQL = `
INSERT INTO workspace_capabilities(
  capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
  membership_auth_epoch, key_epoch, expires_at, created_at
)
SELECT ?1, w.workspace_id, d.device_id, ?4, d.auth_epoch, m.auth_epoch,
       w.active_key_epoch, ?5, ?6
FROM workspaces w
JOIN memberships m ON m.workspace_id = w.workspace_id
JOIN devices d ON d.user_id = m.user_id
JOIN users u ON u.user_id = d.user_id
JOIN access_tokens a ON a.access_token_id = ?7 AND a.device_id = d.device_id
WHERE w.workspace_id = ?2 AND d.device_id = ?3
  AND w.status = 'active' AND m.status = 'active' AND d.status = 'active' AND u.status = 'active'
  AND a.revoked_at IS NULL AND a.expires_at > ?6 AND a.device_auth_epoch = d.auth_epoch
RETURNING capability_id, workspace_id, device_id, device_auth_epoch,
          membership_auth_epoch, key_epoch, expires_at
`;

export const PROMOTE_CHECKPOINT_SQL = `
INSERT INTO checkpoint_promotions(workspace_id, checkpoint_id, ciphertext_sha256, promoted_at)
SELECT c.workspace_id, c.checkpoint_id, c.ciphertext_sha256, ?4
FROM checkpoints c
JOIN workspaces w ON w.workspace_id = c.workspace_id
JOIN workspace_capabilities capability
  ON capability.capability_id = ?5 AND capability.workspace_id = c.workspace_id
JOIN devices d ON d.device_id = capability.device_id AND d.device_id = ?6
JOIN users u ON u.user_id = d.user_id
JOIN memberships m ON m.workspace_id = c.workspace_id AND m.user_id = d.user_id
WHERE c.workspace_id = ?1 AND c.checkpoint_id = ?2 AND c.ciphertext_sha256 = ?3
  AND c.status = 'candidate'
  AND w.candidate_checkpoint_id = c.checkpoint_id
  AND w.status = 'active' AND w.rotation_required = 0
  AND c.key_epoch = w.active_key_epoch
  AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
  AND capability.revoked_at IS NULL AND capability.expires_at > ?4
  AND capability.device_auth_epoch = d.auth_epoch
  AND capability.membership_auth_epoch = m.auth_epoch
  AND capability.key_epoch = w.active_key_epoch
  AND EXISTS (
    SELECT 1 FROM checkpoint_acks a
    WHERE a.workspace_id = c.workspace_id AND a.checkpoint_id = c.checkpoint_id
      AND a.ciphertext_sha256 = c.ciphertext_sha256
      AND a.validator_device_id = capability.device_id
  )
  AND (
    ((SELECT COUNT(*) FROM devices active_device
      JOIN users active_user ON active_user.user_id = active_device.user_id
      JOIN memberships active_membership ON active_membership.user_id = active_device.user_id
      WHERE active_membership.workspace_id = c.workspace_id
        AND active_membership.status = 'active' AND active_device.status = 'active'
        AND active_user.status = 'active') > 1
      AND capability.device_id <> c.uploader_device_id)
    OR
    ((SELECT COUNT(*) FROM devices active_device
      JOIN users active_user ON active_user.user_id = active_device.user_id
      JOIN memberships active_membership ON active_membership.user_id = active_device.user_id
      WHERE active_membership.workspace_id = c.workspace_id
        AND active_membership.status = 'active' AND active_device.status = 'active'
        AND active_user.status = 'active') = 1
      AND ?4 >= c.created_at + ?7)
  )
`;

export const INSERT_CHECKPOINT_ACK_SQL = `
INSERT INTO checkpoint_acks(
  workspace_id, checkpoint_id, ciphertext_sha256, validator_device_id,
  validation_version, replay_from_seq, replayed_through_seq, replayed_event_count,
  previous_stable_checkpoint_id, previous_stable_sha256, signature, created_at
)
SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12
FROM checkpoints candidate
JOIN workspaces w ON w.workspace_id = candidate.workspace_id
JOIN workspace_capabilities capability
  ON capability.capability_id = ?13 AND capability.workspace_id = candidate.workspace_id
JOIN devices d ON d.device_id = capability.device_id AND d.device_id = ?4
JOIN users u ON u.user_id = d.user_id
JOIN memberships m ON m.workspace_id = candidate.workspace_id AND m.user_id = d.user_id
WHERE candidate.workspace_id = ?1 AND candidate.checkpoint_id = ?2
  AND candidate.ciphertext_sha256 = ?3 AND candidate.status = 'candidate'
  AND w.candidate_checkpoint_id = candidate.checkpoint_id
  AND w.status = 'active' AND w.rotation_required = 0
  AND candidate.key_epoch = w.active_key_epoch
  AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
  AND capability.revoked_at IS NULL AND capability.expires_at > ?12
  AND capability.device_auth_epoch = d.auth_epoch
  AND capability.membership_auth_epoch = m.auth_epoch
  AND capability.key_epoch = w.active_key_epoch
  AND (
    ((SELECT COUNT(*) FROM devices active_device
      JOIN users active_user ON active_user.user_id = active_device.user_id
      JOIN memberships active_membership ON active_membership.user_id = active_device.user_id
      WHERE active_membership.workspace_id = candidate.workspace_id
        AND active_membership.status = 'active' AND active_device.status = 'active'
        AND active_user.status = 'active') > 1
      AND capability.device_id <> candidate.uploader_device_id)
    OR
    ((SELECT COUNT(*) FROM devices active_device
      JOIN users active_user ON active_user.user_id = active_device.user_id
      JOIN memberships active_membership ON active_membership.user_id = active_device.user_id
      WHERE active_membership.workspace_id = candidate.workspace_id
        AND active_membership.status = 'active' AND active_device.status = 'active'
        AND active_user.status = 'active') = 1
      AND ?12 >= candidate.created_at + ?14)
  )
ON CONFLICT(workspace_id, checkpoint_id, ciphertext_sha256, validator_device_id)
DO NOTHING
`;

export const REJECT_CHECKPOINT_SQL = `
UPDATE checkpoints AS candidate SET status = 'rejected'
WHERE candidate.workspace_id = ?1 AND candidate.checkpoint_id = ?2
  AND candidate.ciphertext_sha256 = ?3 AND candidate.status = 'candidate'
  AND EXISTS (
    SELECT 1 FROM workspaces w
    JOIN workspace_capabilities capability
      ON capability.capability_id = ?4 AND capability.workspace_id = w.workspace_id
    JOIN devices d ON d.device_id = capability.device_id AND d.device_id = ?5
    JOIN users u ON u.user_id = d.user_id
    JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
    WHERE w.workspace_id = candidate.workspace_id
      AND w.candidate_checkpoint_id = candidate.checkpoint_id
      AND w.status = 'active' AND w.rotation_required = 0
      AND candidate.key_epoch = w.active_key_epoch
      AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
      AND capability.revoked_at IS NULL AND capability.expires_at > ?6
      AND capability.device_auth_epoch = d.auth_epoch
      AND capability.membership_auth_epoch = m.auth_epoch
      AND capability.key_epoch = w.active_key_epoch
  )
`;

export const CLEAR_REJECTED_CHECKPOINT_POINTER_SQL = `
UPDATE workspaces AS w SET candidate_checkpoint_id = NULL
WHERE w.workspace_id = ?1 AND w.candidate_checkpoint_id = ?2
  AND EXISTS (
    SELECT 1 FROM checkpoints candidate
    WHERE candidate.workspace_id = w.workspace_id
      AND candidate.checkpoint_id = ?2 AND candidate.ciphertext_sha256 = ?3
      AND candidate.status = 'rejected'
  )
  AND EXISTS (
    SELECT 1 FROM workspace_capabilities capability
    JOIN devices d ON d.device_id = capability.device_id AND d.device_id = ?5
    JOIN users u ON u.user_id = d.user_id
    JOIN memberships m ON m.workspace_id = w.workspace_id AND m.user_id = d.user_id
    WHERE capability.capability_id = ?4 AND capability.workspace_id = w.workspace_id
      AND w.status = 'active' AND w.rotation_required = 0
      AND d.status = 'active' AND u.status = 'active' AND m.status = 'active'
      AND capability.revoked_at IS NULL AND capability.expires_at > ?6
      AND capability.device_auth_epoch = d.auth_epoch
      AND capability.membership_auth_epoch = m.auth_epoch
      AND capability.key_epoch = w.active_key_epoch
  )
`;
