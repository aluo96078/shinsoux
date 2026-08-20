import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/worker.ts";
import type { Env, ExecutionContext } from "../src/runtime.ts";
import { IDS } from "./helpers.ts";

function environment(): Env {
  return {
    DB: {} as Env["DB"],
    CHECKPOINTS: {} as Env["CHECKPOINTS"],
    WORKSPACE_HUB: {} as Env["WORKSPACE_HUB"],
    BOOTSTRAP_SECRET: "b".repeat(43),
    TOKEN_PEPPER: "p".repeat(32),
    RATE_LIMIT_SALT: "r".repeat(32),
    INSTANCE_ID: IDS.instance,
    REALTIME_ENABLED: "false",
    ALLOWED_ORIGINS: "https://app.example.test",
  };
}

const context: ExecutionContext = {
  waitUntil() {},
  passThroughOnException() {},
};

test("capabilities advertises Lite polling compatibility and version gates", async () => {
  const response = await worker.fetch(
    new Request("https://sync.example.test/v1/capabilities"),
    environment(),
    context,
  );
  assert.equal(response.status, 200);
  const body = await response.json() as Record<string, unknown>;
  assert.equal(body.profile, "lite");
  assert.equal(body.websocket, false);
  assert.equal(body.protocolVersion, 1);
  assert.equal(body.minReaderVersion, 1);
  assert.equal(body.minWriterVersion, 1);
  assert.equal(body.schemaVersion, 1);
  assert.equal(body.minSchemaReaderVersion, 1);
  assert.equal(body.minSchemaWriterVersion, 1);
  assert.equal(body.instanceId, IDS.instance);
});

test("protocol and state schema capability ranges are configured independently", async () => {
  const env = {
    ...environment(),
    PROTOCOL_VERSION: "3",
    MIN_READER_VERSION: "2",
    MIN_WRITER_VERSION: "1",
    SCHEMA_VERSION: "7",
    MIN_SCHEMA_READER_VERSION: "5",
    MIN_SCHEMA_WRITER_VERSION: "4",
  };
  const response = await worker.fetch(
    new Request("https://sync.example.test/v1/capabilities"),
    env,
    context,
  );
  assert.equal(response.status, 200);
  const body = await response.json() as Record<string, unknown>;
  assert.deepEqual(
    {
      protocolVersion: body.protocolVersion,
      minReaderVersion: body.minReaderVersion,
      minWriterVersion: body.minWriterVersion,
      schemaVersion: body.schemaVersion,
      minSchemaReaderVersion: body.minSchemaReaderVersion,
      minSchemaWriterVersion: body.minSchemaWriterVersion,
    },
    {
      protocolVersion: 3,
      minReaderVersion: 2,
      minWriterVersion: 1,
      schemaVersion: 7,
      minSchemaReaderVersion: 5,
      minSchemaWriterVersion: 4,
    },
  );
});

test("invalid version configuration fails closed", async () => {
  for (const env of [
    { ...environment(), SCHEMA_VERSION: "not-a-number" },
    { ...environment(), PROTOCOL_VERSION: "2147483648" },
    { ...environment(), SCHEMA_VERSION: "2", MIN_SCHEMA_WRITER_VERSION: "3" },
    { ...environment(), PROTOCOL_VERSION: "1", MIN_READER_VERSION: "2" },
  ]) {
    const response = await worker.fetch(
      new Request("https://sync.example.test/v1/capabilities"),
      env,
      context,
    );
    assert.equal(response.status, 503);
    assert.equal((await response.json() as { error: { code: string } }).error.code, "version_configuration_invalid");
  }
});

test("setup metadata is machine-readable without disclosing the bootstrap secret", async () => {
  const env = environment();
  const response = await worker.fetch(
    new Request("https://sync.example.test/setup"),
    env,
    context,
  );
  assert.equal(response.status, 200);
  const body = await response.text();
  assert.match(body, /name="shinsou-sync-endpoint" content="https:\/\/sync\.example\.test"/);
  assert.match(body, new RegExp(`name="shinsou-sync-instance" content="${IDS.instance}"`));
  const setupLink = `shinsou://sync/setup?endpoint=${encodeURIComponent("https://sync.example.test")}` +
    `&instance=${IDS.instance}`;
  const escapedSetupLink = setupLink.replaceAll("&", "&amp;");
  assert.ok(body.includes(`name="shinsou-sync-setup-link" content="${escapedSetupLink}"`));
  assert.ok(body.includes(`data-payload="${escapedSetupLink}"`));
  assert.match(body, /<path d="M/);
  assert.ok(body.includes(`id="open-shinsou" href="${escapedSetupLink}"`));
  assert.doesNotMatch(body, /<script|<img|<image|src=/i);
  assert.doesNotMatch(body, new RegExp(env.BOOTSTRAP_SECRET));
  assert.doesNotMatch(body, /[?&]secret=/);
});

test("unlisted browser origins are rejected while native requests remain allowed", async () => {
  const blocked = await worker.fetch(
    new Request("https://sync.example.test/v1/capabilities", {
      headers: { Origin: "https://evil.example" },
    }),
    environment(),
    context,
  );
  assert.equal(blocked.status, 403);
  const native = await worker.fetch(
    new Request("https://sync.example.test/v1/capabilities"),
    environment(),
    context,
  );
  assert.equal(native.status, 200);
});

test("allowed origin gets an exact CORS response rather than wildcard", async () => {
  const response = await worker.fetch(
    new Request("https://sync.example.test/v1/capabilities", {
      headers: { Origin: "https://app.example.test" },
    }),
    environment(),
    context,
  );
  assert.equal(response.headers.get("Access-Control-Allow-Origin"), "https://app.example.test");
  assert.notEqual(response.headers.get("Access-Control-Allow-Origin"), "*");
});

test("security headers are present on public metadata", async () => {
  const response = await worker.fetch(
    new Request("https://sync.example.test/v1/capabilities"),
    environment(),
    context,
  );
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("X-Frame-Options"), "DENY");
});
