import { ApiError } from "../http.ts";
import { concatBytes, timingSafeEqual } from "./base.ts";

type CborScalar = null | boolean | number | string | Uint8Array;
export type CborValue = CborScalar | CborValue[] | { [key: string]: CborValue };

function unsignedLength(major: number, value: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0) throw new ApiError(400, "invalid_cbor_integer");
  if (value < 24) return Uint8Array.of((major << 5) | value);
  if (value <= 0xff) return Uint8Array.of((major << 5) | 24, value);
  if (value <= 0xffff) return Uint8Array.of((major << 5) | 25, value >>> 8, value & 0xff);
  if (value <= 0xffffffff) {
    return Uint8Array.of(
      (major << 5) | 26,
      (value / 0x1000000) & 0xff,
      (value >>> 16) & 0xff,
      (value >>> 8) & 0xff,
      value & 0xff,
    );
  }
  const high = Math.floor(value / 0x100000000);
  const low = value >>> 0;
  return Uint8Array.of(
    (major << 5) | 27,
    (high >>> 24) & 0xff,
    (high >>> 16) & 0xff,
    (high >>> 8) & 0xff,
    high & 0xff,
    (low >>> 24) & 0xff,
    (low >>> 16) & 0xff,
    (low >>> 8) & 0xff,
    low & 0xff,
  );
}

function compareCanonicalKeys(left: Uint8Array, right: Uint8Array): number {
  if (left.byteLength !== right.byteLength) return left.byteLength - right.byteLength;
  for (let index = 0; index < left.byteLength; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return 0;
}

export function encodeCanonicalCbor(value: CborValue): Uint8Array {
  if (value === null) return Uint8Array.of(0xf6);
  if (value === false) return Uint8Array.of(0xf4);
  if (value === true) return Uint8Array.of(0xf5);
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new ApiError(400, "cbor_integer_required");
    return value >= 0 ? unsignedLength(0, value) : unsignedLength(1, -1 - value);
  }
  if (typeof value === "string") {
    const bytes = new TextEncoder().encode(value);
    return concatBytes(unsignedLength(3, bytes.byteLength), bytes);
  }
  if (value instanceof Uint8Array) {
    return concatBytes(unsignedLength(2, value.byteLength), value);
  }
  if (Array.isArray(value)) {
    return concatBytes(unsignedLength(4, value.length), ...value.map(encodeCanonicalCbor));
  }
  const entries = Object.entries(value).map(([key, entryValue]) => ({
    keyBytes: encodeCanonicalCbor(key),
    valueBytes: encodeCanonicalCbor(entryValue),
  }));
  entries.sort((left, right) => compareCanonicalKeys(left.keyBytes, right.keyBytes));
  return concatBytes(
    unsignedLength(5, entries.length),
    ...entries.flatMap((entry) => [entry.keyBytes, entry.valueBytes]),
  );
}

class Decoder {
  private offset = 0;
  private readonly bytes: Uint8Array;

  constructor(bytes: Uint8Array) {
    this.bytes = bytes;
  }

  done(): boolean {
    return this.offset === this.bytes.byteLength;
  }

  private take(): number {
    if (this.offset >= this.bytes.byteLength) throw new ApiError(400, "truncated_cbor");
    return this.bytes[this.offset++];
  }

  private length(additional: number): number {
    if (additional < 24) return additional;
    const widths: Record<number, number> = { 24: 1, 25: 2, 26: 4, 27: 8 };
    const width = widths[additional];
    if (!width) throw new ApiError(400, "unsupported_cbor_length");
    let value = 0;
    for (let index = 0; index < width; index += 1) value = value * 256 + this.take();
    if (!Number.isSafeInteger(value)) throw new ApiError(400, "cbor_integer_too_large");
    if ((additional === 24 && value < 24) ||
        (additional === 25 && value <= 0xff) ||
        (additional === 26 && value <= 0xffff) ||
        (additional === 27 && value <= 0xffffffff)) {
      throw new ApiError(400, "non_canonical_cbor_length");
    }
    return value;
  }

  read(depth = 0): CborValue {
    if (depth > 16) throw new ApiError(400, "cbor_nesting_too_deep");
    const initial = this.take();
    const major = initial >>> 5;
    const additional = initial & 0x1f;
    if (major === 7) {
      if (additional === 20) return false;
      if (additional === 21) return true;
      if (additional === 22) return null;
      throw new ApiError(400, "unsupported_cbor_simple_value");
    }
    const length = this.length(additional);
    if (major === 0) return length;
    if (major === 1) return -1 - length;
    if (major === 2 || major === 3) {
      if (this.offset + length > this.bytes.byteLength) throw new ApiError(400, "truncated_cbor");
      const value = this.bytes.slice(this.offset, this.offset + length);
      this.offset += length;
      if (major === 2) return value;
      try {
        return new TextDecoder("utf-8", { fatal: true }).decode(value);
      } catch {
        throw new ApiError(400, "invalid_cbor_utf8");
      }
    }
    if (major === 4) {
      const result: CborValue[] = [];
      for (let index = 0; index < length; index += 1) result.push(this.read(depth + 1));
      return result;
    }
    if (major === 5) {
      const result: Record<string, CborValue> = Object.create(null);
      for (let index = 0; index < length; index += 1) {
        const key = this.read(depth + 1);
        if (typeof key !== "string" || Object.hasOwn(result, key)) {
          throw new ApiError(400, "invalid_cbor_map_key");
        }
        result[key] = this.read(depth + 1);
      }
      return result;
    }
    throw new ApiError(400, "unsupported_cbor_type");
  }
}

export function decodeCanonicalCbor(bytes: Uint8Array): CborValue {
  const decoder = new Decoder(bytes);
  const value = decoder.read();
  if (!decoder.done()) throw new ApiError(400, "trailing_cbor_bytes");
  const canonical = encodeCanonicalCbor(value);
  if (!timingSafeEqual(canonical, bytes)) throw new ApiError(400, "non_canonical_cbor");
  return value;
}

export function decodeCanonicalMap(bytes: Uint8Array): Record<string, CborValue> {
  const value = decodeCanonicalCbor(bytes);
  if (!value || value instanceof Uint8Array || Array.isArray(value) || typeof value !== "object") {
    throw new ApiError(400, "cbor_map_required");
  }
  return value;
}
