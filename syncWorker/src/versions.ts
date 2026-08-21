import { ApiError } from "./http.ts";
import type { Env } from "./runtime.ts";

export type VersionCapabilities = {
  protocolVersion: number;
  minReaderVersion: number;
  minWriterVersion: number;
  schemaVersion: number;
  minSchemaReaderVersion: number;
  minSchemaWriterVersion: number;
};

export const BODY_PLANE_PROTOCOL_VERSION = 2;
export const BODY_PLANE_SCHEMA_VERSION = 2;

function configuredVersion(value: string | undefined, name: string): number {
  const encoded = value ?? "1";
  if (!/^[1-9][0-9]*$/.test(encoded)) {
    throw new ApiError(503, "version_configuration_invalid", `${name} must be a positive integer`);
  }
  const parsed = Number(encoded);
  if (!Number.isSafeInteger(parsed) || parsed > 0x7FFF_FFFF) {
    throw new ApiError(503, "version_configuration_invalid", `${name} is outside the signed 32-bit range`);
  }
  return parsed;
}

export function versionCapabilities(env: Env): VersionCapabilities {
  const result = {
    protocolVersion: configuredVersion(env.PROTOCOL_VERSION, "PROTOCOL_VERSION"),
    minReaderVersion: configuredVersion(env.MIN_READER_VERSION, "MIN_READER_VERSION"),
    minWriterVersion: configuredVersion(env.MIN_WRITER_VERSION, "MIN_WRITER_VERSION"),
    schemaVersion: configuredVersion(env.SCHEMA_VERSION, "SCHEMA_VERSION"),
    minSchemaReaderVersion: configuredVersion(
      env.MIN_SCHEMA_READER_VERSION,
      "MIN_SCHEMA_READER_VERSION",
    ),
    minSchemaWriterVersion: configuredVersion(
      env.MIN_SCHEMA_WRITER_VERSION,
      "MIN_SCHEMA_WRITER_VERSION",
    ),
  };
  if (result.minReaderVersion > result.protocolVersion ||
      result.minWriterVersion > result.protocolVersion ||
      result.minSchemaReaderVersion > result.schemaVersion ||
      result.minSchemaWriterVersion > result.schemaVersion) {
    throw new ApiError(
      503,
      "version_configuration_invalid",
      "Minimum reader/writer versions cannot exceed their advertised current version",
    );
  }
  return result;
}

/** Rejects writes the server cannot preserve for every advertised reader before storage access. */
export function requireWritableEnvelopeVersions(
  env: Env,
  protocolVersion: number,
  schemaVersion: number,
): void {
  const versions = versionCapabilities(env);
  if (protocolVersion < versions.minWriterVersion || protocolVersion > versions.protocolVersion) {
    throw new ApiError(409, "protocol_write_version_incompatible", "Protocol writer version is unsupported", {
      receivedVersion: protocolVersion,
      minWriterVersion: versions.minWriterVersion,
      maxWriterVersion: versions.protocolVersion,
    });
  }
  if (schemaVersion < versions.minSchemaWriterVersion || schemaVersion > versions.schemaVersion) {
    throw new ApiError(409, "schema_write_version_incompatible", "Schema writer version is unsupported", {
      receivedVersion: schemaVersion,
      minWriterVersion: versions.minSchemaWriterVersion,
      maxWriterVersion: versions.schemaVersion,
    });
  }
}

/** Body bytes are a v2-only contract and must never be interpreted by a v1 deployment/client. */
export function requireBodyPlaneVersions(
  env: Env,
  protocolVersion: number,
  schemaVersion: number,
): void {
  const versions = versionCapabilities(env);
  if (versions.protocolVersion < BODY_PLANE_PROTOCOL_VERSION ||
      versions.schemaVersion < BODY_PLANE_SCHEMA_VERSION) {
    throw new ApiError(503, "body_plane_not_configured");
  }
  if (protocolVersion !== BODY_PLANE_PROTOCOL_VERSION) {
    throw new ApiError(409, "body_protocol_version_incompatible", "Body-plane protocol v2 is required", {
      receivedVersion: protocolVersion,
      requiredVersion: BODY_PLANE_PROTOCOL_VERSION,
    });
  }
  if (schemaVersion !== BODY_PLANE_SCHEMA_VERSION) {
    throw new ApiError(409, "body_schema_version_incompatible", "Body-plane schema v2 is required", {
      receivedVersion: schemaVersion,
      requiredVersion: BODY_PLANE_SCHEMA_VERSION,
    });
  }
}
