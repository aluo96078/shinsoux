import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { test } from "node:test";
import {
  assertOutsideCheckout,
  assertR2Inventory,
  buildEmergencyHandoffLink,
  buildGeneratedConfig,
  emergencyResetConfirmation,
  makeManifest,
  parseArguments,
  requestedFlag,
  resolveR2BucketNames,
  safeObjectFile,
  verifyManifestShape,
  workspaceR2Prefix,
} from "./cloudflare-ops.ts";
import bridgeWorker from "./r2-ops-bridge.ts";

test("argument parsing is deterministic and does not imply apply", () => {
  const parsed = parseArguments(["deploy", "--worker", "private-sync"]);
  assert.equal(parsed.command, "deploy");
  assert.equal(parsed.flags.get("worker"), "private-sync");
  assert.equal(parsed.flags.has("apply"), false);
  assert.equal(requestedFlag(parsed, "apply"), false);
  assert.equal(requestedFlag(parseArguments(["deploy", "--apply"]), "apply"), true);
  assert.throws(() => requestedFlag(parseArguments(["deploy", "--apply=false"]), "apply"), /does not take a value/);
  assert.throws(() => parseArguments(["deploy", "--apply", "--apply"]), /Duplicate option/);
  assert.throws(() => parseArguments(["deploy", "--apply=false", "--apply"]), /Duplicate option/);
  assert.deepEqual(resolveR2BucketNames(parseArguments(["deploy"])), {
    checkpoints: "shinsou-sync-checkpoints",
    blobs: "shinsou-sync-blobs",
  });
  assert.deepEqual(resolveR2BucketNames(parseArguments([
    "deploy", "--checkpoints-bucket", "private-checkpoints", "--blobs-bucket", "private-blobs",
  ])), { checkpoints: "private-checkpoints", blobs: "private-blobs" });
  assert.deepEqual(resolveR2BucketNames(parseArguments([
    "deploy", "--bucket", "legacy-checkpoints",
  ])), { checkpoints: "legacy-checkpoints", blobs: "shinsou-sync-blobs" });
  assert.deepEqual(resolveR2BucketNames(parseArguments(["deploy"]), {
    r2_buckets: [
      { binding: "CHECKPOINTS", bucket_name: "retained-checkpoints" },
      { binding: "BLOBS", bucket_name: "retained-blobs" },
    ],
  }), { checkpoints: "retained-checkpoints", blobs: "retained-blobs" });
  assert.throws(
    () => resolveR2BucketNames(parseArguments([
      "deploy", "--bucket", "legacy-checkpoints", "--checkpoints-bucket", "private-checkpoints",
    ])),
    /Use only one/,
  );
  assert.throws(
    () => resolveR2BucketNames(parseArguments([
      "deploy", "--checkpoints-bucket", "shared-bucket", "--blobs-bucket", "shared-bucket",
    ])),
    /different R2 buckets/,
  );
});

test("emergency reset dry-run performs no Cloudflare command and apply requires exact confirmation", () => {
  const script = fileURLToPath(new URL("./cloudflare-ops.ts", import.meta.url));
  const workspace = "00000000-0000-4000-8000-000000000007";
  const dryRun = spawnSync(process.execPath, [
    "--no-warnings=ExperimentalWarning", "--experimental-strip-types", script,
    "emergency-reset", "--workspace", workspace,
  ], { encoding: "utf8", env: { ...process.env, PATH: "" } });
  assert.equal(dryRun.status, 0, dryRun.stderr);
  assert.match(dryRun.stdout, /"mode": "dry-run"/);
  assert.match(dryRun.stdout, /"checkpoints": "shinsou-sync-checkpoints"/);
  assert.match(dryRun.stdout, /"blobs": "shinsou-sync-blobs"/);
  assert.match(dryRun.stdout, /No Cloudflare command was run/);

  const refused = spawnSync(process.execPath, [
    "--no-warnings=ExperimentalWarning", "--experimental-strip-types", script,
    "emergency-reset", "--workspace", workspace, "--apply", "--confirm", "WRONG",
  ], { encoding: "utf8", env: { ...process.env, PATH: "" } });
  assert.notEqual(refused.status, 0);
  assert.match(refused.stderr, /--confirm must exactly equal/);
  assert.equal(emergencyResetConfirmation(workspace), `RESET EMPTY WORKSPACE ${workspace}`);
  assert.equal(workspaceR2Prefix(workspace), `workspaces/${workspace}/`);
});

test("emergency handoff link is exact and carries the protected owner/workspace binding", () => {
  const link = buildEmergencyHandoffLink({
    formatVersion: 1,
    resetId: "00000000-0000-4000-8000-000000000020",
    workspaceId: "00000000-0000-4000-8000-000000000007",
    userId: "00000000-0000-4000-8000-000000000002",
    instanceId: "00000000-0000-4000-8000-000000000001",
    endpoint: "https://sync.example.test",
    handoffSecret: "R".repeat(43),
    handoffSecretHash: "hash",
    requestedAt: 1,
    expiresAt: 2,
  });
  assert.match(link, /^shinsou:\/\/sync\/emergency-reset\?/);
  const query = new URL(link.replace("shinsou://", "https://"));
  assert.equal(query.searchParams.get("workspace"), "00000000-0000-4000-8000-000000000007");
  assert.equal(query.searchParams.get("user"), "00000000-0000-4000-8000-000000000002");
  assert.equal(query.searchParams.get("session"), "00000000-0000-4000-8000-000000000020");
});

