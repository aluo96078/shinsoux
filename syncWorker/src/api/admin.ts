import { ApiError, readJson } from "../http.ts";
import type { Env } from "../runtime.ts";
import { authenticateAccess, requireControlSignature } from "../auth/service.ts";
import { all, first, returning, run } from "../storage/d1.ts";
import { randomUuid } from "../crypto/base.ts";
import { LIMITS } from "../protocol.ts";

interface QuotaRow {
  quotaProfileId: string;
  maxUsers: number;
  maxWorkspacesPerUser: number;
  maxDevicesPerUser: number;
  maxWorkspaceBytes: number;
  maxEventBytes: number;
  maxCheckpointBytes: number;
}

interface TotalsRow {
  activeUsers: number;
  activeDevices: number;
  activeWorkspaces: number;
  committedBytes: number;
  reservedBytes: number;
}

interface WorkspaceUsageRow {
  workspaceId: string;
  ownerUserId: string;
  status: string;
  headSeq: number;
  committedBytes: number;
  reservedBytes: number;
  maximumBytes: number;
}

interface DailyUsageRow {
  day: string;
  eventsWritten: number;
  eventBytesWritten: number;
  checkpointsWritten: number;
  checkpointBytesWritten: number;
}

function integer(
  value: unknown,
  name: string,
  minimum: number,
  maximum: number,
): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value as number;
}

async function requireAdmin(request: Request, env: Env, now: number) {
  const access = await authenticateAccess(request, env, now);
  if (access.instanceRole !== "admin") throw new ApiError(403, "admin_required");
  return access;
}

async function quota(env: Env): Promise<QuotaRow> {
  const row = await first<QuotaRow>(env.DB, `
    SELECT quota_profile_id AS quotaProfileId, max_users AS maxUsers,
           max_workspaces_per_user AS maxWorkspacesPerUser,
           max_devices_per_user AS maxDevicesPerUser,
           max_workspace_bytes AS maxWorkspaceBytes, max_event_bytes AS maxEventBytes,
           max_checkpoint_bytes AS maxCheckpointBytes
    FROM quota_profiles WHERE quota_profile_id = 'private-default'
  `);
  if (!row) throw new ApiError(503, "quota_profile_unavailable");
  return row;
}

/** Returns aggregate/identifier metadata only; event and checkpoint ciphertext is never selected. */
export async function getAdminUsage(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  await requireAdmin(request, env, now);
  const [profile, totals, workspaces, daily] = await Promise.all([
    quota(env),
    first<TotalsRow>(env.DB, `
      SELECT
        (SELECT COUNT(*) FROM users WHERE status = 'active') AS activeUsers,
        (SELECT COUNT(*) FROM devices WHERE status = 'active') AS activeDevices,
        (SELECT COUNT(*) FROM workspaces WHERE status = 'active') AS activeWorkspaces,
        COALESCE((SELECT SUM(committed_bytes) FROM workspaces WHERE status <> 'reset'), 0) AS committedBytes,
        COALESCE((SELECT SUM(reserved_bytes) FROM workspaces WHERE status <> 'reset'), 0) AS reservedBytes
    `),
    all<WorkspaceUsageRow>(env.DB, `
      SELECT w.workspace_id AS workspaceId, w.owner_user_id AS ownerUserId,
             w.status, w.head_seq AS headSeq, w.committed_bytes AS committedBytes,
             w.reserved_bytes AS reservedBytes, q.max_workspace_bytes AS maximumBytes
      FROM workspaces w
      JOIN users u ON u.user_id = w.owner_user_id
      JOIN quota_profiles q ON q.quota_profile_id = u.quota_profile_id
      ORDER BY w.created_at, w.workspace_id
    `),
    all<DailyUsageRow>(env.DB, `
      SELECT day, SUM(events_written) AS eventsWritten,
             SUM(event_bytes_written) AS eventBytesWritten,
             SUM(checkpoints_written) AS checkpointsWritten,
             SUM(checkpoint_bytes_written) AS checkpointBytesWritten
      FROM usage_daily GROUP BY day ORDER BY day DESC LIMIT 30
    `),
  ]);
  return {
    generatedAt: now,
    quota: profile,
    totals: totals ?? {
      activeUsers: 0,
      activeDevices: 0,
      activeWorkspaces: 0,
      committedBytes: 0,
      reservedBytes: 0,
    },
    workspaces,
    daily,
  };
}

