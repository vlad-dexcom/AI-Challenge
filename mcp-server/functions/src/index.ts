import { onRequest } from "firebase-functions/v2/https";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { Request, Response } from "express";
import { registerGetExerciseInfo } from "./tools/getExerciseInfo.js";
import { registerSuggestWorkout } from "./tools/suggestWorkout.js";

/** Builds a fresh MCP server instance with all tools registered. A new instance is created per
 * request (stateless mode): Cloud Functions instances are not guaranteed to be reused between
 * invocations, so there is no point keeping session state around on the server side. */
function buildMcpServer(): McpServer {
  const server = new McpServer({
    name: "personal-trainer-mcp-server",
    version: "1.0.0",
  });

  registerGetExerciseInfo(server);
  registerSuggestWorkout(server);

  return server;
}

/**
 * HTTPS Cloud Function exposing an MCP server over Streamable HTTP (stateless mode, per the MCP
 * spec: https://modelcontextprotocol.io/specification). Wraps the public wger.de exercise
 * database as `get_exercise_info` and `suggest_workout` tools.
 */
export const mcp = onRequest(
  { region: "us-central1", cors: true, invoker: "public" },
  async (req: Request, res: Response) => {
    if (req.method !== "POST") {
      res.status(405).json({
        jsonrpc: "2.0",
        error: { code: -32000, message: "Method not allowed. Use POST." },
        id: null,
      });
      return;
    }

    const server = buildMcpServer();
    try {
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined,
      });
      res.on("close", () => {
        transport.close();
        server.close();
      });
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (error) {
      console.error("Error handling MCP request:", error);
      if (!res.headersSent) {
        res.status(500).json({
          jsonrpc: "2.0",
          error: { code: -32603, message: "Internal server error" },
          id: null,
        });
      }
    }
  }
);
