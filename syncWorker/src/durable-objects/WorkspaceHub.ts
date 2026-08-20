import { ApiError, errorResponse, jsonResponse, readJson } from "../http.ts";
import type {
  CapabilityPrincipal,
  DurableObjectState,
  Env,
} from "../runtime.ts";
import { appendEvent } from "../api/events.ts";
import { first } from "../storage/d1.ts";
import { LIMITS } from "../protocol.ts";

declare const WebSocketPair: {
  new(): { 0: WebSocket; 1: WebSocket };
};

interface SocketAttachment {
  workspaceId: string;
  deviceId: string;
  capabilityId: string;
  expiresAt: number;
  cursor: number;
}

interface Bucket {
  tokens: number;
  updatedAt: number;
}

function internalPrincipal(request: Request): CapabilityPrincipal {
  const headers = request.headers;
  const required = (name: string): string => {
    const value = headers.get(name);
    if (!value) throw new ApiError(403, "internal_principal_missing");
    return value;
  };
  return {
    capabilityId: required("X-Shinsou-Internal-Capability"),
    workspaceId: required("X-Shinsou-Internal-Workspace"),
    deviceId: required("X-Shinsou-Internal-Device"),
    userId: required("X-Shinsou-Internal-User"),
    role: required("X-Shinsou-Internal-Role") as CapabilityPrincipal["role"],
    deviceAuthEpoch: Number(required("X-Shinsou-Internal-Device-Epoch")),
    membershipAuthEpoch: Number(required("X-Shinsou-Internal-Membership-Epoch")),
    keyEpoch: Number(required("X-Shinsou-Internal-Key-Epoch")),
    signingPublicKey: required("X-Shinsou-Internal-Signing-Key"),
    expiresAt: Number(required("X-Shinsou-Internal-Expires")),
  };
}

function attachment(socket: WebSocket): SocketAttachment | null {
  const candidate = socket as WebSocket & { deserializeAttachment?: () => SocketAttachment | null };
  return candidate.deserializeAttachment?.() ?? null;
}

function saveAttachment(socket: WebSocket, value: SocketAttachment): void {
  const candidate = socket as WebSocket & { serializeAttachment?: (entry: SocketAttachment) => void };
  candidate.serializeAttachment?.(value);
}

