import { createCentralMcpServer } from "../server.mjs";

const central = createCentralMcpServer({ basePath: "/api", requirePersistentStore: true });

export default central.httpServer;
