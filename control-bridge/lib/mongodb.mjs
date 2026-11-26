import { MongoClient } from "mongodb";

let cached = globalThis.__voicxMongo;
if (!cached) {
  cached = { client: null, promise: null, db: null };
  globalThis.__voicxMongo = cached;
}

export async function getDb() {
  if (cached.db) return cached.db;
  const uri = process.env.MONGODB_URI;
  const dbName = process.env.MONGODB_DB_NAME || "voicx_sip_prod";
  if (!uri || !/^mongodb(?:\+srv)?:\/\//i.test(uri)) throw new Error("database_not_configured");
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(dbName)) throw new Error("database_not_configured");
  if (!cached.promise) {
    const client = new MongoClient(uri, {
      maxPoolSize: 10,
      minPoolSize: 0,
      serverSelectionTimeoutMS: 8000,
      connectTimeoutMS: 8000,
      socketTimeoutMS: 15000,
      maxIdleTimeMS: 60000,
      retryReads: true,
      retryWrites: true,
      appName: "voicx-vercel-api",
    });
    cached.client = client;
    cached.promise = client.connect().then(async connected => {
      cached.db = connected.db(dbName);
      await cached.db.command({ ping: 1 });
      await Promise.all([
        cached.db.collection("customers").createIndex({ createdAt: 1, _id: 1 }),
        cached.db.collection("customer_memories").createIndex({ customerId: 1, createdAt: -1 }),
        cached.db.collection("call_summaries").createIndex({ phone: 1, createdAt: -1 }),
        cached.db.collection("call_summaries").createIndex({ reportId: 1 }, { unique: true, sparse: true }),
        cached.db.collection("customer_memories").createIndex({ callReportId: 1 }, { unique: true, sparse: true }),
        cached.db.collection("call_schedules").createIndex({ status: 1, runAt: 1 }),
        cached.db.collection("call_schedules").createIndex({ idempotencyKey: 1 }, { unique: true, sparse: true }),
        cached.db.collection("sms_schedules").createIndex({ status: 1, runAt: 1 }),
        cached.db.collection("sms_schedules").createIndex({ idempotencyKey: 1 }, { unique: true, sparse: true }),
        cached.db.collection("campaigns").createIndex({ status: 1, startAt: 1 }),
        cached.db.collection("campaign_customers").createIndex({ campaignId: 1, status: 1, sequence: 1 }),
        cached.db.collection("campaign_customers").createIndex({ phone: 1, createdAt: -1 }),
        cached.db.collection("interested_customers").createIndex({ campaignCustomerId: 1 }, { unique: true }),
        cached.db.collection("telegram_updates").createIndex({ updateId: 1 }, { unique: true }),
        cached.db.collection("telegram_updates").createIndex({ status: 1, nextAttemptAt: 1, createdAt: 1 }),
        cached.db.collection("device_status").createIndex({ deviceId: 1 }, { unique: true }),
        cached.db.collection("device_commands").createIndex({ commandId: 1 }, { unique: true }),
        cached.db.collection("device_commands").createIndex({ sourceUpdateId: 1 }, { unique: true, sparse: true }),
        cached.db.collection("device_commands").createIndex({ deviceId: 1, status: 1, availableAt: 1, createdAt: 1 }),
        cached.db.collection("device_commands").createIndex({ status: 1, replyStatus: 1, replyNextAttemptAt: 1 }),
      ]);
      return cached.db;
    }).catch(error => {
      cached.client = null;
      cached.db = null;
      cached.promise = null;
      throw error;
    });
  }
  await cached.promise;
  return cached.db;
}

export function databaseConfigured() {
  return Boolean(process.env.MONGODB_URI && process.env.MONGODB_DB_NAME);
}
