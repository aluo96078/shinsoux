import assert from "node:assert/strict";
import test from "node:test";
import { ApiError } from "../src/http.ts";
import type { Env } from "../src/runtime.ts";
import { requireWritableEnvelopeVersions } from "../src/versions.ts";

const env = {
  PROTOCOL_VERSION: "4",
  MIN_READER_VERSION: "2",
  MIN_WRITER_VERSION: "3",
  SCHEMA_VERSION: "9",
  MIN_SCHEMA_READER_VERSION: "5",
  MIN_SCHEMA_WRITER_VERSION: "7",
} as Env;

test("server write gate applies protocol and schema writer ranges independently", () => {
  assert.doesNotThrow(() => requireWritableEnvelopeVersions(env, 3, 7));
  assert.doesNotThrow(() => requireWritableEnvelopeVersions(env, 4, 9));
  for (const [protocol, schema, code] of [
    [2, 7, "protocol_write_version_incompatible"],
    [5, 7, "protocol_write_version_incompatible"],
    [3, 6, "schema_write_version_incompatible"],
    [3, 10, "schema_write_version_incompatible"],
  ] as const) {
    assert.throws(
      () => requireWritableEnvelopeVersions(env, protocol, schema),
      (error: unknown) => error instanceof ApiError && error.status === 409 && error.code === code,
    );
  }
});
