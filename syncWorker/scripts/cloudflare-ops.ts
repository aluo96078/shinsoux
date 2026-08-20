import { createHash, randomBytes, randomUUID } from "node:crypto";
import {
  chmodSync,
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SCRIPT_FILE = fileURLToPath(import.meta.url);
const WORKER_DIR = resolve(dirname(SCRIPT_FILE), "..");
const CHECKOUT_DIR = resolve(WORKER_DIR, "..");
const BASE_CONFIG = join(WORKER_DIR, "wrangler.jsonc");
const WRANGLER = ["--yes", "wrangler@4"];
const BACKUP_FORMAT = 1;

type Arguments = { command?: string; flags: Map<string, string | boolean> };
type EmergencyResetBundle = {
  formatVersion: 1;
  resetId: string;
  workspaceId: string;
  userId: string;
  instanceId: string;
  endpoint: string;
  handoffSecret: string;
  handoffSecretHash: string;
  requestedAt: number;
  expiresAt: number;
  oneTimeLink: string;
};
type BackupObject = {
  key: string;
  file: string;
  byteSize: number;
  sha256: string;
  httpMetadata: Record<string, string>;
  customMetadata: Record<string, string>;
};
export type BackupManifest = {
  formatVersion: number;
  createdAt: string;
  database: { file: string; sha256: string };
  r2: { objectCount: number; objects: BackupObject[] };
};

function usage(): string {
  return `Shinsou Cloudflare operations (safe by default)

Usage:
  npm run ops -- deploy [--apply] [--worker NAME] [--database NAME] [--bucket NAME]
  npm run ops -- export --output DIR [--apply] [--config FILE] [--database NAME] [--bucket NAME]
  npm run ops -- verify-backup --input DIR
  npm run ops -- restore --input DIR [--apply] --confirm-empty-target NAME --confirm-empty-r2 NAME
  npm run ops -- emergency-reset --workspace UUID --endpoint URL [--apply]
    --confirm "RESET EMPTY WORKSPACE <UUID>" [--config FILE] [--database NAME] [--bucket NAME]

No remote command runs unless --apply is present. Secrets are stored in mode-0600 state outside
the Git checkout and are never printed. Wrangler uses its normal login or CLOUDFLARE_API_TOKEN.
Export/restore temporarily deploys a random, bearer-protected R2 binding bridge so Worker-native
custom metadata casing is preserved; the bridge is deleted in a finally block.
Emergency reset uses the logged-in Cloudflare operator as a separate authority, never bootstrap or
device credentials. Its one-time App handoff is written only to the protected deployment state.
`;
}

export function parseArguments(argv: string[]): Arguments {
  const command = argv[0];
  const flags = new Map<string, string | boolean>();
  for (let index = 1; index < argv.length; index += 1) {
    const token = argv[index]!;
    if (!token.startsWith("--")) throw new Error(`Unexpected positional argument: ${token}`);
    const equals = token.indexOf("=");
    const name = equals > 2 ? token.slice(2, equals) : token.slice(2);
    if (flags.has(name)) throw new Error(`Duplicate option: --${name}`);
    if (equals > 2) {
      flags.set(name, token.slice(equals + 1));
      continue;
    }
    const next = argv[index + 1];
    if (next && !next.startsWith("--")) {
      flags.set(name, next);
      index += 1;
    } else {
      flags.set(name, true);
    }
  }
  return { command, flags };
}

export function requestedFlag(args: Arguments, name: string): boolean {
  const value = args.flags.get(name);
  if (value === undefined) return false;
  if (value !== true) throw new Error(`--${name} is a boolean flag and does not take a value`);
  return true;
}

function flag(args: Arguments, name: string, fallback?: string): string | undefined {
  const value = args.flags.get(name);
  if (value === undefined || value === true) return value === true ? undefined : fallback;
  return value;
}

function requireFlag(args: Arguments, name: string): string {
  const value = flag(args, name);
  if (!value) throw new Error(`--${name} is required`);
  return value;
}

function assertKnownFlags(args: Arguments, allowed: string[]): void {
  for (const name of args.flags.keys()) {
    if (!allowed.includes(name)) throw new Error(`Unknown option: --${name}`);
  }
}

function sha256(data: Uint8Array | string): string {
  return createHash("sha256").update(data).digest("hex");
}

function jsonFile(path: string): unknown {
  return JSON.parse(readFileSync(path, "utf8"));
}

function runWrangler(
  wranglerArgs: string[],
  options: { input?: string; secrets?: string[]; quiet?: boolean } = {},
): string {
  const result = spawnSync("npx", [...WRANGLER, ...wranglerArgs], {
    cwd: WORKER_DIR,
    encoding: "utf8",
    input: options.input,
    env: { ...process.env, CI: "true", WRANGLER_LOG: "none", WRANGLER_SEND_METRICS: "false" },
    stdio: options.input === undefined ? ["ignore", "pipe", "pipe"] : ["pipe", "pipe", "pipe"],
  });
  let output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  for (const secret of options.secrets ?? []) output = output.split(secret).join("[REDACTED]");
  if (result.status !== 0) throw new Error(`Wrangler failed (${wranglerArgs[0]}):\n${output.trim()}`);
  if (!options.quiet && output.trim()) process.stdout.write(output);
  return `${result.stdout ?? ""}`;
}

function safeName(value: string, label: string): string {
  if (!/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(value)) {
    throw new Error(`${label} must be 3-63 lowercase letters, digits, or hyphens`);
  }
  return value;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function workspaceUuid(value: string): string {
  const normalized = value.toLowerCase();
  if (!UUID_PATTERN.test(normalized)) throw new Error("--workspace must be a canonical UUID");
  return normalized;
}

export function emergencyResetConfirmation(workspaceIdInput: string): string {
  return `RESET EMPTY WORKSPACE ${workspaceUuid(workspaceIdInput)}`;
}

export function workspaceR2Prefix(workspaceIdInput: string): string {
  return `workspaces/${workspaceUuid(workspaceIdInput)}/`;
}

function normalizedEndpoint(value: string): string {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
    throw new Error("--endpoint must be a secret-free HTTPS Worker origin");
  }
  const path = url.pathname.replace(/\/+$/, "");
  if (path && path !== "/") throw new Error("--endpoint must not contain a path");
  return url.origin;
}

export function buildEmergencyHandoffLink(bundle: Omit<EmergencyResetBundle, "oneTimeLink">): string {
  const query = new URLSearchParams({
    endpoint: normalizedEndpoint(bundle.endpoint),
    instance: workspaceUuid(bundle.instanceId),
    session: workspaceUuid(bundle.resetId),
    user: workspaceUuid(bundle.userId),
    workspace: workspaceUuid(bundle.workspaceId),
    secret: bundle.handoffSecret,
  });
  return `shinsou://sync/emergency-reset?${query}`;
}

function defaultStateDir(worker: string): string {
  return join(homedir(), ".config", "shinsou", "cloudflare", worker);
}

type SecretState = {
  bootstrapSecret: string;
  tokenPepper: string;
  rateLimitSalt: string;
};

function secret(): string {
  return randomBytes(32).toString("base64url");
}

function loadOrCreateSecrets(stateDir: string, bootstrapFile?: string): SecretState {
  const path = join(stateDir, "secrets.json");
  if (existsSync(path)) {
    const directoryMode = statSync(stateDir).mode & 0o777;
    if ((directoryMode & 0o077) !== 0) throw new Error(`Secret state directory permissions are too broad: ${stateDir}`);
    const mode = statSync(path).mode & 0o777;
    if ((mode & 0o077) !== 0) throw new Error(`Secret state permissions are too broad: ${path}`);
    const value = jsonFile(path) as Partial<SecretState>;
    const values = [value.bootstrapSecret, value.tokenPepper, value.rateLimitSalt];
    if (values.some((entry) => typeof entry !== "string" || Buffer.byteLength(entry, "utf8") < 32) ||
        new Set(values).size !== values.length) {
      throw new Error(`Secret state is incomplete: ${path}`);
    }
    const bootstrapPath = join(stateDir, "bootstrap.secret");
    if (existsSync(bootstrapPath)) {
      const bootstrapMode = statSync(bootstrapPath).mode & 0o777;
      if ((bootstrapMode & 0o077) !== 0) throw new Error(`Bootstrap secret permissions are too broad: ${bootstrapPath}`);
      if (readFileSync(bootstrapPath, "utf8").trim() !== value.bootstrapSecret) {
        throw new Error(`Bootstrap secret does not match secret state: ${bootstrapPath}`);
      }
    } else {
      writeFileSync(bootstrapPath, `${value.bootstrapSecret}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
      chmodSync(bootstrapPath, 0o600);
    }
    return value as SecretState;
  }
  const bootstrapSecret = bootstrapFile ? readFileSync(resolve(bootstrapFile), "utf8").trim() : secret();
  if (Buffer.from(bootstrapSecret, "utf8").byteLength < 32) {
    throw new Error("Bootstrap secret must be at least 32 bytes");
  }
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  chmodSync(stateDir, 0o700);
  const value = { bootstrapSecret, tokenPepper: secret(), rateLimitSalt: secret() };
  writeFileSync(path, `${JSON.stringify(value)}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  chmodSync(path, 0o600);
  const bootstrapPath = join(stateDir, "bootstrap.secret");
  writeFileSync(bootstrapPath, `${bootstrapSecret}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  chmodSync(bootstrapPath, 0o600);
  return value;
}

function parseJsonOutput(output: string): unknown {
  const starts = [0];
  for (const match of output.matchAll(/(?:^|\n)\s*[\[{]/g)) {
    const marker = match[0].lastIndexOf(match[0].includes("[") ? "[" : "{");
    starts.push((match.index ?? 0) + marker);
  }
  for (const start of [...new Set(starts)].sort((a, b) => a - b)) {
    try {
      return JSON.parse(output.slice(start).trim());
    } catch {
      // Wrangler may print a banner before the machine-readable payload.
    }
  }
  throw new Error("Wrangler did not return valid JSON");
}

function findD1Id(value: unknown, name: string): string | undefined {
  if (Array.isArray(value)) {
    for (const entry of value) {
      const found = findD1Id(entry, name);
      if (found) return found;
    }
    return undefined;
  }
  if (!value || typeof value !== "object") return undefined;
  const row = value as Record<string, unknown>;
  const id = row.uuid ?? row.id ?? row.database_id;
  if ((row.name === name || row.database_name === name) && typeof id === "string") return id;
  for (const nested of Object.values(row)) {
    const found = findD1Id(nested, name);
    if (found) return found;
  }
  return undefined;
}

function findCreatedD1Id(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    for (const entry of value) {
      const found = findCreatedD1Id(entry);
      if (found) return found;
    }
    return undefined;
  }
  if (!value || typeof value !== "object") return undefined;
  const row = value as Record<string, unknown>;
  const id = row.uuid ?? row.id ?? row.database_id;
  if (typeof id === "string" && /^[0-9a-f-]{36}$/i.test(id)) return id;
  for (const nested of Object.values(row)) {
    const found = findCreatedD1Id(nested);
    if (found) return found;
  }
  return undefined;
}

function containsNamedResource(value: unknown, name: string): boolean {
  if (Array.isArray(value)) return value.some((entry) => containsNamedResource(entry, name));
  if (!value || typeof value !== "object") return false;
  const row = value as Record<string, unknown>;
  return row.name === name || row.bucket_name === name || Object.values(row).some((entry) =>
    containsNamedResource(entry, name)
  );
}

export function buildGeneratedConfig(
  base: Record<string, unknown>,
  values: { worker: string; database: string; databaseId: string; bucket: string; instanceId: string },
): Record<string, unknown> {
  return {
    ...base,
    name: values.worker,
    main: join(WORKER_DIR, "src", "worker.ts"),
    vars: { ...(base.vars as Record<string, unknown>), INSTANCE_ID: values.instanceId },
    d1_databases: [{
      binding: "DB",
      database_name: values.database,
      database_id: values.databaseId,
      migrations_dir: join(WORKER_DIR, "migrations"),
    }],
    r2_buckets: [{ binding: "CHECKPOINTS", bucket_name: values.bucket }],
  };
}

function deploy(args: Arguments): void {
  assertKnownFlags(args, [
    "apply", "worker", "database", "database-id", "bucket", "instance-id", "state-dir",
    "bootstrap-secret-file", "endpoint",
  ]);
  const worker = safeName(flag(args, "worker", "shinsou-sync")!, "worker name");
  const database = safeName(flag(args, "database", "shinsou-sync")!, "database name");
  const bucket = safeName(flag(args, "bucket", "shinsou-sync-checkpoints")!, "bucket name");
  const stateDir = resolve(flag(args, "state-dir", defaultStateDir(worker))!);
  assertOutsideCheckout(stateDir, "Deployment state");
  if (!requestedFlag(args, "apply")) {
    console.log(JSON.stringify({
      mode: "dry-run",
      operations: [
        `find or create D1 database ${database}`,
        `find or create private R2 bucket ${bucket}`,
        "generate/reuse three independent secrets in a mode-0600 state file outside Git",
        "apply numbered remote D1 migrations",
        `deploy Worker ${worker} and install encrypted secrets through stdin`,
        "print the secret-free /setup URL and terminal QR when available",
      ],
      stateDir,
    }, null, 2));
    console.log("Dry-run only. Add --apply to perform these operations.");
    return;
  }

  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  chmodSync(stateDir, 0o700);
  let databaseId = flag(args, "database-id");
  if (!databaseId) {
    const listed = parseJsonOutput(runWrangler(["d1", "list", "--json"], { quiet: true }));
    databaseId = findD1Id(listed, database);
  }
  if (!databaseId) {
    const created = parseJsonOutput(runWrangler(["d1", "create", database, "--json"], { quiet: true }));
    databaseId = findCreatedD1Id(created);
  }
  if (!/^[0-9a-f-]{36}$/i.test(databaseId)) throw new Error("Could not determine the D1 database ID");

  const buckets = parseJsonOutput(runWrangler(["r2", "bucket", "list", "--json"], { quiet: true }));
  const bucketExists = containsNamedResource(buckets, bucket);
  if (!bucketExists) runWrangler(["r2", "bucket", "create", bucket], { quiet: true });

  const existingConfigPath = join(stateDir, "wrangler.generated.jsonc");
  const existingConfig = existsSync(existingConfigPath)
    ? jsonFile(existingConfigPath) as { vars?: { INSTANCE_ID?: string } }
    : undefined;
  const instanceId = flag(args, "instance-id") ?? existingConfig?.vars?.INSTANCE_ID ?? randomUUID();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(instanceId)) {
    throw new Error("--instance-id must be a random UUID v4");
  }
  const base = JSON.parse(readFileSync(BASE_CONFIG, "utf8")) as Record<string, unknown>;
  const generated = buildGeneratedConfig(base, { worker, database, databaseId, bucket, instanceId });
  const configPath = existingConfigPath;
  writeFileSync(configPath, `${JSON.stringify(generated, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
  chmodSync(configPath, 0o600);
  const configArgs = ["--config", configPath];
  runWrangler(["d1", "migrations", "apply", database, "--remote", ...configArgs]);
  const deploymentOutput = runWrangler(["deploy", ...configArgs]);

  const secrets = loadOrCreateSecrets(stateDir, flag(args, "bootstrap-secret-file"));
  for (const [name, value] of Object.entries({
    BOOTSTRAP_SECRET: secrets.bootstrapSecret,
    TOKEN_PEPPER: secrets.tokenPepper,
    RATE_LIMIT_SALT: secrets.rateLimitSalt,
  })) {
    runWrangler(["secret", "put", name, ...configArgs], { input: `${value}\n`, secrets: [value], quiet: true });
  }

  const detectedEndpoint = deploymentOutput.match(/https:\/\/[^\s]+\.workers\.dev/)?.[0];
  const endpoint = flag(args, "endpoint") ?? detectedEndpoint;
  console.log(`Deployment state: ${stateDir}`);
  console.log(`Generated config: ${configPath}`);
  console.log(`Bootstrap secret file (mode 0600): ${join(stateDir, "bootstrap.secret")}`);
  console.log("The bootstrap secret was not printed.");
  if (endpoint) {
    const setupUrl = `${endpoint.replace(/\/$/, "")}/setup`;
    console.log(`Setup URL: ${setupUrl}`);
    const qr = spawnSync("npx", ["--yes", "qrcode-terminal", setupUrl], { cwd: WORKER_DIR, encoding: "utf8" });
    if (qr.status === 0 && qr.stdout) process.stdout.write(qr.stdout);
    else console.log("Terminal QR renderer unavailable; open the Setup URL directly.");
  } else {
    console.log("Wrangler output did not expose a workers.dev URL; pass --endpoint https://your-host to print /setup.");
  }
}

type BridgeObject = {
  key: string;
  size: number;
  httpMetadata: Record<string, string>;
  customMetadata: Record<string, string>;
};

class R2Bridge {
  private readonly endpoint: string;
  private readonly token: string;

  constructor(endpoint: string, token: string) {
    this.endpoint = endpoint;
    this.token = token;
  }

  private async request(path: string, init: RequestInit = {}): Promise<Response> {
    let status = 0;
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await fetch(`${this.endpoint}${path}`, {
        ...init,
        headers: { ...(init.headers ?? {}), Authorization: `Bearer ${this.token}` },
      });
      status = response.status;
      if (response.ok) return response;
      if (![401, 404, 429, 500, 502, 503, 504].includes(status)) break;
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 250 * (2 ** attempt)));
    }
    throw new Error(`Temporary R2 bridge failed with HTTP ${status}`);
  }

  async list(prefix?: string): Promise<BridgeObject[]> {
    const result: BridgeObject[] = [];
    let cursor = "";
    do {
      const query = new URLSearchParams();
      if (prefix) query.set("prefix", prefix);
      if (cursor) query.set("cursor", cursor);
      const response = await this.request(`/list${query.size ? `?${query}` : ""}`);
      const page = await response.json() as { objects: BridgeObject[]; truncated: boolean; cursor?: string };
      result.push(...page.objects);
      cursor = page.truncated ? page.cursor ?? "" : "";
      if (page.truncated && !cursor) throw new Error("R2 bridge returned a truncated page without cursor");
    } while (cursor);
    return result.sort((a, b) => a.key.localeCompare(b.key));
  }

  async get(key: string): Promise<Uint8Array> {
    const response = await this.request(`/object?key=${encodeURIComponent(key)}`);
    return new Uint8Array(await response.arrayBuffer());
  }

  async put(object: BackupObject, data: Uint8Array): Promise<void> {
    await this.request(`/object?key=${encodeURIComponent(object.key)}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-Shinsou-Http-Metadata": Buffer.from(JSON.stringify(object.httpMetadata)).toString("base64url"),
        "X-Shinsou-Custom-Metadata": Buffer.from(JSON.stringify(object.customMetadata)).toString("base64url"),
      },
      body: data,
    });
  }

  async delete(key: string): Promise<void> {
    await this.request(`/object?key=${encodeURIComponent(key)}`, { method: "DELETE" });
  }
}

async function withR2Bridge<T>(bucket: string, action: (bridge: R2Bridge) => Promise<T>): Promise<T> {
  const suffix = randomBytes(6).toString("hex");
  const workerName = `shinsou-r2-ops-${suffix}`;
  const temporaryDir = join(WORKER_DIR, ".wrangler", `ops-${suffix}`);
  const config = join(temporaryDir, "wrangler.jsonc");
  const token = secret();
  mkdirSync(temporaryDir, { recursive: true, mode: 0o700 });
  writeFileSync(config, `${JSON.stringify({
    name: workerName,
    main: join(WORKER_DIR, "scripts", "r2-ops-bridge.ts"),
    compatibility_date: "2026-08-20",
    workers_dev: true,
    r2_buckets: [{ binding: "CHECKPOINTS", bucket_name: bucket }],
  }, null, 2)}\n`, { mode: 0o600 });
  let deployed = false;
  try {
    const output = runWrangler(["deploy", "--config", config], { quiet: true });
    deployed = true;
    runWrangler(["secret", "put", "OPS_TOKEN", "--config", config], { input: `${token}\n`, secrets: [token], quiet: true });
    const endpoint = output.match(/https:\/\/[^\s]+\.workers\.dev/)?.[0];
    if (!endpoint) throw new Error("Could not determine temporary R2 bridge endpoint");
    return await action(new R2Bridge(endpoint, token));
  } finally {
    if (deployed) {
      try {
        runWrangler(["delete", workerName, "--force", "--config", config], { quiet: true });
      } catch (cleanupError) {
        console.error(`WARNING: temporary R2 bridge cleanup failed; delete Worker ${workerName} manually.`);
      }
    }
    rmSync(temporaryDir, { recursive: true, force: true });
  }
}

export function safeObjectFile(key: string): string {
  if (!key || Buffer.byteLength(key, "utf8") > 1024 || key.startsWith("/") || key.includes("\\") ||
      /[\u0000-\u001f\u007f]/.test(key)) {
    throw new Error(`Unsafe R2 object key: ${JSON.stringify(key)}`);
  }
  const pieces = key.split("/");
  if (pieces.some((piece) => piece === "" || piece === "." || piece === "..")) {
    throw new Error(`Unsafe R2 object key: ${JSON.stringify(key)}`);
  }
  return `objects/${pieces.join("/")}`;
}

const PRESERVED_HTTP_METADATA = new Set([
  "cacheControl", "cacheExpiry", "contentDisposition", "contentEncoding", "contentLanguage", "contentType",
]);

export function makeManifest(
  createdAt: string,
  databaseSha256: string,
  objects: BackupObject[],
): BackupManifest {
  const sorted = [...objects].sort((a, b) => a.key.localeCompare(b.key));
  return {
    formatVersion: BACKUP_FORMAT,
    createdAt,
    database: { file: "database.sql", sha256: databaseSha256 },
    r2: { objectCount: sorted.length, objects: sorted },
  };
}

function canonicalProspectivePath(path: string): string {
  let cursor = resolve(path);
  const missing: string[] = [];
  while (!existsSync(cursor)) {
    const parent = dirname(cursor);
    if (parent === cursor) break;
    missing.unshift(basename(cursor));
    cursor = parent;
  }
  return resolve(realpathSync(cursor), ...missing);
}

export function assertOutsideCheckout(path: string, label = "Backup output"): void {
  const canonicalCheckout = realpathSync(CHECKOUT_DIR);
  const rel = relative(canonicalCheckout, canonicalProspectivePath(path));
  if (rel === "" || (!rel.startsWith(`..${sep}`) && rel !== ".." && !isAbsolute(rel))) {
    throw new Error(`${label} must be outside the Git checkout`);
  }
}

async function exportBackup(args: Arguments): Promise<void> {
  assertKnownFlags(args, ["apply", "output", "config", "database", "bucket"]);
  const output = resolve(requireFlag(args, "output"));
  const database = safeName(flag(args, "database", "shinsou-sync")!, "database name");
  const bucket = safeName(flag(args, "bucket", "shinsou-sync-checkpoints")!, "bucket name");
  const config = resolve(flag(args, "config", BASE_CONFIG)!);
  assertOutsideCheckout(output);
  if (!requestedFlag(args, "apply")) {
    console.log(JSON.stringify({ mode: "dry-run", operation: "export", database, bucket, output, config }, null, 2));
    console.log("Dry-run only. Add --apply after reviewing the destination.");
    return;
  }
  if (existsSync(output)) throw new Error(`Backup destination already exists: ${output}`);
  mkdirSync(dirname(output), { recursive: true });
  const temporary = join(dirname(output), `.${basename(output)}.partial-${randomUUID()}`);
  mkdirSync(join(temporary, "objects"), { recursive: true, mode: 0o700 });
  chmodSync(temporary, 0o700);
  try {
    const databaseFile = join(temporary, "database.sql");
    runWrangler([
      "d1", "export", database, "--remote", "--skip-confirmation",
      "--output", databaseFile, "--config", config,
    ]);
    chmodSync(databaseFile, 0o600);
    const objects = await withR2Bridge(bucket, async (bridge) => {
      const backedUp: BackupObject[] = [];
      for (const listed of await bridge.list()) {
        const file = safeObjectFile(listed.key);
        const data = await bridge.get(listed.key);
        if (data.byteLength !== listed.size) throw new Error(`R2 size changed during export: ${listed.key}`);
        const target = resolve(temporary, file);
        if (!target.startsWith(`${temporary}${sep}`)) throw new Error("Resolved R2 path escaped backup directory");
        mkdirSync(dirname(target), { recursive: true, mode: 0o700 });
        writeFileSync(target, data, { mode: 0o600 });
        backedUp.push({
          key: listed.key,
          file,
          byteSize: data.byteLength,
          sha256: sha256(data),
          httpMetadata: listed.httpMetadata,
          customMetadata: listed.customMetadata,
        });
      }
      return backedUp;
    });
    const databaseHash = sha256(readFileSync(databaseFile));
    const manifest = makeManifest(new Date().toISOString(), databaseHash, objects);
    writeFileSync(join(temporary, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    renameSync(temporary, output);
    chmodSync(output, 0o700);
    console.log(`Verified backup written to ${output} (${objects.length} R2 objects).`);
  } catch (error) {
    rmSync(temporary, { recursive: true, force: true });
    throw error;
  }
}

export function verifyManifestShape(value: unknown): BackupManifest {
  const manifest = value as BackupManifest;
  if (!manifest || manifest.formatVersion !== BACKUP_FORMAT || !manifest.database || !manifest.r2) {
    throw new Error("Unsupported or invalid backup manifest");
  }
  if (manifest.database.file !== "database.sql" || !/^[0-9a-f]{64}$/.test(manifest.database.sha256)) {
    throw new Error("Invalid database manifest entry");
  }
  if (!Array.isArray(manifest.r2.objects) || manifest.r2.objectCount !== manifest.r2.objects.length) {
    throw new Error("Invalid R2 object count");
  }
  let previous = "";
  const keys = new Set<string>();
  for (const object of manifest.r2.objects) {
    if (object.file !== safeObjectFile(object.key) || object.key.localeCompare(previous) < 0 || keys.has(object.key)) {
      throw new Error("R2 manifest keys must be unique, sorted, and path-safe");
    }
    if (!Number.isSafeInteger(object.byteSize) || object.byteSize < 0 || !/^[0-9a-f]{64}$/.test(object.sha256)) {
      throw new Error(`Invalid R2 manifest entry for ${object.key}`);
    }
    for (const [name, metadataValue] of Object.entries(object.httpMetadata ?? {})) {
      if (!PRESERVED_HTTP_METADATA.has(name) || typeof metadataValue !== "string" || /[\r\n]/.test(metadataValue)) {
        throw new Error(`Unsafe R2 HTTP metadata for ${object.key}`);
      }
    }
    for (const [name, metadataValue] of Object.entries(object.customMetadata ?? {})) {
      if (!/^[A-Za-z][A-Za-z0-9-]{0,63}$/.test(name) || typeof metadataValue !== "string" || /[\r\n]/.test(metadataValue)) {
        throw new Error(`Unsafe R2 custom metadata for ${object.key}`);
      }
    }
    previous = object.key;
    keys.add(object.key);
  }
  return manifest;
}

function verifyBackup(input: string): BackupManifest {
  const root = resolve(input);
  const manifest = verifyManifestShape(jsonFile(join(root, "manifest.json")));
  const databaseFile = resolve(root, manifest.database.file);
  if (!databaseFile.startsWith(`${root}${sep}`) || sha256(readFileSync(databaseFile)) !== manifest.database.sha256) {
    throw new Error("D1 export SHA-256 mismatch");
  }
  for (const object of manifest.r2.objects) {
    const file = resolve(root, object.file);
    if (!file.startsWith(`${root}${sep}`)) throw new Error("R2 backup path escaped input directory");
    const data = readFileSync(file);
    if (data.byteLength !== object.byteSize || sha256(data) !== object.sha256) {
      throw new Error(`R2 object verification failed: ${object.key}`);
    }
  }
  return manifest;
}

function d1Rows(value: unknown): Array<Record<string, unknown>> {
  const envelopes = Array.isArray(value) ? value : [value];
  const rows: Array<Record<string, unknown>> = [];
  for (const envelope of envelopes) {
    if (!envelope || typeof envelope !== "object") continue;
    const result = (envelope as Record<string, unknown>).results;
    if (Array.isArray(result)) rows.push(...result.filter((entry) => entry && typeof entry === "object") as Array<Record<string, unknown>>);
  }
  return rows;
}

function queryD1(database: string, config: string, sql: string): Array<Record<string, unknown>> {
  const output = runWrangler([
    "d1", "execute", database, "--remote", "--command", sql,
    "--json", "--yes", "--config", config,
  ], { quiet: true });
  return d1Rows(parseJsonOutput(output));
}

function executeD1(database: string, config: string, sql: string): void {
  runWrangler([
    "d1", "execute", database, "--remote", "--command", sql,
    "--json", "--yes", "--config", config,
  ], { quiet: true });
}

function resetBundlePath(stateDir: string, workspaceId: string): string {
  return join(stateDir, `emergency-reset-${workspaceId}.json`);
}

function validateResetBundle(value: unknown, expected: {
  workspaceId: string;
  endpoint: string;
  path: string;
}): EmergencyResetBundle {
  const bundle = value as EmergencyResetBundle;
  const unsignedBundle = bundle && typeof bundle === "object"
    ? Object.fromEntries(Object.entries(bundle).filter(([key]) => key !== "oneTimeLink")) as Omit<EmergencyResetBundle, "oneTimeLink">
    : undefined;
  if (!bundle || bundle.formatVersion !== 1 || bundle.workspaceId !== expected.workspaceId ||
      bundle.endpoint !== expected.endpoint || !UUID_PATTERN.test(bundle.resetId) ||
      !UUID_PATTERN.test(bundle.userId) || !UUID_PATTERN.test(bundle.instanceId) ||
      typeof bundle.handoffSecret !== "string" || Buffer.from(bundle.handoffSecret, "base64url").byteLength !== 32 ||
      typeof bundle.handoffSecretHash !== "string" ||
      bundle.handoffSecretHash !== createHash("sha256")
        .update(Buffer.from(bundle.handoffSecret, "base64url")).digest("base64url") ||
      !Number.isSafeInteger(bundle.requestedAt) || !Number.isSafeInteger(bundle.expiresAt) ||
      bundle.expiresAt <= bundle.requestedAt ||
      !unsignedBundle || bundle.oneTimeLink !== buildEmergencyHandoffLink(unsignedBundle)) {
    throw new Error(`Emergency reset handoff state is invalid: ${expected.path}`);
  }
  return bundle;
}

function loadOrCreateResetBundle(
  stateDir: string,
  details: {
    workspaceId: string;
    userId: string;
    instanceId: string;
    endpoint: string;
    ttlSeconds: number;
  },
): EmergencyResetBundle {
  const path = resetBundlePath(stateDir, details.workspaceId);
  if (existsSync(path)) {
    const mode = statSync(path).mode & 0o777;
    if ((mode & 0o077) !== 0) throw new Error(`Emergency handoff state permissions are too broad: ${path}`);
    return validateResetBundle(jsonFile(path), { workspaceId: details.workspaceId, endpoint: details.endpoint, path });
  }
  const requestedAt = Date.now();
  const partial = {
    formatVersion: 1 as const,
    resetId: randomUUID(),
    workspaceId: details.workspaceId,
    userId: workspaceUuid(details.userId),
    instanceId: workspaceUuid(details.instanceId),
    endpoint: details.endpoint,
    handoffSecret: secret(),
    handoffSecretHash: "",
    requestedAt,
    expiresAt: requestedAt + details.ttlSeconds * 1000,
  };
  partial.handoffSecretHash = createHash("sha256")
    .update(Buffer.from(partial.handoffSecret, "base64url")).digest("base64url");
  const bundle: EmergencyResetBundle = {
    ...partial,
    oneTimeLink: buildEmergencyHandoffLink(partial),
  };
  writeFileSync(path, `${JSON.stringify(bundle, null, 2)}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  chmodSync(path, 0o600);
  return bundle;
}

function exactInteger(row: Record<string, unknown>, name: string, minimum = 0): number {
  const value = Number(row[name]);
  if (!Number.isSafeInteger(value) || value < minimum) throw new Error(`Invalid D1 ${name}`);
  return value;
}

async function emergencyReset(args: Arguments): Promise<void> {
  assertKnownFlags(args, [
    "apply", "workspace", "confirm", "endpoint", "config", "database", "bucket",
    "worker", "state-dir", "handoff-ttl-seconds",
  ]);
  const workspaceId = workspaceUuid(requireFlag(args, "workspace"));
  const prefix = workspaceR2Prefix(workspaceId);
  const confirmation = emergencyResetConfirmation(workspaceId);
  const worker = safeName(flag(args, "worker", "shinsou-sync")!, "worker name");
  const stateDir = resolve(flag(args, "state-dir", defaultStateDir(worker))!);
  assertOutsideCheckout(stateDir, "Emergency reset state");
  const config = resolve(flag(args, "config", join(stateDir, "wrangler.generated.jsonc"))!);
  const database = safeName(flag(args, "database", "shinsou-sync")!, "database name");
  const bucket = safeName(flag(args, "bucket", "shinsou-sync-checkpoints")!, "bucket name");
  const ttlSeconds = Number(flag(args, "handoff-ttl-seconds", String(7 * 86_400)));
  if (!Number.isSafeInteger(ttlSeconds) || ttlSeconds < 300 || ttlSeconds > 7 * 86_400) {
    throw new Error("--handoff-ttl-seconds must be between 300 and 604800");
  }

  if (!requestedFlag(args, "apply")) {
    console.log(JSON.stringify({
      mode: "dry-run",
      operation: "emergency-reset",
      workspaceId,
      r2Prefix: prefix,
      database,
      bucket,
      config,
      stateDir,
      requiredConfirmation: confirmation,
      operations: [
        "verify private single-owner workspace scope",
        "atomically revoke credentials and clear only the exact workspace in D1",
        `delete and verify only R2 prefix ${prefix}`,
        "write a mode-0600 one-time App handoff outside the checkout",
      ],
    }, null, 2));
    console.log("Dry-run only. No Cloudflare command was run; add --apply, --endpoint, and the exact confirmation.");
    return;
  }
  if (flag(args, "confirm") !== confirmation) {
    throw new Error(`Refusing emergency reset: --confirm must exactly equal ${JSON.stringify(confirmation)}`);
  }
  const endpoint = normalizedEndpoint(requireFlag(args, "endpoint"));
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  chmodSync(stateDir, 0o700);

  const preview = queryD1(database, config, `
    SELECT w.workspace_id AS workspaceId, w.owner_user_id AS ownerUserId,
           w.status, w.active_key_epoch AS activeKeyEpoch,
           w.directory_generation AS directoryGeneration,
           w.head_seq AS headSeq, w.committed_bytes AS committedBytes,
           owner.instance_role AS ownerInstanceRole, installation.instance_id AS instanceId,
           (SELECT COUNT(*) FROM memberships m
             WHERE m.workspace_id = w.workspace_id AND m.status = 'active') AS activeMemberCount,
           (SELECT COUNT(*) FROM memberships other
             WHERE other.user_id = w.owner_user_id AND other.workspace_id <> w.workspace_id
               AND other.status = 'active') AS otherActiveWorkspaceCount
    FROM workspaces w
    JOIN users owner ON owner.user_id = w.owner_user_id
    CROSS JOIN installation
    WHERE w.workspace_id = '${workspaceId}'
  `);
  if (preview.length !== 1) throw new Error("Exact workspace was not found in the selected D1 database");
  const workspace = preview[0]!;
  if (exactInteger(workspace, "activeMemberCount") !== 1 ||
      exactInteger(workspace, "otherActiveWorkspaceCount") !== 0) {
    throw new Error("Emergency reset is limited to a single-owner identity that has no other active workspace");
  }
  const userId = workspaceUuid(String(workspace.ownerUserId));
  const instanceId = workspaceUuid(String(workspace.instanceId));

  const activeBefore = queryD1(database, config, `
    SELECT reset_id AS resetId, handoff_secret_hash AS handoffSecretHash, status
    FROM emergency_workspace_resets
    WHERE workspace_id = '${workspaceId}'
      AND status IN ('requested', 'purge_pending', 'handoff_ready')
    ORDER BY requested_at DESC LIMIT 1
  `)[0];
  const bundlePath = resetBundlePath(stateDir, workspaceId);
  if (activeBefore && !existsSync(bundlePath)) {
    throw new Error(`An active reset exists but its protected handoff file is unavailable: ${bundlePath}`);
  }
  const bundle = loadOrCreateResetBundle(stateDir, {
    workspaceId, userId, instanceId, endpoint, ttlSeconds,
  });
  if (activeBefore && (activeBefore.resetId !== bundle.resetId ||
      activeBefore.handoffSecretHash !== bundle.handoffSecretHash)) {
    throw new Error("Protected handoff state does not match the active D1 reset");
  }

  if (!activeBefore) {
    const previousKeyEpoch = exactInteger(workspace, "activeKeyEpoch", 1);
    const previousDirectoryGeneration = exactInteger(workspace, "directoryGeneration", 1);
    const previousHeadSeq = exactInteger(workspace, "headSeq");
    const previousCommittedBytes = exactInteger(workspace, "committedBytes");
    executeD1(database, config, `
      INSERT INTO emergency_workspace_resets(
        reset_id, workspace_id, owner_user_id, confirmation, r2_prefix,
        handoff_secret_hash, status, previous_key_epoch, previous_directory_generation,
        previous_head_seq, previous_committed_bytes, handoff_expires_at, requested_at
      ) VALUES (
        '${bundle.resetId}', '${workspaceId}', '${userId}', '${confirmation}', '${prefix}',
        '${bundle.handoffSecretHash}', 'requested', ${previousKeyEpoch},
        ${previousDirectoryGeneration}, ${previousHeadSeq}, ${previousCommittedBytes},
        ${bundle.expiresAt}, ${bundle.requestedAt}
      )
    `);
  }

  let reset = queryD1(database, config, `
    SELECT reset_id AS resetId, status, r2_prefix AS r2Prefix
    FROM emergency_workspace_resets WHERE reset_id = '${bundle.resetId}'
  `)[0];
  if (!reset || reset.r2Prefix !== prefix) throw new Error("D1 reset receipt is missing or has the wrong R2 prefix");

  if (reset.status === "purge_pending") {
    const deletedObjectCount = await withR2Bridge(bucket, async (bridge) => {
      const objects = await bridge.list(prefix);
      if (objects.some((object) => !object.key.startsWith(prefix))) {
        throw new Error("R2 returned an object outside the exact workspace prefix; refusing deletion");
      }
      for (const object of objects) await bridge.delete(object.key);
      const remaining = await bridge.list(prefix);
      if (remaining.length !== 0) {
        throw new Error(`R2 purge is incomplete (${remaining.length} objects remain); rerun the same command`);
      }
      return objects.length;
    });
    const purgedAt = Date.now();
    executeD1(database, config, `
      INSERT INTO emergency_workspace_reset_purges(
        reset_id, workspace_id, r2_prefix, deleted_object_count,
        verified_remaining_count, purged_at
      ) VALUES (
        '${bundle.resetId}', '${workspaceId}', '${prefix}', ${deletedObjectCount}, 0, ${purgedAt}
      )
    `);
    reset = queryD1(database, config, `
      SELECT reset_id AS resetId, status, r2_prefix AS r2Prefix
      FROM emergency_workspace_resets WHERE reset_id = '${bundle.resetId}'
    `)[0];
  }

  if (!reset || !["handoff_ready", "complete"].includes(String(reset.status))) {
    throw new Error("Emergency reset is not handoff-ready; R2 purge receipt was not committed");
  }
  console.log(`Emergency reset status: ${reset.status}`);
  console.log(`Protected one-time App handoff (mode 0600): ${bundlePath}`);
  console.log("The handoff token/link was not printed. Import the protected link in Shinsou X before it expires.");
}

function canonicalMetadata(value: Record<string, string>): string {
  return JSON.stringify(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)));
}

export function assertR2Inventory(expected: BackupObject[], actual: BridgeObject[]): void {
  if (actual.length !== expected.length) throw new Error("Restored R2 object count mismatch");
  for (let index = 0; index < expected.length; index += 1) {
    const wanted = expected[index]!;
    const stored = actual[index]!;
    if (stored.key !== wanted.key || stored.size !== wanted.byteSize ||
        canonicalMetadata(stored.httpMetadata) !== canonicalMetadata(wanted.httpMetadata) ||
        canonicalMetadata(stored.customMetadata) !== canonicalMetadata(wanted.customMetadata)) {
      throw new Error(`Restored R2 object or metadata mismatch: ${wanted.key}`);
    }
  }
}

async function assertEmptyD1(database: string, config: string): Promise<void> {
  const output = runWrangler([
    "d1", "execute", database, "--remote", "--command",
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE '_cf_%' LIMIT 1",
    "--json", "--yes", "--config", config,
  ], { quiet: true });
  if (d1Rows(parseJsonOutput(output)).length > 0) {
    throw new Error("Target D1 database is not empty; restore requires a newly-created empty database");
  }
}

async function restoreBackup(args: Arguments): Promise<void> {
  assertKnownFlags(args, [
    "apply", "input", "config", "database", "bucket", "confirm-empty-target", "confirm-empty-r2",
  ]);
  const input = resolve(requireFlag(args, "input"));
  const database = safeName(flag(args, "database", "shinsou-sync")!, "database name");
  const bucket = safeName(flag(args, "bucket", "shinsou-sync-checkpoints")!, "bucket name");
  const config = resolve(flag(args, "config", BASE_CONFIG)!);
  const manifest = verifyBackup(input);
  if (!requestedFlag(args, "apply")) {
    console.log(JSON.stringify({
      mode: "dry-run", operation: "restore", database, bucket, config,
      d1Sha256: manifest.database.sha256, r2Objects: manifest.r2.objectCount,
    }, null, 2));
    console.log("Backup verified. Add --apply and both exact empty-target confirmations to restore.");
    return;
  }
  if (flag(args, "confirm-empty-target") !== database || flag(args, "confirm-empty-r2") !== bucket) {
    throw new Error("Refusing restore: confirmations must exactly match the empty target database and R2 bucket names");
  }
  await assertEmptyD1(database, config);
  await withR2Bridge(bucket, async (bridge) => {
    const existing = await bridge.list();
    if (existing.length > 0) throw new Error(`Target R2 bucket is not empty (${existing.length} objects)`);
    // R2 goes first: a failed D1 import may leave harmless orphan ciphertext, while the reverse
    // order could expose checkpoint rows whose immutable objects do not exist yet.
    for (const object of manifest.r2.objects) {
      const data = readFileSync(resolve(input, object.file));
      await bridge.put(object, data);
    }
    const restored = await bridge.list();
    assertR2Inventory(manifest.r2.objects, restored);
    for (const object of manifest.r2.objects) {
      const data = await bridge.get(object.key);
      if (data.byteLength !== object.byteSize || sha256(data) !== object.sha256) {
        throw new Error(`Restored R2 object verification failed: ${object.key}`);
      }
    }
  });
  runWrangler([
    "d1", "execute", database, "--remote", "--yes", "--file",
    resolve(input, manifest.database.file), "--config", config,
  ]);
  console.log(`Restore completed: ${manifest.r2.objectCount} R2 objects and the verified D1 export.`);
}

async function main(): Promise<void> {
  const args = parseArguments(process.argv.slice(2));
  if (!args.command || args.command === "help" || args.flags.has("help")) {
    console.log(usage());
    return;
  }
  switch (args.command) {
    case "deploy": deploy(args); break;
    case "export": await exportBackup(args); break;
    case "verify-backup": {
      assertKnownFlags(args, ["input"]);
      const manifest = verifyBackup(requireFlag(args, "input"));
      console.log(`Backup verified: D1 + ${manifest.r2.objectCount} R2 objects.`);
      break;
    }
    case "restore": await restoreBackup(args); break;
    case "emergency-reset": await emergencyReset(args); break;
    default: throw new Error(`Unknown command: ${args.command}\n\n${usage()}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === SCRIPT_FILE) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : "Cloudflare operation failed");
    process.exitCode = 1;
  });
}
