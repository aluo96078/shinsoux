import { ApiError } from "../http.ts";

const encoder = new TextEncoder();

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function decodeBase64Url(value: string, name = "base64url"): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(value) || value.length % 4 === 1) {
    throw new ApiError(400, `invalid_${name}`);
  }
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  try {
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    if (encodeBase64Url(bytes) !== value) throw new Error("non-canonical base64url");
    return bytes;
  } catch {
    throw new ApiError(400, `invalid_${name}`);
  }
}

export function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

export function randomToken(length = 32): string {
  return encodeBase64Url(randomBytes(length));
}

export function randomUuid(): string {
  return crypto.randomUUID();
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const output = new Uint8Array(parts.reduce((sum, part) => sum + part.byteLength, 0));
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.byteLength;
  }
  return output;
}

export async function sha256Bytes(value: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", value));
}

export async function sha256Base64Url(value: Uint8Array): Promise<string> {
  return encodeBase64Url(await sha256Bytes(value));
}

export async function hashSecret(secret: string, pepper: string, domain: string): Promise<string> {
  return sha256Base64Url(encoder.encode(`shinsou:${domain}:v1:${pepper}:${secret}`));
}

export async function publicTokenCommitment(token: string, name = "token"): Promise<string> {
  const bytes = requireHighEntropySecret(token, name);
  return sha256Base64Url(bytes);
}

export function timingSafeEqual(left: Uint8Array, right: Uint8Array): boolean {
  const length = Math.max(left.byteLength, right.byteLength);
  let difference = left.byteLength ^ right.byteLength;
  for (let index = 0; index < length; index += 1) {
    difference |= (left[index] ?? 0) ^ (right[index] ?? 0);
  }
  return difference === 0;
}

export async function timingSafeSecretEqual(left: string, right: string): Promise<boolean> {
  const [leftHash, rightHash] = await Promise.all([
    sha256Bytes(encoder.encode(left)),
    sha256Bytes(encoder.encode(right)),
  ]);
  return timingSafeEqual(leftHash, rightHash);
}

export async function verifyEd25519(
  publicKeyBase64Url: string,
  signatureBase64Url: string,
  message: Uint8Array,
): Promise<boolean> {
  const publicKey = decodeBase64Url(publicKeyBase64Url, "signing_public_key");
  const signature = decodeBase64Url(signatureBase64Url, "signature");
  if (publicKey.byteLength !== 32 || signature.byteLength !== 64) return false;
  try {
    const key = await crypto.subtle.importKey("raw", publicKey, { name: "Ed25519" }, false, ["verify"]);
    return crypto.subtle.verify({ name: "Ed25519" }, key, signature, message);
  } catch {
    return false;
  }
}

export function domainSeparatedMessage(
  domain: string,
  ...parts: Uint8Array[]
): Uint8Array {
  return concatBytes(encoder.encode(`shinsou:${domain}:v1\0`), ...parts);
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function requireHighEntropySecret(value: string, name: string): Uint8Array {
  const bytes = decodeBase64Url(value, name);
  if (bytes.byteLength < 24) throw new ApiError(400, `${name}_too_short`);
  return bytes;
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new ApiError(400, "non_canonical_json_number");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (typeof value === "object" && value) {
    return `{${Object.entries(value as Record<string, unknown>)
      .filter(([, entry]) => entry !== undefined)
      .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
      .map(([key, entry]) => `${JSON.stringify(key)}:${canonicalJson(entry)}`)
      .join(",")}}`;
  }
  throw new ApiError(400, "unsupported_canonical_json_value");
}
