import type { Env, RequestBody } from "./runtime.ts";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: Record<string, unknown>;

  constructor(status: number, code: string, message = code, details?: Record<string, unknown>) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

const encoder = new TextEncoder();

export function securityHeaders(): Headers {
  return new Headers({
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
    "Cross-Origin-Resource-Policy": "same-origin",
    "Referrer-Policy": "no-referrer",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  });
}

export function jsonResponse(
  value: unknown,
  status = 200,
  extraHeaders?: HeadersInit,
): Response {
  const headers = securityHeaders();
  headers.set("Content-Type", "application/json; charset=utf-8");
  if (extraHeaders) {
    new Headers(extraHeaders).forEach((entry, key) => headers.set(key, entry));
  }
  return new Response(JSON.stringify(value), { status, headers });
}

export function emptyResponse(status = 204, extraHeaders?: HeadersInit): Response {
  const headers = securityHeaders();
  if (extraHeaders) {
    new Headers(extraHeaders).forEach((entry, key) => headers.set(key, entry));
  }
  return new Response(null, { status, headers });
}

export function errorResponse(error: unknown): Response {
  if (error instanceof ApiError) {
    return jsonResponse(
      { error: { code: error.code, message: error.message, ...(error.details ?? {}) } },
      error.status,
    );
  }
  return jsonResponse(
    { error: { code: "internal_error", message: "The request could not be completed" } },
    500,
  );
}

export function allowedOrigin(request: Request, env: Env): string | null {
  const origin = request.headers.get("Origin");
  if (!origin) return null;
  const allowed = (env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  if (!allowed.includes(origin)) {
    throw new ApiError(403, "origin_forbidden");
  }
  return origin;
}

export function corsHeaders(request: Request, env: Env): HeadersInit {
  const origin = allowedOrigin(request, env);
  if (!origin) return {};
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Credentials": "false",
    "Access-Control-Allow-Headers": [
      "Authorization",
      "Content-Type",
      "X-Shinsou-Nonce",
      "X-Shinsou-Signature",
      "X-Shinsou-Timestamp",
      "X-Shinsou-Header-Cbor",
      "X-Shinsou-Ciphertext-Sha256",
      "X-Shinsou-Device-Signature",
    ].join(", "),
    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
    "Access-Control-Expose-Headers": "Content-Length, X-Shinsou-Ciphertext-Sha256",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

export async function readBytes(request: Request, maxBytes: number): Promise<Uint8Array> {
  const declared = request.headers.get("Content-Length");
  if (declared) {
    const size = Number(declared);
    if (!Number.isSafeInteger(size) || size < 0) {
      throw new ApiError(400, "invalid_content_length");
    }
    if (size > maxBytes) throw new ApiError(413, "body_too_large");
  }

  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel("body_too_large");
        throw new ApiError(413, "body_too_large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return joined;
}

export async function readJson<T = Record<string, unknown>>(
  request: Request,
  maxBytes: number,
): Promise<RequestBody<T>> {
  const contentType = request.headers.get("Content-Type")?.split(";", 1)[0].trim();
  if (contentType !== "application/json") {
    throw new ApiError(415, "json_content_type_required");
  }
  const raw = await readBytes(request, maxBytes);
  if (raw.byteLength === 0) throw new ApiError(400, "empty_body");
  try {
    const parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(raw));
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("object required");
    }
    return { raw, value: parsed as T };
  } catch {
    throw new ApiError(400, "invalid_json");
  }
}

export function bearerToken(request: Request): string {
  const value = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{32,})$/.exec(value);
  if (!match) throw new ApiError(401, "bearer_required");
  return match[1];
}

export function integerParam(
  value: string | null,
  fallback: number,
  minimum: number,
  maximum: number,
  name: string,
): number {
  if (value === null || value === "") return fallback;
  if (!/^\d+$/.test(value)) throw new ApiError(400, `invalid_${name}`);
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new ApiError(400, `invalid_${name}`);
  }
  return parsed;
}

export function requireObject(value: unknown, name: string): Record<string, unknown> {
  if (!value || Array.isArray(value) || typeof value !== "object") {
    throw new ApiError(400, `invalid_${name}`);
  }
  return value as Record<string, unknown>;
}

export function requireString(
  object: Record<string, unknown>,
  key: string,
  minimum = 1,
  maximum = 4096,
): string {
  const value = object[key];
  if (typeof value !== "string" || value.length < minimum || value.length > maximum) {
    throw new ApiError(400, `invalid_${key}`);
  }
  return value;
}

export function optionalString(
  object: Record<string, unknown>,
  key: string,
  maximum = 4096,
): string | null {
  const value = object[key];
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || value.length > maximum) {
    throw new ApiError(400, `invalid_${key}`);
  }
  return value;
}

export function requireInteger(
  object: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number,
): number {
  const value = object[key];
  if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new ApiError(400, `invalid_${key}`);
  }
  return value as number;
}

export function utf8Bytes(value: string): Uint8Array {
  return encoder.encode(value);
}
