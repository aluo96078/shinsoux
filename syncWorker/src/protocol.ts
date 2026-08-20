import { ApiError } from "./http.ts";
import { decodeBase64Url, domainSeparatedMessage } from "./crypto/base.ts";
import { decodeCanonicalMap, type CborValue } from "./crypto/cbor.ts";

export const LIMITS = Object.freeze({
  jsonBody: 256 * 1024,
  eventCiphertext: 32 * 1024,
  checkpointCiphertext: 32 * 1024 * 1024,
  checkpointUncompressed: 32 * 1024 * 1024,
  catchUpPage: 500,
  devicesPerUser: 10,
  eventDevicePerMinute: 60,
  eventWorkspacePerMinute: 300,
  workspaceSockets: 20,
  deviceSockets: 2,
});

export const CHECKPOINT_COMPRESSION = "LZ4_BLOCK_V1" as const;

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function requireUuid(value: unknown, name: string): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value.toLowerCase();
}

export function requirePublicKey(value: unknown, name: string): string {
  if (typeof value !== "string") throw new ApiError(400, `invalid_${name}`);
  const bytes = decodeBase64Url(value, name);
  if (bytes.byteLength !== 32) throw new ApiError(400, `invalid_${name}`);
  return value;
}

function valueString(map: Record<string, CborValue>, name: string): string {
  const value = map[name];
  if (typeof value !== "string") throw new ApiError(400, `invalid_header_${name}`);
  return value;
}

function valueInteger(map: Record<string, CborValue>, name: string, minimum = 0): number {
  const value = map[name];
  if (!Number.isSafeInteger(value) || (value as number) < minimum) {
    throw new ApiError(400, `invalid_header_${name}`);
  }
  return value as number;
}

function exactKeys(map: Record<string, CborValue>, expected: readonly string[]): void {
  const actual = Object.keys(map).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new ApiError(400, "unexpected_header_fields");
  }
}

export interface EventHeader {
  envelopeVersion: number;
  protocolVersion: number;
  schemaVersion: number;
  cipherSuite: string;
  nonce: string;
  instanceId: string;
  workspaceId: string;
  eventId: string;
  deviceId: string;
  deviceSeq: number;
  keyEpoch: number;
}

const EVENT_HEADER_KEYS = [
  "envelopeVersion", "protocolVersion", "schemaVersion", "cipherSuite", "nonce",
  "instanceId", "workspaceId", "eventId", "deviceId", "deviceSeq", "keyEpoch",
] as const;

function validateCipherSuite(map: Record<string, CborValue>): { cipherSuite: string; nonce: string } {
  const cipherSuite = valueString(map, "cipherSuite");
  // Protocol v1 has one wire algorithm. Advertising another enum without implementing it on
  // every client creates algorithm confusion: a correctly authenticated AES header would be fed
  // into the ChaCha primitive. A future suite requires a new negotiated protocol contract.
  if (cipherSuite !== "CHACHA20_POLY1305") {
    throw new ApiError(400, "unsupported_cipher_suite");
  }
  const nonce = valueString(map, "nonce");
  if (decodeBase64Url(nonce, "nonce").byteLength !== 12) {
    throw new ApiError(400, "invalid_nonce");
  }
  return { cipherSuite, nonce };
}

export function parseEventHeader(bytes: Uint8Array): EventHeader {
  const map = decodeCanonicalMap(bytes);
  exactKeys(map, EVENT_HEADER_KEYS);
  const { cipherSuite, nonce } = validateCipherSuite(map);
  const header: EventHeader = {
    envelopeVersion: valueInteger(map, "envelopeVersion", 1),
    protocolVersion: valueInteger(map, "protocolVersion", 1),
    schemaVersion: valueInteger(map, "schemaVersion", 1),
    cipherSuite,
    nonce,
    instanceId: requireUuid(valueString(map, "instanceId"), "header_instance_id"),
    workspaceId: requireUuid(valueString(map, "workspaceId"), "header_workspace_id"),
    eventId: requireUuid(valueString(map, "eventId"), "header_event_id"),
    deviceId: requireUuid(valueString(map, "deviceId"), "header_device_id"),
    deviceSeq: valueInteger(map, "deviceSeq", 1),
    keyEpoch: valueInteger(map, "keyEpoch", 1),
  };
  if (header.envelopeVersion !== 1) {
    throw new ApiError(426, "protocol_upgrade_required");
  }
  return header;
}

export interface CheckpointHeader {
  envelopeVersion: number;
  protocolVersion: number;
  schemaVersion: number;
  cipherSuite: string;
  nonce: string;
  instanceId: string;
  workspaceId: string;
  checkpointId: string;
  deviceId: string;
  throughWorkspaceSeq: number;
  keyEpoch: number;
  previousStableCheckpointHash: string;
  stateFormat: string;
  compression: typeof CHECKPOINT_COMPRESSION;
  uncompressedSize: number;
}

const CHECKPOINT_HEADER_KEYS = [
  "envelopeVersion", "protocolVersion", "schemaVersion", "cipherSuite", "nonce",
  "instanceId", "workspaceId", "checkpointId", "deviceId", "throughWorkspaceSeq",
  "keyEpoch", "previousStableCheckpointHash", "stateFormat", "compression", "uncompressedSize",
] as const;