test("generated config replaces deployment identifiers without secrets", () => {
  const result = buildGeneratedConfig({ vars: { PROTOCOL_VERSION: "1" } }, {
    worker: "private-sync",
    database: "private-db",
    databaseId: "00000000-0000-0000-0000-000000000001",
    buckets: { checkpoints: "private-checkpoints", blobs: "private-blobs" },
    instanceId: "00000000-0000-4000-8000-000000000002",
  });
  assert.equal(result.name, "private-sync");
  assert.deepEqual(result.d1_databases, [{
    binding: "DB",
    database_name: "private-db",
    database_id: "00000000-0000-0000-0000-000000000001",
    migrations_dir: resolve("migrations"),
  }]);
  assert.deepEqual(result.r2_buckets, [
    { binding: "CHECKPOINTS", bucket_name: "private-checkpoints" },
    { binding: "BLOBS", bucket_name: "private-blobs" },
  ]);
  assert.equal(JSON.stringify(result).includes("SECRET"), false);
});

test("manifest keeps checkpoint and body inventories isolated and rejects traversal", () => {
  const manifest = makeManifest("2026-08-20T00:00:00.000Z", "a".repeat(64), [
    { key: "workspaces/b/two", file: "objects/checkpoints/workspaces/b/two", byteSize: 2, sha256: "2".repeat(64), httpMetadata: {}, customMetadata: {} },
    { key: "workspaces/a/one", file: "objects/checkpoints/workspaces/a/one", byteSize: 1, sha256: "1".repeat(64), httpMetadata: {}, customMetadata: { checkpointId: "id" } },
  ], [
    { key: "workspaces/a/one", file: "objects/blobs/workspaces/a/one", byteSize: 3, sha256: "3".repeat(64), httpMetadata: {}, customMetadata: { blobId: "id" } },
  ]);
  assert.deepEqual(manifest.r2.checkpoints.objects.map((entry) => entry.key), ["workspaces/a/one", "workspaces/b/two"]);
  assert.deepEqual(manifest.r2.blobs.objects.map((entry) => entry.key), ["workspaces/a/one"]);
  assert.equal(verifyManifestShape(manifest), manifest);
  assert.throws(() => safeObjectFile("checkpoints", "../escape"), /Unsafe R2 object key/);
  assert.throws(() => safeObjectFile("blobs", `workspaces/a/${"x".repeat(1025)}`), /Unsafe R2 object key/);
  assert.throws(() => safeObjectFile("checkpoints", "workspaces/a/line\nbreak"), /Unsafe R2 object key/);
  assert.throws(() => verifyManifestShape({
    ...manifest,
    r2: { ...manifest.r2, blobs: { objectCount: 2, objects: manifest.r2.blobs.objects } },
  }), /count/);
  assert.throws(() => assertOutsideCheckout(resolve("..", "local-backup")), /outside the Git checkout/);
  assert.doesNotThrow(() => assertOutsideCheckout(resolve("..", "..", "shinsou-sync-backup-test")));
  assertR2Inventory(manifest.r2.checkpoints.objects, manifest.r2.checkpoints.objects.map((entry) => ({
    key: entry.key,
    size: entry.byteSize,
    httpMetadata: entry.httpMetadata,
    customMetadata: entry.customMetadata,
  })), "checkpoints");
  assert.throws(() => assertR2Inventory(manifest.r2.blobs.objects, [], "blobs"), /count mismatch/);

  const legacy = verifyManifestShape({
    formatVersion: 1,
    createdAt: "2026-08-20T00:00:00.000Z",
    database: { file: "database.sql", sha256: "a".repeat(64) },
    r2: {
      objectCount: 1,
      objects: [{
        key: "workspaces/a/legacy",
        file: "objects/workspaces/a/legacy",
        byteSize: 1,
        sha256: "4".repeat(64),
        httpMetadata: {},
        customMetadata: {},
      }],
    },
  });
  assert.equal(legacy.formatVersion, 1);
  assert.equal(legacy.r2.checkpoints.objectCount, 1);
  assert.equal(legacy.r2.blobs.objectCount, 0);
});

