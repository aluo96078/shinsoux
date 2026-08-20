import { ApiError, utf8Bytes } from "../http.ts";
import type { Env } from "../runtime.ts";
import { canonicalJson, sha256Base64Url } from "../crypto/base.ts";
import { all, first } from "../storage/d1.ts";

export interface DeviceDirectoryEntry {
  deviceId: string;
  userId: string;
  displayName: string;
  platform: string;
  signingPublicKey: string;
  wrappingPublicKey: string;
  status: string;
  authEpoch: number;
  createdAt: number;
  revokedAt: number | null;
  attestation: {
    type: "initial" | "pairing" | "recovery";
    workspaceId: string;
    attestorDeviceId: string | null;
    attestorPublicKey: string;
    signatureDomain: string;
    manifestJson: string;
    signature: string;
    createdAt: number;
  };
}

interface DirectoryRow {
  deviceId: string;
  userId: string;
  displayName: string;
  platform: string;
  signingPublicKey: string;
  wrappingPublicKey: string;
  status: string;
  authEpoch: number;
  createdAt: number;
  revokedAt: number | null;
  attestationType: "initial" | "pairing" | "recovery" | null;
  attestationWorkspaceId: string | null;
  attestorDeviceId: string | null;
  attestorPublicKey: string | null;
  signatureDomain: string | null;
  manifestJson: string | null;
  attestationSignature: string | null;
  attestationCreatedAt: number | null;
}

function toEntry(row: DirectoryRow): DeviceDirectoryEntry {
  if (!row.attestationType || !row.attestationWorkspaceId || !row.attestorPublicKey ||
      !row.signatureDomain || !row.manifestJson || !row.attestationSignature ||
      row.attestationCreatedAt === null) {
    throw new ApiError(503, "device_directory_incomplete");
  }
  return {
    deviceId: row.deviceId,
    userId: row.userId,
    displayName: row.displayName,
    platform: row.platform,
    signingPublicKey: row.signingPublicKey,
    wrappingPublicKey: row.wrappingPublicKey,
    status: row.status,
    authEpoch: row.authEpoch,
    createdAt: row.createdAt,
    revokedAt: row.revokedAt,
    attestation: {
      type: row.attestationType,
      workspaceId: row.attestationWorkspaceId,
      attestorDeviceId: row.attestorDeviceId,
      attestorPublicKey: row.attestorPublicKey,
      signatureDomain: row.signatureDomain,
      manifestJson: row.manifestJson,
      signature: row.attestationSignature,
      createdAt: row.attestationCreatedAt,
    },
  };
}

const DIRECTORY_SELECT = `
  SELECT d.device_id AS deviceId, d.user_id AS userId, d.display_name AS displayName,
         d.platform, d.signing_public_key AS signingPublicKey,
         d.wrapping_public_key AS wrappingPublicKey, d.status,
         d.auth_epoch AS authEpoch, d.created_at AS createdAt, d.revoked_at AS revokedAt,
         a.attestation_type AS attestationType, a.workspace_id AS attestationWorkspaceId,
         a.attestor_device_id AS attestorDeviceId, a.attestor_public_key AS attestorPublicKey,
         a.signature_domain AS signatureDomain, a.manifest_json AS manifestJson,
         a.signature AS attestationSignature, a.created_at AS attestationCreatedAt
  FROM devices d LEFT JOIN device_key_attestations a ON a.device_id = d.device_id
`;

export async function loadWorkspaceDeviceDirectory(
  env: Env,
  workspaceId: string,
  senderDeviceIds?: ReadonlySet<string>,
): Promise<{
  version: number;
  hash: string;
  devices: DeviceDirectoryEntry[];
  allDeviceCount: number;
}> {
  const workspace = await first<{ version: number }>(env.DB, `
    SELECT device_directory_epoch AS version FROM workspaces
    WHERE workspace_id = ?1 AND status = 'active'
  `, workspaceId);
  if (!workspace) throw new ApiError(404, "workspace_not_found");
  const rows = await all<DirectoryRow>(env.DB, `${DIRECTORY_SELECT}
    JOIN memberships m ON m.user_id = d.user_id
    JOIN workspaces directory_workspace ON directory_workspace.workspace_id = m.workspace_id
    WHERE m.workspace_id = ?1 AND m.status = 'active'
      AND d.directory_generation = directory_workspace.directory_generation
    ORDER BY d.device_id
  `, workspaceId);
  const allDevices = rows.map(toEntry);
  const canonicalDirectory = canonicalJson({
    workspaceId,
    version: workspace.version,
    devices: allDevices,
  });
  const hash = await sha256Base64Url(utf8Bytes(canonicalDirectory));
  const devices = senderDeviceIds
    ? allDevices.filter((device) => senderDeviceIds.has(device.deviceId))
    : allDevices;
  if (senderDeviceIds && devices.length !== senderDeviceIds.size) {
    throw new ApiError(503, "event_sender_directory_incomplete");
  }
  return { version: workspace.version, hash, devices, allDeviceCount: allDevices.length };
}

export async function loadUserDevices(env: Env, userId: string): Promise<DeviceDirectoryEntry[]> {
  const rows = await all<DirectoryRow>(env.DB, `${DIRECTORY_SELECT}
    WHERE d.user_id = ?1 ORDER BY d.created_at, d.device_id
  `, userId);
  return rows.map(toEntry);
}
