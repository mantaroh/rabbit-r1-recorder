/**
 * MCP endpoint so the agent can reach the lifelog as tools.
 *
 * This is the piece that makes "さっきの話" work without any special-casing in
 * the client: the R1 sends an ordinary prompt, and the agent decides on its own
 * to look up what was being said. No dedicated query API, no context plumbing
 * through the device.
 *
 * Streamable HTTP transport, which is plain JSON-RPC 2.0 over POST — the same
 * shape the Hermes gateway already speaks, so no SDK is needed for three tools.
 */

export interface McpDeps {
  context(atIso: string, beforeSec: number): Promise<unknown>;
  search(query: string, limit: number): Promise<unknown>;
  stats(): Promise<unknown>;
}

const PROTOCOL_VERSION = "2025-06-18";

const TOOLS = [
  {
    name: "lifelog_recent",
    description:
      "Read what was said out loud near a moment in time, from the always-on " +
      "recording on the user's Rabbit R1. Use this to resolve references to " +
      "recent speech — 'さっきの話', 'これ', 'the thing we just discussed' — " +
      "before answering. Defaults to the two minutes before now.",
    inputSchema: {
      type: "object",
      properties: {
        at: {
          type: "string",
          description:
            "ISO 8601 instant to look back from. Omit for 'now'.",
        },
        before_sec: {
          type: "number",
          description: "How many seconds to look back. Default 120.",
        },
      },
    },
  },
  {
    name: "lifelog_search",
    description:
      "Full-text search over everything the user has been recorded saying. " +
      "Use for 'when did I talk about X' or to recover a detail from an " +
      "earlier conversation. Returns matching passages with timestamps.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Search terms." },
        limit: { type: "number", description: "Max results. Default 20." },
      },
      required: ["query"],
    },
  },
  {
    name: "lifelog_stats",
    description:
      "How much has been captured and whether anything is stuck: segment " +
      "counts by status, total bytes, and the time range covered.",
    inputSchema: { type: "object", properties: {} },
  },
];

export async function handleMcp(request: Request, deps: McpDeps): Promise<Response> {
  if (request.method !== "POST") {
    return jsonRpcResponse({ error: { code: -32600, message: "POST required" } }, 405);
  }

  let message: any;
  try {
    message = await request.json();
  } catch {
    return jsonRpcResponse({ id: null, error: { code: -32700, message: "parse error" } });
  }

  // Notifications carry no id and expect no reply.
  const id = message?.id ?? null;
  const method = message?.method;

  try {
    switch (method) {
      case "initialize":
        return jsonRpcResponse({
          id,
          result: {
            protocolVersion: PROTOCOL_VERSION,
            capabilities: { tools: {} },
            serverInfo: { name: "r1-lifelog", version: "0.1.0" },
          },
        });

      case "notifications/initialized":
        return new Response(null, { status: 202 });

      case "tools/list":
        return jsonRpcResponse({ id, result: { tools: TOOLS } });

      case "tools/call": {
        const name = message?.params?.name;
        const args = message?.params?.arguments ?? {};
        const payload = await callTool(name, args, deps);
        return jsonRpcResponse({
          id,
          result: {
            // Agents read text; the JSON is the text.
            content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
          },
        });
      }

      case "ping":
        return jsonRpcResponse({ id, result: {} });

      default:
        return jsonRpcResponse({
          id,
          error: { code: -32601, message: `unknown method: ${method}` },
        });
    }
  } catch (error) {
    return jsonRpcResponse({
      id,
      // Tool failures come back as a protocol error rather than a thrown 500 so
      // the agent sees a message it can reason about instead of a dead server.
      error: { code: -32603, message: String(error).slice(0, 300) },
    });
  }
}

async function callTool(name: string, args: any, deps: McpDeps): Promise<unknown> {
  switch (name) {
    case "lifelog_recent":
      return deps.context(
        typeof args.at === "string" ? args.at : new Date().toISOString(),
        Number.isFinite(args.before_sec) ? Math.trunc(args.before_sec) : 120,
      );
    case "lifelog_search":
      if (typeof args.query !== "string" || !args.query.trim()) {
        throw new Error("query is required");
      }
      return deps.search(
        args.query,
        Number.isFinite(args.limit) ? Math.trunc(args.limit) : 20,
      );
    case "lifelog_stats":
      return deps.stats();
    default:
      throw new Error(`unknown tool: ${name}`);
  }
}

function jsonRpcResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", ...body }), {
    status,
    headers: { "content-type": "application/json" },
  });
}