export function parseCheckpointHeader(bytes: Uint8Array): CheckpointHeader {
  const map = decodeCanonicalMap(bytes);
  exactKeys(map, CHECKPOINT_HEADER_KEYS);
  const { cipherSuite, nonce } = validateCipherSuite(map);
  const previous = valueString(map, "previousStableCheckpointHash");
  if (previous && decodeBase64Url(previous, "previous_stable_checkpoint_hash").byteLength !== 32) {
    throw new ApiError(400, "invalid_previous_stable_checkpoint_hash");
  }
  const stateFormat = valueString(map, "stateFormat");
  if (stateFormat !== "sync-state-v1") throw new ApiError(400, "unsupported_checkpoint_state_format");
  const compression = valueString(map, "compression");
  if (compression !== CHECKPOINT_COMPRESSION) {
    throw new ApiError(400, "unsupported_checkpoint_compression");
  }
  const uncompressedSize = valueInteger(map, "uncompressedSize", 1);
  if (uncompressedSize > LIMITS.checkpointUncompressed) {
    throw new ApiError(400, "invalid_header_uncompressedSize");
  }
  const header: CheckpointHeader = {
    envelopeVersion: valueInteger(map, "envelopeVersion", 1),
    protocolVersion: valueInteger(map, "protocolVersion", 1),
    schemaVersion: valueInteger(map, "schemaVersion", 1),
    cipherSuite,
    nonce,
    instanceId: requireUuid(valueString(map, "instanceId"), "header_instance_id"),
    workspaceId: requireUuid(valueString(map, "workspaceId"), "header_workspace_id"),
    checkpointId: requireUuid(valueString(map, "checkpointId"), "header_checkpoint_id"),
    deviceId: requireUuid(valueString(map, "deviceId"), "header_device_id"),
    throughWorkspaceSeq: valueInteger(map, "throughWorkspaceSeq", 0),
    keyEpoch: valueInteger(map, "keyEpoch", 1),
    previousStableCheckpointHash: previous,
    stateFormat,
    compression,
    uncompressedSize,
  };
  if (header.envelopeVersion !== 1) {
    throw new ApiError(426, "protocol_upgrade_required");
  }
  return header;
}

export function envelopeSignatureMessage(
  kind: "event" | "checkpoint",
  headerCbor: Uint8Array,
  ciphertextSha256: Uint8Array,
): Uint8Array {
  if (ciphertextSha256.byteLength !== 32) throw new ApiError(400, "invalid_ciphertext_sha256");
  return domainSeparatedMessage(`${kind}-envelope`, headerCbor, ciphertextSha256);
}

export function checkpointObjectKey(
  workspaceId: string,
  throughSeq: number,
  checkpointId: string,
  sha256Hex: string,
): string {
  const workspace = requireUuid(workspaceId, "workspace_id");
  const checkpoint = requireUuid(checkpointId, "checkpoint_id");
  if (!Number.isSafeInteger(throughSeq) || throughSeq < 0) throw new ApiError(400, "invalid_through_seq");
  if (!/^[0-9a-f]{64}$/.test(sha256Hex)) throw new ApiError(400, "invalid_ciphertext_sha256_hex");
  return `workspaces/${workspace}/checkpoints/v1/${throughSeq}/${checkpoint}-${sha256Hex}.bin`;
}

export type SequenceDecision =
  | { kind: "append" }
  | { kind: "duplicate"; workspaceSeq: number }
  | { kind: "replay_or_corruption" }
  | { kind: "sequence_gap"; expectedDeviceSeq: number };

export function classifyDeviceSequence(
  committedDeviceSeq: number,
  incomingDeviceSeq: number,
  existingReceipt: { ciphertextSha256: string; workspaceSeq: number } | null,
  incomingHash: string,
): SequenceDecision {
  if (incomingDeviceSeq <= committedDeviceSeq) {
    if (!existingReceipt || existingReceipt.ciphertextSha256 !== incomingHash) {
      return { kind: "replay_or_corruption" };
    }
    return { kind: "duplicate", workspaceSeq: existingReceipt.workspaceSeq };
  }
  const expectedDeviceSeq = committedDeviceSeq + 1;
  if (incomingDeviceSeq !== expectedDeviceSeq) return { kind: "sequence_gap", expectedDeviceSeq };
  return { kind: "append" };
}

export interface CheckpointAckFacts {
  checkpointId: string;
  checkpointHash: string;
  throughSeq: number;
  expectedReplayFromSeq: number;
  replayFromSeq: number;
  replayedThroughSeq: number;
  replayedEventCount: number;
  authoritativeEventCount: number;
  previousStableCheckpointId: string | null;
  previousStableHash: string | null;
  assertedPreviousStableCheckpointId: string | null;
  assertedPreviousStableHash: string | null;
}

export function validateCheckpointReplayAck(facts: CheckpointAckFacts): void {
  if (facts.replayedThroughSeq !== facts.throughSeq ||
      facts.replayedEventCount !== facts.authoritativeEventCount ||
      facts.assertedPreviousStableCheckpointId !== facts.previousStableCheckpointId ||
      facts.assertedPreviousStableHash !== facts.previousStableHash) {
    throw new ApiError(409, "checkpoint_replay_incomplete");
  }
  if (facts.replayFromSeq !== facts.expectedReplayFromSeq || facts.replayFromSeq > facts.throughSeq) {
    throw new ApiError(409, "checkpoint_replay_range_invalid");
  }
}

export function validateRecipientSet(
  expected: ReadonlyArray<{ deviceId: string; authEpoch: number }>,
  actual: ReadonlyArray<{ deviceId: string; authEpoch: number }>,
): void {
  const normalize = (items: ReadonlyArray<{ deviceId: string; authEpoch: number }>) =>
    items.map((item) => `${item.deviceId}:${item.authEpoch}`).sort();
  const left = normalize(expected);
  const right = normalize(actual);
  if (left.length !== right.length || left.some((item, index) => item !== right[index])) {
    throw new ApiError(409, "rotation_recipient_set_changed");
  }
}