export class WorkspaceHub {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  private appendQueue: Promise<void> = Promise.resolve();
  private readonly deviceBuckets = new Map<string, Bucket>();
  private workspaceBucket: Bucket = { tokens: 20, updatedAt: Date.now() };

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (request.method === "POST" && url.pathname === "/append") {
        return await this.serializedAppend(request);
      }
      if (request.method === "GET" && url.pathname === "/stream") {
        return await this.openStream(request);
      }
      if (request.method === "POST" && url.pathname === "/invalidate") {
        return await this.invalidate(request);
      }
      if (request.method === "POST" && url.pathname === "/checkpoint") {
        const { value } = await readJson(request, 8 * 1024);
        this.broadcast({
          type: "checkpointAvailable",
          ...value,
        });
        return jsonResponse({ notified: true });
      }
      return jsonResponse({ error: { code: "not_found" } }, 404);
    } catch (error) {
      return errorResponse(error);
    }
  }

  private serializedAppend(request: Request): Promise<Response> {
    let resolveResult!: (response: Response) => void;
    const result = new Promise<Response>((resolve) => { resolveResult = resolve; });
    const run = async () => {
      try {
        const principal = internalPrincipal(request);
        this.consumeRate(principal.deviceId, Date.now());
        const broadcastEnvelope = (await readJson(request.clone(), 64 * 1024)).value;
        const receipt = await appendEvent(request, this.env, principal);
        if (!receipt.duplicate) {
          this.broadcast({ type: "event", workspaceSeq: receipt.workspaceSeq, envelope: broadcastEnvelope });
        }
        resolveResult(jsonResponse(receipt, receipt.duplicate ? 200 : 201));
      } catch (error) {
        resolveResult(errorResponse(error));
      }
    };
    this.appendQueue = this.appendQueue.then(run, run).then(() => undefined);
    return result;
  }

  private refill(bucket: Bucket, now: number, ratePerMinute: number, burst: number): void {
    const elapsed = Math.max(0, now - bucket.updatedAt);
    bucket.tokens = Math.min(burst, bucket.tokens + elapsed * ratePerMinute / 60_000);
    bucket.updatedAt = now;
  }

  private consumeRate(deviceId: string, now: number): void {
    const device = this.deviceBuckets.get(deviceId) ?? { tokens: 20, updatedAt: now };
    this.refill(device, now, LIMITS.eventDevicePerMinute, 20);
    this.refill(this.workspaceBucket, now, LIMITS.eventWorkspacePerMinute, 60);
    if (device.tokens < 1 || this.workspaceBucket.tokens < 1) throw new ApiError(429, "rate_limited");
    device.tokens -= 1;
    this.workspaceBucket.tokens -= 1;
    this.deviceBuckets.set(deviceId, device);
  }

  private async openStream(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      throw new ApiError(426, "websocket_upgrade_required");
    }
    const principal = internalPrincipal(request);
    if (principal.expiresAt <= Date.now()) throw new ApiError(401, "capability_expired");
    const url = new URL(request.url);
    const cursorValue = url.searchParams.get("cursor") ?? "0";
    if (!/^\d+$/.test(cursorValue) || !Number.isSafeInteger(Number(cursorValue))) {
      throw new ApiError(400, "invalid_cursor");
    }
    const cursor = Number(cursorValue);
    const sockets = this.state.storage.getWebSockets();
    if (sockets.length >= LIMITS.workspaceSockets) throw new ApiError(429, "workspace_socket_limit");
    const deviceSocketCount = sockets.filter((socket) => attachment(socket)?.deviceId === principal.deviceId).length;
    if (deviceSocketCount >= LIMITS.deviceSockets) throw new ApiError(429, "device_socket_limit");
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.state.storage.acceptWebSocket(server, [`device:${principal.deviceId}`]);
    saveAttachment(server, {
      workspaceId: principal.workspaceId,
      deviceId: principal.deviceId,
      capabilityId: principal.capabilityId,
      expiresAt: principal.expiresAt,
      cursor,
    });
    const workspace = await first<{
      headSeq: number;
      gcBeforeSeq: number;
      stableCheckpointSeq: number | null;
    }>(this.env.DB, `
      SELECT w.head_seq AS headSeq, w.gc_before_seq AS gcBeforeSeq,
             c.through_seq AS stableCheckpointSeq
      FROM workspaces w LEFT JOIN checkpoints c
        ON c.workspace_id = w.workspace_id AND c.checkpoint_id = w.stable_checkpoint_id
      WHERE w.workspace_id = ?1
    `, principal.workspaceId);
    server.send(JSON.stringify({
      type: "hello",
      headSeq: workspace?.headSeq ?? 0,
      stableCheckpointSeq: workspace?.stableCheckpointSeq ?? 0,
      capabilityExpiresAt: principal.expiresAt,
    }));
    if (workspace && cursor < workspace.gcBeforeSeq) {
      server.send(JSON.stringify({
        type: "resyncRequired",
        stableCheckpointSeq: workspace.stableCheckpointSeq ?? 0,
        headSeq: workspace.headSeq,
      }));
    }
    const currentAlarm = await this.state.storage.getAlarm();
    if (currentAlarm === null || currentAlarm > principal.expiresAt) {
      await this.state.storage.setAlarm(principal.expiresAt);
    }
    return new Response(null, { status: 101, webSocket: client } as ResponseInit);
  }

  private async invalidate(request: Request): Promise<Response> {
    const { value } = await readJson(request, 8 * 1024);
    const targetDeviceId = typeof value.targetDeviceId === "string" ? value.targetDeviceId : null;
    let disconnected = 0;
    for (const socket of this.state.storage.getWebSockets()) {
      const state = attachment(socket);
      if (!targetDeviceId || state?.deviceId === targetDeviceId) {
        try {
          socket.send(JSON.stringify({ type: "reauthRequired" }));
          socket.close(4001, "capability invalidated");
        } catch {
          // A concurrently closed hibernated socket needs no further work.
        }
        disconnected += 1;
      }
    }
    return jsonResponse({ disconnected });
  }

  private broadcast(message: unknown): void {
    const serialized = JSON.stringify(message);
    for (const socket of this.state.storage.getWebSockets()) {
      try {
        socket.send(serialized);
      } catch {
        try { socket.close(1011, "send failed"); } catch { /* already closed */ }
      }
    }
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string" || message.length > 4096) {
      socket.close(1008, "invalid message");
      return;
    }
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(message);
    } catch {
      socket.close(1008, "invalid json");
      return;
    }
    if (parsed.type === "ping") {
      socket.send(JSON.stringify({ type: "pong", now: Date.now() }));
      return;
    }
    if (parsed.type === "ack" && Number.isSafeInteger(parsed.workspaceSeq)) {
      const current = attachment(socket);
      if (current) {
        current.cursor = Math.max(current.cursor, parsed.workspaceSeq as number);
        saveAttachment(socket, current);
        const workspace = await first<{
          gcBeforeSeq: number;
          headSeq: number;
          stableCheckpointSeq: number | null;
        }>(this.env.DB, `
          SELECT w.gc_before_seq AS gcBeforeSeq, w.head_seq AS headSeq,
                 c.through_seq AS stableCheckpointSeq
          FROM workspaces w LEFT JOIN checkpoints c
            ON c.workspace_id = w.workspace_id AND c.checkpoint_id = w.stable_checkpoint_id
          WHERE w.workspace_id = ?1
        `, current.workspaceId);
        if (workspace && current.cursor < workspace.gcBeforeSeq) {
          socket.send(JSON.stringify({
            type: "resyncRequired",
            stableCheckpointSeq: workspace.stableCheckpointSeq ?? 0,
            headSeq: workspace.headSeq,
          }));
        }
      }
      return;
    }
    socket.close(1008, "unsupported message");
  }

  async webSocketClose(): Promise<void> {
    // Hibernatable sockets are removed by the runtime.
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    try { socket.close(1011, "socket error"); } catch { /* already closed */ }
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    let next: number | null = null;
    for (const socket of this.state.storage.getWebSockets()) {
      const state = attachment(socket);
      if (!state || state.expiresAt <= now) {
        try {
          socket.send(JSON.stringify({ type: "reauthRequired" }));
          socket.close(4001, "capability expired");
        } catch { /* already closed */ }
      } else {
        next = next === null ? state.expiresAt : Math.min(next, state.expiresAt);
      }
    }
    if (next !== null) await this.state.storage.setAlarm(next);
  }
}
