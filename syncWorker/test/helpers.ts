import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import type { D1Database, D1PreparedStatement, D1Result } from "../src/runtime.ts";

export const IDS = Object.freeze({
  instance: "00000000-0000-4000-8000-000000000001",
  userA: "00000000-0000-4000-8000-000000000002",
  userB: "00000000-0000-4000-8000-000000000003",
  deviceA: "00000000-0000-4000-8000-000000000004",
  deviceA2: "00000000-0000-4000-8000-000000000005",
  deviceB: "00000000-0000-4000-8000-000000000006",
  workspaceA: "00000000-0000-4000-8000-000000000007",
  workspaceB: "00000000-0000-4000-8000-000000000008",
  capabilityA: "00000000-0000-4000-8000-000000000009",
  capabilityB: "00000000-0000-4000-8000-00000000000a",
  eventA: "00000000-0000-4000-8000-00000000000b",
  eventB: "00000000-0000-4000-8000-00000000000c",
  checkpointA: "00000000-0000-4000-8000-00000000000d",
  checkpointB: "00000000-0000-4000-8000-00000000000e",
  rotationA: "00000000-0000-4000-8000-00000000000f",
  invite: "00000000-0000-4000-8000-000000000010",
  challenge: "00000000-0000-4000-8000-000000000011",
  claim: "00000000-0000-4000-8000-000000000012",
});

export function database(): DatabaseSync {
  const db = new DatabaseSync(":memory:");
  const migrationsDirectory = fileURLToPath(new URL("../migrations/", import.meta.url));
  const migrations = readdirSync(migrationsDirectory)
    .filter((name) => /^\d{4}_[a-z0-9_]+\.sql$/.test(name))
    .sort();
  for (const migration of migrations) {
    db.exec(readFileSync(fileURLToPath(new URL(`../migrations/${migration}`, import.meta.url)), "utf8"));
  }
  return db;
}

export function numberedGet(
  db: DatabaseSync,
  sql: string,
  ...values: unknown[]
): Record<string, unknown> | undefined {
  const bindings: Record<string, unknown> = {};
  for (let index = 0; index < values.length; index += 1) bindings[`?${index + 1}`] = values[index];
  return db.prepare(sql).get(bindings) as Record<string, unknown> | undefined;
}

class SqliteD1Statement implements D1PreparedStatement {
  private readonly database: DatabaseSync;
  private readonly sql: string;
  private values: unknown[] = [];

  constructor(database: DatabaseSync, sql: string) {
    this.database = database;
    this.sql = sql;
  }

  bind(...values: unknown[]): D1PreparedStatement {
    const statement = new SqliteD1Statement(this.database, this.sql);
    statement.values = values;
    return statement;
  }

  private bindings(): Record<string, unknown> {
    const bindings: Record<string, unknown> = {};
    this.values.forEach((value, index) => { bindings[`?${index + 1}`] = value; });
    return bindings;
  }

  async first<T = Record<string, unknown>>(column?: string): Promise<T | null> {
    const row = this.database.prepare(this.sql).get(this.bindings()) as Record<string, unknown> | undefined;
    if (!row) return null;
    return (column ? row[column] : row) as T;
  }

  async all<T = Record<string, unknown>>(): Promise<D1Result<T>> {
    const results = this.database.prepare(this.sql).all(this.bindings()) as T[];
    return { success: true, results };
  }

  async run<T = Record<string, unknown>>(): Promise<D1Result<T>> {
    const result = this.database.prepare(this.sql).run(this.bindings());
    return { success: true, meta: { changes: Number(result.changes), last_row_id: Number(result.lastInsertRowid) } };
  }

  async raw<T = unknown[]>(): Promise<T[]> {
    return this.database.prepare(this.sql).all(this.bindings()).map((row) => Object.values(row) as T);
  }
}

export function sqliteD1(db: DatabaseSync): D1Database {
  return {
    prepare(sql: string) {
      return new SqliteD1Statement(db, sql);
    },
    async batch(statements: D1PreparedStatement[]) {
      db.exec("BEGIN IMMEDIATE");
      try {
        const results = [];
        for (const statement of statements) results.push(await statement.run());
        db.exec("COMMIT");
        return results;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    },
    async exec(sql: string) {
      db.exec(sql);
      return { success: true };
    },
  };
}

export function seedTenant(db: DatabaseSync, now = 1_000_000): void {
  db.prepare(`INSERT INTO installation(instance_id, schema_version, initialized_at, signup_mode)
    VALUES (?, 1, ?, 'invite_only')`).run(IDS.instance, now);
  db.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
    VALUES (?, 'A', 'admin', 'active', 'private-default', ?)`)
    .run(IDS.userA, now);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'A phone', 'ios', 'sign-a', 'wrap-a', 'token-a', 'active', 1, ?)`)
    .run(IDS.deviceA, IDS.userA, now);
  db.prepare(`INSERT INTO workspaces(workspace_id, owner_user_id, status, active_key_epoch, created_at)
    VALUES (?, ?, 'active', 1, ?)`)
    .run(IDS.workspaceA, IDS.userA, now);
  db.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
    VALUES (?, ?, 'owner', 'active', 1)`)
    .run(IDS.workspaceA, IDS.userA);
  db.prepare(`INSERT INTO recovery_credentials(
    user_id, signing_public_key, wrapping_public_key, auth_epoch, rotated_at
  ) VALUES (?, 'recovery-sign-a', 'recovery-wrap-a', 1, ?)`)
    .run(IDS.userA, now);
  db.prepare(`INSERT INTO workspace_capabilities(
    capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
    membership_auth_epoch, key_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 'cap-token-a', 1, 1, 1, ?, ?)`)
    .run(IDS.capabilityA, IDS.workspaceA, IDS.deviceA, now + 1_000_000, now);
}

export function seedSecondTenant(db: DatabaseSync, now = 1_000_000): void {
  db.prepare(`INSERT INTO users(user_id, display_name, instance_role, status, quota_profile_id, created_at)
    VALUES (?, 'B', 'user', 'active', 'private-default', ?)`)
    .run(IDS.userB, now);
  db.prepare(`INSERT INTO devices(
    device_id, user_id, display_name, platform, signing_public_key, wrapping_public_key,
    token_hash, status, auth_epoch, created_at
  ) VALUES (?, ?, 'B desktop', 'windows', 'sign-b', 'wrap-b', 'token-b', 'active', 1, ?)`)
    .run(IDS.deviceB, IDS.userB, now);
  db.prepare(`INSERT INTO workspaces(workspace_id, owner_user_id, status, active_key_epoch, created_at)
    VALUES (?, ?, 'active', 1, ?)`)
    .run(IDS.workspaceB, IDS.userB, now);
  db.prepare(`INSERT INTO memberships(workspace_id, user_id, role, status, auth_epoch)
    VALUES (?, ?, 'owner', 'active', 1)`)
    .run(IDS.workspaceB, IDS.userB);
  db.prepare(`INSERT INTO workspace_capabilities(
    capability_id, workspace_id, device_id, token_hash, device_auth_epoch,
    membership_auth_epoch, key_epoch, expires_at, created_at
  ) VALUES (?, ?, ?, 'cap-token-b', 1, 1, 1, ?, ?)`)
    .run(IDS.capabilityB, IDS.workspaceB, IDS.deviceB, now + 1_000_000, now);
}
