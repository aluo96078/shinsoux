interface Env {
  CHECKPOINTS: {
    list(options: { cursor?: string; prefix?: string; limit: number; include: string[] }): Promise<{
      objects: Array<{
        key: string;
        size: number;
        httpMetadata?: Record<string, string>;
        customMetadata?: Record<string, string>;
      }>;
      truncated: boolean;
      cursor?: string;
    }>;
    get(key: string): Promise<{ body: ReadableStream; size: number } | null>;
    put(key: string, body: ReadableStream | ArrayBuffer, options: {
      httpMetadata: Record<string, string | Date>;
      customMetadata: Record<string, string>;
    }): Promise<unknown>;
    delete(key: string): Promise<void>;
  };
  OPS_TOKEN: string;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function metadata(request: Request, name: string): Record<string, string> {
  const encoded = request.headers.get(name);
  if (!encoded || encoded.length > 24 * 1024) throw new Error("metadata_invalid");
  const normalized = encoded.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(encoded.length / 4) * 4, "=");
  const bytes = Uint8Array.from(atob(normalized), (character) => character.charCodeAt(0));
  const value = JSON.parse(new TextDecoder().decode(bytes)) as unknown;
  if (!value || Array.isArray(value) || typeof value !== "object") throw new Error("metadata_invalid");
  const output: Record<string, string> = {};
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    if (!/^[A-Za-z][A-Za-z0-9-]{0,63}$/.test(key) || typeof entry !== "string" || /[\r\n]/.test(entry)) {
      throw new Error("metadata_invalid");
    }
    output[key] = entry;
  }
  return output;
}

function objectKey(url: URL): string {
  const key = url.searchParams.get("key") ?? "";
  if (!key || new TextEncoder().encode(key).byteLength > 1024 || key.startsWith("/") || key.includes("\\") ||
      /[\u0000-\u001f\u007f]/.test(key) ||
      key.split("/").some((part) => part === "" || part === "." || part === "..")) {
    throw new Error("key_invalid");
  }
  return key;
}

function workspacePrefix(url: URL): string | undefined {
  const prefix = url.searchParams.get("prefix") ?? undefined;
  if (prefix === undefined) return undefined;
  if (!/^workspaces\/[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\/$/.test(prefix)) {
    throw new Error("prefix_invalid");
  }
  return prefix;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!env.OPS_TOKEN || request.headers.get("Authorization") !== `Bearer ${env.OPS_TOKEN}`) {
      return json({ error: "unauthorized" }, 401);
    }
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/list") {
        const cursor = url.searchParams.get("cursor") ?? undefined;
        const prefix = workspacePrefix(url);
        const page = await env.CHECKPOINTS.list({
          cursor,
          prefix,
          limit: 1000,
          include: ["httpMetadata", "customMetadata"],
        });
        return json({
          objects: page.objects.map((object) => ({
            key: object.key,
            size: object.size,
            httpMetadata: object.httpMetadata ?? {},
            customMetadata: object.customMetadata ?? {},
          })),
          truncated: page.truncated,
          cursor: page.cursor,
        });
      }
      if (request.method === "GET" && url.pathname === "/object") {
        const object = await env.CHECKPOINTS.get(objectKey(url));
        if (!object) return json({ error: "not_found" }, 404);
        return new Response(object.body, {
          headers: {
            "Content-Type": "application/octet-stream",
            "Content-Length": String(object.size),
            "Cache-Control": "no-store",
          },
        });
      }
      if (request.method === "PUT" && url.pathname === "/object") {
        const lengthHeader = request.headers.get("Content-Length");
        const length = Number(lengthHeader);
        if (lengthHeader === null || !Number.isSafeInteger(length) || length < 0 || length > 32 * 1024 * 1024) {
          return json({ error: "body_size_invalid" }, 413);
        }
        const httpMetadata: Record<string, string | Date> = metadata(request, "X-Shinsou-Http-Metadata");
        if (typeof httpMetadata.cacheExpiry === "string") {
          const parsed = new Date(httpMetadata.cacheExpiry);
          if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== httpMetadata.cacheExpiry) {
            throw new Error("metadata_invalid");
          }
          httpMetadata.cacheExpiry = parsed;
        }
        await env.CHECKPOINTS.put(objectKey(url), request.body ?? new ArrayBuffer(0), {
          httpMetadata,
          customMetadata: metadata(request, "X-Shinsou-Custom-Metadata"),
        });
        return json({ stored: true });
      }
      if (request.method === "DELETE" && url.pathname === "/object") {
        await env.CHECKPOINTS.delete(objectKey(url));
        return json({ deleted: true });
      }
      return json({ error: "not_found" }, 404);
    } catch {
      return json({ error: "request_invalid" }, 400);
    }
  },
};