/**
 * Updates the shared private-instance profile with all lower-bound checks in the same
 * conditional D1 statement. This prevents concurrent storage writes from slipping
 * between validation and the quota change.
 */
export async function updateAdminQuota(request: Request, env: Env, now = Date.now()): Promise<unknown> {
  const { raw, value } = await readJson(request, 16 * 1024);
  const access = await requireAdmin(request, env, now);
  await requireControlSignature(request, raw, access, env, now);
  const maxUsers = integer(value.maxUsers, "max_users", 1, 10_000);
  // v1 creates exactly one private workspace during setup/invite redemption and exposes no
  // additional-workspace endpoint. Keeping the minimum at one enforces that MVP invariant;
  // the conditional count below is the authoritative gate for a future creation API.
  const maxWorkspacesPerUser = integer(value.maxWorkspacesPerUser, "max_workspaces_per_user", 1, 100);
  const maxDevicesPerUser = integer(value.maxDevicesPerUser, "max_devices_per_user", 1, 100);
  const maxWorkspaceBytes = integer(value.maxWorkspaceBytes, "max_workspace_bytes", 1_048_576, 1_099_511_627_776);
  const maxEventBytes = integer(value.maxEventBytes, "max_event_bytes", 1_024, LIMITS.eventCiphertext);
  const maxCheckpointBytes = integer(
    value.maxCheckpointBytes,
    "max_checkpoint_bytes",
    1_048_576,
    LIMITS.checkpointCiphertext,
  );
  if (maxEventBytes > maxWorkspaceBytes || maxCheckpointBytes > maxWorkspaceBytes) {
    throw new ApiError(400, "invalid_quota_relationship");
  }

  const updated = await returning<QuotaRow>(env.DB, `
    UPDATE quota_profiles SET
      max_users = ?1, max_workspaces_per_user = ?2, max_devices_per_user = ?3,
      max_workspace_bytes = ?4, max_event_bytes = ?5, max_checkpoint_bytes = ?6
    WHERE quota_profile_id = 'private-default'
      AND ?1 >= (SELECT COUNT(*) FROM users WHERE status <> 'deleted')
      AND ?2 >= COALESCE((
        SELECT MAX(workspace_count) FROM (
          SELECT COUNT(*) AS workspace_count FROM workspaces
          WHERE status <> 'reset' GROUP BY owner_user_id
        )
      ), 0)
      AND ?3 >= COALESCE((
        SELECT MAX(device_count) FROM (
          SELECT COUNT(*) AS device_count FROM devices
          WHERE status <> 'revoked' GROUP BY user_id
        )
      ), 0)
      AND ?4 >= COALESCE((SELECT MAX(committed_bytes + reserved_bytes) FROM workspaces), 0)
      AND ?5 >= COALESCE((SELECT MAX(byte_size) FROM events), 0)
      AND ?6 >= COALESCE((SELECT MAX(byte_size) FROM checkpoints), 0)
    RETURNING quota_profile_id AS quotaProfileId, max_users AS maxUsers,
      max_workspaces_per_user AS maxWorkspacesPerUser,
      max_devices_per_user AS maxDevicesPerUser,
      max_workspace_bytes AS maxWorkspaceBytes, max_event_bytes AS maxEventBytes,
      max_checkpoint_bytes AS maxCheckpointBytes
  `, maxUsers, maxWorkspacesPerUser, maxDevicesPerUser, maxWorkspaceBytes, maxEventBytes, maxCheckpointBytes);
  if (!updated) throw new ApiError(409, "quota_below_current_usage");
  await run(env.DB, `
    INSERT INTO audit_events(audit_id, actor_device_id, action, target_id, created_at)
    VALUES (?1, ?2, 'admin_quota_updated', 'private-default', ?3)
  `, randomUuid(), access.deviceId, now);
  return { updatedAt: now, quota: updated };
}
