export interface D1Result<T = unknown> {
  success: boolean;
  results?: T[];
  meta?: { changes?: number; last_row_id?: number };
  error?: string;
}

export interface D1PreparedStatement {
  bind(...values: unknown[]): D1PreparedStatement;
  first<T = Record<string, unknown>>(column?: string): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<D1Result<T>>;
  run<T = Record<string, unknown>>(): Promise<D1Result<T>>;
  raw<T = unknown[]>(): Promise<T[]>;
}

export interface D1Database {
  prepare(sql: string): D1PreparedStatement;
  batch<T = unknown>(statements: D1PreparedStatement[]): Promise<D1Result<T>[]>;
  exec(sql: string): Promise<D1Result>;
}

export interface R2ObjectBody {
  key: string;
  size: number;
  etag: string;
  customMetadata?: Record<string, string>;
  body: ReadableStream<Uint8Array>;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export interface R2Bucket {
  get(key: string): Promise<R2ObjectBody | null>;
  head(key: string): Promise<Omit<R2ObjectBody, "body" | "arrayBuffer"> | null>;
  put(
    key: string,
    value: ArrayBuffer | Uint8Array | ReadableStream<Uint8Array>,
    options?: {
      customMetadata?: Record<string, string>;
      httpMetadata?: Record<string, string>;
      onlyIf?: { etagDoesNotMatch?: string };
    },
  ): Promise<{ key: string; size: number; etag: string } | null>;
  delete(key: string): Promise<void>;
}

export interface DurableObjectId {
  toString(): string;
}

export interface DurableObjectStub {
  fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
}

export interface DurableObjectNamespace {
  idFromName(name: string): DurableObjectId;
  get(id: DurableObjectId): DurableObjectStub;
}

export interface DurableObjectStorage {
  get<T = unknown>(key: string): Promise<T | undefined>;
  put<T = unknown>(key: string, value: T): Promise<void>;
  delete(key: string): Promise<boolean>;
  setAlarm(timestamp: number | Date): Promise<void>;
  getAlarm(): Promise<number | null>;
  acceptWebSocket(socket: WebSocket, tags?: string[]): void;
  getWebSockets(tag?: string): WebSocket[];
}

export interface DurableObjectState {
  id: DurableObjectId;
  storage: DurableObjectStorage;
  waitUntil(promise: Promise<unknown>): void;
  blockConcurrencyWhile<T>(callback: () => Promise<T>): Promise<T>;
}

export interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

export interface Env {
  DB: D1Database;
  CHECKPOINTS: R2Bucket;
  WORKSPACE_HUB: DurableObjectNamespace;
  BOOTSTRAP_SECRET: string;
  TOKEN_PEPPER: string;
  RATE_LIMIT_SALT: string;
  INSTANCE_ID: string;
  PROTOCOL_VERSION?: string;
  MIN_READER_VERSION?: string;
  MIN_WRITER_VERSION?: string;
  SCHEMA_VERSION?: string;
  MIN_SCHEMA_READER_VERSION?: string;
  MIN_SCHEMA_WRITER_VERSION?: string;
  REALTIME_ENABLED?: string;
  CAPABILITY_TTL_SECONDS?: string;
  ACCESS_TOKEN_TTL_SECONDS?: string;
  CHECKPOINT_SELF_ACK_GRACE_SECONDS?: string;
  CHECKPOINT_GC_SAFETY_SECONDS?: string;
  ALLOWED_ORIGINS?: string;
}

export interface AccessPrincipal {
  accessTokenId: string;
  deviceId: string;
  userId: string;
  instanceRole: "admin" | "user";
  deviceAuthEpoch: number;
  signingPublicKey: string;
  expiresAt: number;
}

export interface CapabilityPrincipal {
  capabilityId: string;
  workspaceId: string;
  deviceId: string;
  userId: string;
  role: "owner" | "editor" | "reader";
  deviceAuthEpoch: number;
  membershipAuthEpoch: number;
  keyEpoch: number;
  signingPublicKey: string;
  expiresAt: number;
}

export interface RequestBody<T = Record<string, unknown>> {
  raw: Uint8Array;
  value: T;
}