test("temporary bridge is bearer protected and preserves camelCase R2 metadata", async () => {
  type StoredOptions = { httpMetadata: Record<string, string | Date>; customMetadata: Record<string, string> };
  let storedOptions: StoredOptions | undefined;
  let checkpointListedPrefix: string | undefined;
  let blobListedPrefix: string | undefined;
  let checkpointDeletedKey: string | undefined;
  let blobDeletedKey: string | undefined;
  const env = {
    OPS_TOKEN: "test-token",
    CHECKPOINTS: {
      async list(options: { prefix?: string }) {
        checkpointListedPrefix = options.prefix;
        return {
          objects: [{
            key: "workspaces/a/checkpoint",
            size: 3,
            httpMetadata: { contentType: "application/octet-stream" },
            customMetadata: { ciphertextSha256: "hash", checkpointId: "id" },
          }],
          truncated: false,
        };
      },
      async get() { return { body: new Blob(["abc"]).stream(), size: 3 }; },
      async put(_key: string, _body: ReadableStream | ArrayBuffer, options: StoredOptions) {
        storedOptions = options;
      },
      async delete(key: string) { checkpointDeletedKey = key; },
    },
    BLOBS: {
      async list(options: { prefix?: string }) {
        blobListedPrefix = options.prefix;
        return {
          objects: [{
            key: "workspaces/a/content/v2/blobs/body",
            size: 3,
            httpMetadata: { contentType: "application/octet-stream" },
            customMetadata: { blobId: "body" },
          }],
          truncated: false,
        };
      },
      async get() { return { body: new Blob(["xyz"]).stream(), size: 3 }; },
      async put(_key: string, _body: ReadableStream | ArrayBuffer, options: StoredOptions) {
        storedOptions = options;
      },
      async delete(key: string) { blobDeletedKey = key; },
    },
  };
  const unauthorized = await bridgeWorker.fetch(new Request("https://ops.invalid/checkpoints/list"), env);
  assert.equal(unauthorized.status, 401);

  const listed = await bridgeWorker.fetch(new Request("https://ops.invalid/checkpoints/list", {
    headers: { Authorization: "Bearer test-token" },
  }), env);
  assert.equal(listed.status, 200);
  assert.equal((await listed.text()).includes("ciphertextSha256"), true);

  const prefix = "workspaces/00000000-0000-4000-8000-000000000007/";
  const prefixed = await bridgeWorker.fetch(new Request(
    `https://ops.invalid/checkpoints/list?prefix=${encodeURIComponent(prefix)}`,
    { headers: { Authorization: "Bearer test-token" } },
  ), env);
  assert.equal(prefixed.status, 200);
  assert.equal(checkpointListedPrefix, prefix);
  const blobPrefixed = await bridgeWorker.fetch(new Request(
    `https://ops.invalid/blobs/list?prefix=${encodeURIComponent(prefix)}`,
    { headers: { Authorization: "Bearer test-token" } },
  ), env);
  assert.equal(blobPrefixed.status, 200);
  assert.equal(blobListedPrefix, prefix);
  const unsafePrefix = await bridgeWorker.fetch(new Request(
    "https://ops.invalid/checkpoints/list?prefix=workspaces%2Fother%2F",
    { headers: { Authorization: "Bearer test-token" } },
  ), env);
  assert.equal(unsafePrefix.status, 400);

  const httpMetadata = Buffer.from(JSON.stringify({
    contentType: "application/octet-stream",
    cacheExpiry: "2026-08-20T00:00:00.000Z",
  })).toString("base64url");
  const customMetadata = Buffer.from(JSON.stringify({ ciphertextSha256: "hash", blobId: "body" })).toString("base64url");
  const stored = await bridgeWorker.fetch(new Request("https://ops.invalid/blobs/object?key=workspaces/a/content/v2/blobs/body", {
    method: "PUT",
    headers: {
      Authorization: "Bearer test-token",
      "Content-Length": "3",
      "X-Shinsou-Http-Metadata": httpMetadata,
      "X-Shinsou-Custom-Metadata": customMetadata,
    },
    body: "abc",
  }), env);
  assert.equal(stored.status, 200);
  assert.deepEqual(storedOptions?.customMetadata, { ciphertextSha256: "hash", blobId: "body" });
  assert.equal(storedOptions?.httpMetadata.cacheExpiry instanceof Date, true);
  assert.equal((storedOptions?.httpMetadata.cacheExpiry as Date).toISOString(), "2026-08-20T00:00:00.000Z");

  const checkpointDeleted = await bridgeWorker.fetch(new Request("https://ops.invalid/checkpoints/object?key=workspaces/a/checkpoint", {
    method: "DELETE",
    headers: { Authorization: "Bearer test-token" },
  }), env);
  assert.equal(checkpointDeleted.status, 200);
  assert.equal(checkpointDeletedKey, "workspaces/a/checkpoint");
  const blobDeleted = await bridgeWorker.fetch(new Request("https://ops.invalid/blobs/object?key=workspaces/a/content/v2/blobs/body", {
    method: "DELETE",
    headers: { Authorization: "Bearer test-token" },
  }), env);
  assert.equal(blobDeleted.status, 200);
  assert.equal(blobDeletedKey, "workspaces/a/content/v2/blobs/body");

  const ambiguousLegacyRoute = await bridgeWorker.fetch(new Request("https://ops.invalid/list", {
    headers: { Authorization: "Bearer test-token" },
  }), env);
  assert.equal(ambiguousLegacyRoute.status, 400);
});
