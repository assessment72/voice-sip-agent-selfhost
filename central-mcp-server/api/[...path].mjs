import { createCentralMcpServer } from "../server.mjs";

// Vercel rewrites /mcp, /healthz and /v1/agent to /api/*.
// Live gateway state is currently instance-local; see README before production use.
const central = createCentralMcpServer({ basePath: "/api" });

export default central.httpServer;
