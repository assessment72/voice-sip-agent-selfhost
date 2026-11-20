import { MongoClient } from "mongodb";
import { randomUUID } from "node:crypto";

export class MongoGatewayStore {
  constructor({ uri, database, clientOptions = {} }) {
    if (!uri) throw new Error("MONGODB_URI is required for persistent gateway storage");
    if (!database) throw new Error("MONGODB_DATABASE is required");
    this.client = new MongoClient(uri, { maxPoolSize: 5, minPoolSize: 0, maxIdleTimeMS: 60_000, serverSelectionTimeoutMS: 10_000, ...clientOptions });
    this.databaseName = database;
    this.ready = null;
  }
  async connect() {
    if (!this.ready) this.ready = (async () => {
      await this.client.connect();
      await this.client.db(this.databaseName).command({ ping: 1 });
      return this.client.db(this.databaseName);
    })().catch(error => { this.ready = null; throw error; });
    return this.ready;
  }
  async close() { await this.client.close(); this.ready = null; }
  async health() { const db = await this.connect(); await db.command({ ping: 1 }); return true; }
  async registerGateway(doc) {
    const db = await this.connect();
    await db.collection("gateways").updateOne(
      { gateway_id: doc.gateway_id },
      { $set: { ...doc, connected: true }, $setOnInsert: { created_at: new Date() } },
      { upsert: true }
    );
  }
  async heartbeat(gatewayId, instanceId, metadata, lastSeen) {
    const db = await this.connect();
    await db.collection("gateways").updateOne(
      { gateway_id: gatewayId, instance_id: instanceId, connected: true },
      { $set: { metadata, last_seen: lastSeen } }
    );
  }
  async disconnectGateway(gatewayId, instanceId, disconnectedAt) {
    const db = await this.connect();
    await db.collection("gateways").updateOne(
      { gateway_id: gatewayId, instance_id: instanceId },
      { $set: { connected: false, disconnected_at: disconnectedAt, last_seen: disconnectedAt } }
    );
  }
  async listGateways() {
    const db = await this.connect();
    return db.collection("gateways").find({}, { projection: { _id: 0 } }).toArray();
  }
  async getGateway(gatewayId) {
    const db = await this.connect();
    return db.collection("gateways").findOne({ gateway_id: gatewayId }, { projection: { _id: 0 } });
  }
  async markAssigned(gatewayId, assignedAt) {
    const db = await this.connect();
    await db.collection("gateways").updateOne({ gateway_id: gatewayId }, { $set: { last_assigned_at: assignedAt } });
  }
  async acquireLease(gatewayId, owner, ttlMs = 90_000) {
    const db = await this.connect();
    const now = new Date();
    const leaseToken = randomUUID();
    try {
      const result = await db.collection("leases").findOneAndUpdate(
        { gateway_id: gatewayId, $or: [{ expires_at: { $lte: now } }, { owner }] },
        { $set: { gateway_id: gatewayId, owner, lease_token: leaseToken, acquired_at: now, expires_at: new Date(now.getTime() + ttlMs) } },
        { upsert: true, returnDocument: "after" }
      );
      return result && (result.lease_token === leaseToken || result.value?.lease_token === leaseToken) ? leaseToken : null;
    } catch (error) {
      if (error?.code === 11000) return null;
      throw error;
    }
  }
  async getLease(gatewayId) {
    const db = await this.connect();
    return db.collection("leases").findOne({ gateway_id: gatewayId, expires_at: { $gt: new Date() } }, { projection: { _id: 0 } });
  }
  async releaseLease(gatewayId, leaseToken) {
    const db = await this.connect();
    await db.collection("leases").deleteOne({ gateway_id: gatewayId, lease_token: leaseToken });
  }
  async createCommand({ gatewayId, instanceId, name, args, timeoutMs }) {
    const db = await this.connect();
    const commandId = `cmd_${randomUUID()}`;
    const now = new Date();
    const doc = {
      command_id: commandId, gateway_id: gatewayId, instance_id: instanceId,
      name, arguments: args, status: "queued", created_at: now,
      expires_at: new Date(now.getTime() + Math.max(timeoutMs * 3, 60_000))
    };
    await db.collection("commands").insertOne(doc);
    await db.collection("audit_events").insertOne({
      event_id: randomUUID(), gateway_id: gatewayId, event_type: "command_queued",
      command_id: commandId, command_name: name, created_at: now
    });
    return commandId;
  }
  async claimCommand(commandId) {
    const db = await this.connect();
    const claimed = await db.collection("commands").updateOne({ command_id: commandId, status: "queued" }, { $set: { status: "delivered", delivered_at: new Date() } });
    return claimed.modifiedCount === 1;
  }
  async completeCommand(commandId, gatewayId, result, error) {
    const db = await this.connect();
    const now = new Date();
    await db.collection("commands").updateOne(
      { command_id: commandId, gateway_id: gatewayId, status: { $in: ["queued", "delivered"] } },
      { $set: { status: error ? "failed" : "completed", result: result ?? null, error: error ? String(error).slice(0, 500) : null, completed_at: now } }
    );
    await db.collection("audit_events").insertOne({
      event_id: randomUUID(), gateway_id: gatewayId, event_type: error ? "command_failed" : "command_completed",
      command_id: commandId, created_at: now
    });
  }
  async waitForCommand(commandId, timeoutMs) {
    const db = await this.connect();
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      const doc = await db.collection("commands").findOne({ command_id: commandId });
      if (!doc) throw new Error("command_not_found");
      if (doc.status === "completed") return doc.result ?? {};
      if (doc.status === "failed") throw new Error(doc.error || "gateway_command_failed");
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    await db.collection("commands").updateOne({ command_id: commandId, status: { $in: ["queued", "delivered"] } }, { $set: { status: "timed_out", completed_at: new Date() } });
    throw new Error("gateway_command_timeout");
  }
  watchCommands(instanceId, onCommand, onError = () => {}) {
    const dbPromise = this.connect();
    let closed = false;
    let stream;
    const start = async () => {
      if (closed) return;
      try {
        const db = await dbPromise;
        stream = db.collection("commands").watch(
          [{ $match: { operationType: "insert", "fullDocument.instance_id": instanceId } }],
          { fullDocument: "default" }
        );
        stream.on("change", change => {
          Promise.resolve(onCommand(change.fullDocument)).catch(onError);
        });
        const queued = await db.collection("commands").find({ instance_id: instanceId, status: "queued" }).toArray();
        for (const command of queued) Promise.resolve(onCommand(command)).catch(onError);
        stream.on("error", error => {
          onError(error);
          stream?.close().catch(() => {});
          if (!closed) setTimeout(start, 1000).unref?.();
        });
      } catch (error) {
        onError(error);
        if (!closed) setTimeout(start, 1000).unref?.();
      }
    };
    start();
    return () => { closed = true; stream?.close().catch(() => {}); };
  }
  async listAudit(gatewayId, limit = 100) {
    const db = await this.connect();
    return db.collection("audit_events").find({ gateway_id: gatewayId }, { projection: { _id: 0 } }).sort({ created_at: -1 }).limit(limit).toArray();
  }
}
