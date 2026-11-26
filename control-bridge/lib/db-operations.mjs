import { ObjectId } from "mongodb";
import { getDb } from "./mongodb.mjs";

const MAX_LIMIT = 200;
const collections = {
  customers: "customers", memories: "customer_memories", calls: "call_summaries",
  schedules: "call_schedules", smsSchedules: "sms_schedules", campaigns: "campaigns",
  campaignCustomers: "campaign_customers", interestedCustomers: "interested_customers",
};
const now = () => new Date();
const toDate = value => value instanceof Date ? value : new Date(Number(value) || Date.parse(value) || 0);
const idOrNew = value => value && ObjectId.isValid(String(value)) ? new ObjectId(String(value)) : new ObjectId();
const validId = value => typeof value === "string" && ObjectId.isValid(value);
const bounded = (value, min, max, fallback) => Number.isFinite(Number(value)) ? Math.min(max, Math.max(min, Number(value))) : fallback;
const text = (value, max = 1000) => typeof value === "string" ? value.slice(0, max) : "";
const OPERATIONS = new Set(["db.status", "customer.upsert", "customer.findById", "customer.findByPhone", "customer.search", "customer.callContext", "memory.add", "customer.instructionForPhone", "memory.forPhone", "schedule.save", "schedule.claim", "schedule.update", "smsSchedule.save", "smsSchedule.claim", "smsSchedule.update", "smsSchedule.retry", "campaignCustomer.blocked", "campaignCustomer.latestForPhone", "call.report", "schedule.list", "smsSchedule.list", "campaign.create", "campaign.decision", "campaign.followUp", "campaign.advance"]);

function publicCustomer(d) {
  if (!d) return null;
  return { id: d._id?.toString(), phone: d.phone || "", name: d.name || "", role: d.role || "", company: d.company || "",
    instructions: d.instructions || "", notes: d.notes || "", groups: Array.isArray(d.groups) ? d.groups : [],
    createdAt: d.createdAt instanceof Date ? d.createdAt.getTime() : 0,
    updatedAt: d.updatedAt instanceof Date ? d.updatedAt.getTime() : 0 };
}
function publicSchedule(d) {
  if (!d) return null;
  return { id: d._id?.toString(), customerId: d.customerId || "", phone: d.phone || "", runAt: d.runAt?.getTime?.() || 0,
    status: d.status || "", note: d.note || "", campaignId: d.campaignId || null, campaignCustomerId: d.campaignCustomerId || null,
    idempotencyKey: d.idempotencyKey || null, attempts: d.attempts || 0 };
}
function publicSmsSchedule(d) {
  if (!d) return null;
  return { id: d._id?.toString(), customerId: d.customerId || "", phone: d.phone || "", runAt: d.runAt?.getTime?.() || 0,
    message: d.message || "", status: d.status || "", campaignId: d.campaignId || null, note: d.note || "",
    campaignCustomerId: d.campaignCustomerId || null, idempotencyKey: d.idempotencyKey || null, attempts: d.attempts || 0 };
}
function fromScheduleInput(value, sms = false) {
  if (!value || typeof value !== "object") throw Object.assign(new Error("invalid_request"), { status: 400 });
  const runAt = Number(value.runAt);
  if (!Number.isFinite(runAt) || runAt < 0) throw Object.assign(new Error("invalid_request"), { status: 400 });
  const out = { customerId: text(value.customerId, 64), phone: text(value.phone, 40), runAt: new Date(runAt),
    status: text(value.status, 32) || (sms ? "queued" : "pending"), note: text(value.note, 1000),
    campaignId: text(value.campaignId, 64), campaignCustomerId: text(value.campaignCustomerId, 64),
    attempts: bounded(value.attempts, 0, 100000, 0) };
  if (sms) out.message = text(value.message, 4000);
  if (typeof value.idempotencyKey === "string" && value.idempotencyKey.trim()) out.idempotencyKey = value.idempotencyKey.slice(0, 180);
  if (value.id && validId(String(value.id))) out._id = new ObjectId(String(value.id));
  return out;
}

export async function executeDbOperation(operation, input = {}) {
  if (typeof operation !== "string" || operation.length > 64 || !input || typeof input !== "object" || Array.isArray(input))
    throw Object.assign(new Error("invalid_request"), { status: 400 });
  if (!OPERATIONS.has(operation)) throw Object.assign(new Error("unsupported_operation"), { status: 400 });
  const db = await getDb();
  const c = name => db.collection(collections[name]);
  const setStatus = (name, id, status, error) => {
    if (!validId(String(id || "")) || !["pending", "queued", "running", "done", "sent", "failed", "cancelled", "completed"].includes(status)) return null;
    const update = { $set: { status } };
    if (error) update.$set.error = text(error, 500); else update.$unset = { error: "" };
    return c(name).updateOne({ _id: new ObjectId(String(id)) }, update);
  };

  switch (operation) {
    case "db.status": {
      await db.command({ ping: 1 });
      return { ok: true, database: db.databaseName };
    }
    case "customer.upsert": {
      const x = input.customer || {};
      if (!text(x.phone, 40).trim() || !text(x.name, 160).trim()) throw Object.assign(new Error("customer_name_and_phone_required"), { status: 400 });
      const col = c("customers"), requestedId = x.id && validId(String(x.id)) ? new ObjectId(String(x.id)) : null;
      const prior = await col.findOne(requestedId ? { _id: requestedId } : { phone: text(x.phone, 40).trim() }, { projection: { _id: 1, createdAt: 1 } });
      const _id = prior?._id || idOrNew(x.id);
      const document = { phone: text(x.phone, 40).trim(), name: text(x.name, 160).trim(), role: text(x.role, 200).trim(),
        company: text(x.company, 200).trim(), instructions: text(x.instructions, 4000).trim(), notes: text(x.notes, 4000).trim(),
        groups: [...new Set((Array.isArray(x.groups) ? x.groups : []).filter(v => typeof v === "string").map(v => v.trim()).filter(Boolean))].slice(0, 100),
        createdAt: prior?.createdAt || toDate(x.createdAt), updatedAt: now() };
      await col.replaceOne({ _id }, document, { upsert: true });
      return { id: _id.toString() };
    }
    case "customer.findById": {
      const id = text(input.id, 64);
      return { customer: validId(id) ? publicCustomer(await c("customers").findOne({ _id: new ObjectId(id) })) : null };
    }
    case "customer.findByPhone": {
      const phone = text(input.phone, 40).trim();
      return { customer: phone ? publicCustomer(await c("customers").findOne({ phone })) : null };
    }
    case "customer.callContext": {
      const phone = text(input.phone, 40).trim();
      if (!phone) return { customer: null, memories: [] };
      const d = await c("customers").findOne({ phone });
      if (!d) return { customer: null, memories: [] };
      const memories = await c("memories").find({ customerId: d._id.toString() }).sort({ createdAt: -1 }).limit(20).toArray();
      return { customer: publicCustomer(d), memories: memories.reverse().map(item => text(item.text, 4000)).filter(Boolean) };
    }
    case "customer.search": {
      const q = text(input.query, 200).trim();
      const filter = q ? { $or: ["phone", "name", "company"].map(key => ({ [key]: { $regex: q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), $options: "i" } })) } : {};
      const rows = await c("customers").find(filter).sort({ createdAt: -1, _id: 1 }).limit(bounded(input.limit, 1, 50, 20)).toArray();
      return { customers: rows.map(publicCustomer) };
    }
    case "memory.add": {
      const customerId = text(input.customerId, 64), memory = text(input.text, 4000);
      if (!validId(customerId) || !memory.trim()) throw Object.assign(new Error("invalid_request"), { status: 400 });
      const result = await c("memories").insertOne({ customerId, text: memory, source: text(input.source, 80) || "call", createdAt: now() });
      return { id: result.insertedId.toString() };
    }
    case "customer.instructionForPhone": {
      const d = await c("customers").findOne({ phone: text(input.phone, 40).trim() }, { projection: { instructions: 1 } });
      return { instructions: d?.instructions || "" };
    }
    case "memory.forPhone": {
      const customer = await c("customers").findOne({ phone: text(input.phone, 40).trim() }, { projection: { _id: 1 } });
      if (!customer) return { memories: [] };
      const rows = await c("memories").find({ customerId: customer._id.toString() }).sort({ createdAt: -1 }).limit(bounded(input.limit, 1, 20, 10)).toArray();
      return { memories: rows.reverse().map(d => text(d.text, 4000)).filter(Boolean) };
    }
    case "schedule.save": {
      const doc = fromScheduleInput(input.schedule, false), col = c("schedules");
      if (doc.idempotencyKey) {
        const previous = await col.findOne({ idempotencyKey: doc.idempotencyKey }, { projection: { _id: 1 } });
        if (previous) return { id: previous._id.toString() };
      }
      const _id = doc._id || new ObjectId(); delete doc._id;
      try { await col.replaceOne({ _id }, doc, { upsert: true }); }
      catch (error) {
        if (error?.code !== 11000 || !doc.idempotencyKey) throw error;
        const previous = await col.findOne({ idempotencyKey: doc.idempotencyKey }, { projection: { _id: 1 } });
        if (!previous) throw error;
        return { id: previous._id.toString() };
      }
      return { id: _id.toString() };
    }
    case "schedule.claim": {
      const col = c("schedules"), at = new Date(bounded(input.now, 0, Number.MAX_SAFE_INTEGER, Date.now()));
      const stale = new Date(at.getTime() - 10 * 60_000);
      await col.updateMany({ status: "running", $or: [{ claimedAt: { $lt: stale } }, { claimedAt: { $exists: false } }] }, { $set: { status: "pending" }, $unset: { claimedAt: "" } });
      const d = await col.findOneAndUpdate({ status: "pending", runAt: { $lte: at } },
        { $set: { status: "running", claimedAt: at }, $inc: { attempts: 1 } }, { sort: { runAt: 1 }, returnDocument: "after" });
      return { schedule: publicSchedule(d) };
    }
    case "schedule.update":
      await setStatus("schedules", input.id, text(input.status, 32), input.error);
      return { ok: true };
    case "smsSchedule.save": {
      const doc = fromScheduleInput(input.schedule, true), col = c("smsSchedules");
      if (doc.idempotencyKey) {
        const previous = await col.findOne({ idempotencyKey: doc.idempotencyKey }, { projection: { _id: 1 } });
        if (previous) return { id: previous._id.toString() };
      }
      const _id = doc._id || new ObjectId(); delete doc._id;
      try { await col.replaceOne({ _id }, doc, { upsert: true }); }
      catch (error) {
        if (error?.code !== 11000 || !doc.idempotencyKey) throw error;
        const previous = await col.findOne({ idempotencyKey: doc.idempotencyKey }, { projection: { _id: 1 } });
        if (!previous) throw error;
        return { id: previous._id.toString() };
      }
      return { id: _id.toString() };
    }
    case "smsSchedule.claim": {
      const col = c("smsSchedules"), at = new Date(bounded(input.now, 0, Number.MAX_SAFE_INTEGER, Date.now()));
      const stale = new Date(at.getTime() - 10 * 60_000);
      await col.updateMany({ status: "running", $or: [{ claimedAt: { $lt: stale } }, { claimedAt: { $exists: false } }] }, { $set: { status: "queued" }, $unset: { claimedAt: "" } });
      const d = await col.findOneAndUpdate({ status: "queued", runAt: { $lte: at } },
        { $set: { status: "running", claimedAt: at }, $inc: { attempts: 1 } }, { sort: { runAt: 1 }, returnDocument: "after" });
      return { schedule: publicSmsSchedule(d) };
    }
    case "smsSchedule.update":
      await setStatus("smsSchedules", input.id, text(input.status, 32), input.error);
      return { ok: true };
    case "smsSchedule.retry": {
      const id = text(input.id, 64);
      if (!validId(id)) throw Object.assign(new Error("invalid_request"), { status: 400 });
      const col = c("smsSchedules"), _id = new ObjectId(id);
      await col.updateOne({ _id, status: "running" }, { $set: { lastError: text(input.error, 500) } });
      const item = await col.findOne({ _id });
      if (!item || item.status !== "running") return { status: item?.status || "missing", attempts: item?.attempts || 0 };
      if ((item.attempts || 0) >= 3) {
        await col.updateOne({ _id, status: "running" }, { $set: { status: "failed", updatedAt: now() } });
        return { status: "failed", attempts: item.attempts };
      }
      const delayMs = item.attempts <= 1 ? 60_000 : 5 * 60_000;
      await col.updateOne({ _id, status: "running" }, { $set: { status: "queued", runAt: new Date(Date.now() + delayMs), updatedAt: now() } });
      return { status: "queued", attempts: item.attempts, retryAt: Date.now() + delayMs };
    }
    case "campaignCustomer.blocked": {
      const id = text(input.id, 64);
      if (!validId(id)) return { blocked: false };
      const d = await c("campaignCustomers").findOne({ _id: new ObjectId(id) }, { projection: { status: 1 } });
      return { blocked: ["admin", "opted_out", "paused"].includes(d?.status) };
    }
    case "campaignCustomer.latestForPhone": {
      const d = await c("campaignCustomers").find({ phone: text(input.phone, 40).trim() }).sort({ createdAt: -1 }).limit(1).next();
      return { id: d?._id?.toString() || null };
    }
    case "call.report": {
      const r = input.report || {};
      const reportId = text(input.reportId, 80);
      const prior = reportId ? await c("calls").findOne({ reportId }, { projection: { _id: 1 } }) : null;
      const phone = text(r.number, 40);
      const doc = { ...(reportId ? { reportId } : {}), phone, name: text(r.name, 160), role: text(r.role, 200), company: text(r.company, 200),
        startedAt: toDate(r.startedAt), endedAt: toDate(r.endedAt), cause: text(r.cause, 80), failureDetail: text(r.failureDetail, 500),
        inputText: text(r.inputText, 12000), outputText: text(r.outputText, 12000), conversationTranscript: text(r.conversationTranscript, 24000),
        inputFrames: bounded(r.inputFrames, 0, 1_000_000, 0), outputFrames: bounded(r.outputFrames, 0, 1_000_000, 0),
        inputAttempts: bounded(r.inputAttempts, 0, 1_000_000, 0), inputSendFailures: bounded(r.inputSendFailures, 0, 1_000_000, 0),
        outputAudioChunks: bounded(r.outputAudioChunks, 0, 1_000_000, 0), outputAudioBytes: bounded(r.outputAudioBytes, 0, Number.MAX_SAFE_INTEGER, 0),
        outputDroppedFrames: bounded(r.outputDroppedFrames, 0, 1_000_000, 0), outputQueuePeak: bounded(r.outputQueuePeak, 0, 1_000_000, 0),
        firstInputSentAt: Number(r.firstInputSentAt) || 0, lastInputSentAt: Number(r.lastInputSentAt) || 0,
        firstOutputAt: Number(r.firstOutputAt) || 0, firstOutputQueuedAt: Number(r.firstOutputQueuedAt) || 0,
        firstOutputPlayedAt: Number(r.firstOutputPlayedAt) || 0, turnCompleteAt: Number(r.turnCompleteAt) || 0,
        turnResponseCount: bounded(r.turnResponseCount, 0, 1_000_000, 0), lastTurnResponseMs: Number(r.lastTurnResponseMs) || 0,
        averageTurnResponseMs: Number(r.averageTurnResponseMs) || 0, lastTurnSpeechDurationMs: Number(r.lastTurnSpeechDurationMs) || 0,
        averageTurnSpeechDurationMs: Number(r.averageTurnSpeechDurationMs) || 0, turnCount: bounded(r.turnCount, 0, 1_000_000, 0),
        lastSpeechEndAt: Number(r.lastSpeechEndAt) || 0, createdAt: now() };
      if (!prior) {
        try { await c("calls").insertOne(doc); }
        catch (error) {
          if (!(reportId && error?.code === 11000)) throw error;
        }
      }
      const customer = phone ? await c("customers").findOne({ phone }, { projection: { _id: 1 } }) : null;
      if (customer) {
        let memory = `نتيجة مكالمة ${doc.endedAt.toISOString()}: ${doc.cause || "NORMAL"}`;
        if (doc.conversationTranscript) memory += `\n${doc.conversationTranscript}`;
        else if (doc.outputText) memory += `\nرد Gemini: ${doc.outputText}`;
        const memoryDoc = { customerId: customer._id.toString(), text: memory.slice(0, 4000), source: "gemini_call", createdAt: now() };
        if (reportId) await c("memories").updateOne({ callReportId: reportId }, { $setOnInsert: { ...memoryDoc, callReportId: reportId } }, { upsert: true });
        else await c("memories").insertOne(memoryDoc);
      }
      return { ok: true, replayed: Boolean(prior) };
    }
    case "schedule.list": {
      const rows = await c("schedules").find({}).sort({ runAt: 1 }).limit(bounded(input.limit, 1, MAX_LIMIT, 20)).toArray();
      return { schedules: rows.map(publicSchedule) };
    }
    case "smsSchedule.list": {
      const rows = await c("smsSchedules").find({}).sort({ runAt: 1 }).limit(bounded(input.limit, 1, MAX_LIMIT, 20)).toArray();
      return { schedules: rows.map(publicSmsSchedule) };
    }
    case "campaign.create": {
      const x = input.campaign || {};
      if (!text(x.name, 160).trim()) throw Object.assign(new Error("campaign_name_required"), { status: 400 });
      const campaignId = idOrNew(x.id), startAt = Number(x.startAt) || Date.now();
      await c("campaigns").insertOne({ _id: campaignId, name: text(x.name, 160).trim(), startAt: new Date(startAt),
        timezone: text(x.timezone, 100) || "UTC", targetGroup: text(x.targetGroup, 100).trim(), channel: text(x.channel, 32) || "call_sms",
        smsTemplate: text(x.smsTemplate, 1000), status: text(x.status, 32) || "queued", createdAt: toDate(x.createdAt), updatedAt: now() });
      const filter = { optedOut: { $ne: true } };
      const group = text(x.targetGroup, 100).trim();
      if (group && !/^all$/i.test(group)) filter.groups = group;
      const customers = await c("customers").find(filter).sort({ createdAt: 1, _id: 1 }).limit(10000).toArray();
      const members = customers.map((d, sequence) => ({ _id: new ObjectId(), campaignId: campaignId.toString(), customerId: d._id.toString(), phone: d.phone || "",
        sequence, status: "queued", attempts: 0, createdAt: now(), updatedAt: now() }));
      if (members.length) await c("campaignCustomers").insertMany(members);
      if (members.length && String(x.channel || "call_sms").includes("call")) {
        const first = members[0];
        await insertScheduled(c("schedules"), { customerId: first.customerId, phone: first.phone, runAt: new Date(startAt), status: "pending",
          note: "", campaignId: campaignId.toString(), campaignCustomerId: first._id.toString(), attempts: 0,
          idempotencyKey: `${campaignId}:call:${first._id}:${startAt}` });
        if (x.channel === "call_sms" && text(x.smsTemplate, 1000).trim()) await insertScheduled(c("smsSchedules"), {
          customerId: first.customerId, phone: first.phone, runAt: new Date(startAt), message: text(x.smsTemplate, 1000), status: "queued",
          campaignId: campaignId.toString(), campaignCustomerId: first._id.toString(), note: "", attempts: 0,
          idempotencyKey: `${campaignId}:sms:${first._id}:${startAt}` });
        await c("campaignCustomers").updateOne({ campaignId: campaignId.toString(), sequence: 0 }, { $set: { status: "call_scheduled" } });
      } else if (members.length && x.channel === "sms" && text(x.smsTemplate, 1000).trim()) {
        for (const member of members) {
          await insertScheduled(c("smsSchedules"), { customerId: member.customerId, phone: member.phone, runAt: new Date(startAt),
            message: text(x.smsTemplate, 1000), status: "queued", campaignId: campaignId.toString(), campaignCustomerId: member._id.toString(), note: "", attempts: 0,
            idempotencyKey: `${campaignId}:sms:${member._id}:${startAt}` });
          await c("campaignCustomers").updateOne({ campaignId: campaignId.toString(), sequence: member.sequence }, { $set: { status: "sms_scheduled" } });
        }
      }
      return { id: campaignId.toString(), customerCount: members.length };
    }
    case "campaign.decision": {
      const id = text(input.campaignCustomerId, 64), outcome = text(input.outcome, 80), note = text(input.note, 1000);
      if (!validId(id)) return { ok: true };
      const _id = new ObjectId(id), date = now();
      const status = ["interested", "admin"].includes(outcome) ? "admin" : outcome === "opted_out" ? "opted_out" : outcome === "paused" ? "paused" : "follow_up";
      const updates = { status, lastOutcome: outcome, followUpNote: note, updatedAt: date };
      if (status === "admin") Object.assign(updates, { interestedAt: date, adminAt: date });
      if (status === "paused") updates.pausedAt = date;
      if (status === "opted_out") updates.optedOutAt = date;
      const member = await c("campaignCustomers").findOneAndUpdate({ _id }, { $set: updates }, { returnDocument: "after" });
      if (!member) return { ok: true };
      if (["admin", "opted_out", "paused"].includes(status)) {
        await c("schedules").updateMany({ campaignCustomerId: id, status: "pending" }, { $set: { status: "cancelled" } });
        await c("smsSchedules").updateMany({ campaignCustomerId: id, status: "queued" }, { $set: { status: "cancelled" } });
      }
      if (status === "admin") await c("interestedCustomers").updateOne({ campaignCustomerId: id },
        { $set: { campaignCustomerId: id, outcome, note, updatedAt: date } }, { upsert: true });
      if (status === "opted_out" && validId(member.customerId || "")) await c("customers").updateOne({ _id: new ObjectId(member.customerId) }, { $set: { optedOut: true } });
      return { ok: true };
    }
    case "campaign.followUp": {
      const id = text(input.campaignCustomerId, 64);
      if (!validId(id)) return { scheduled: false };
      const member = await c("campaignCustomers").findOne({ _id: new ObjectId(id) });
      if (!member || ["admin", "opted_out"].includes(member.status)) return { scheduled: false };
      const callAt = input.nextCallAt == null ? null : Number(input.nextCallAt);
      const smsAt = input.nextSmsAt == null ? null : Number(input.nextSmsAt);
      const note = text(input.note, 1000), changes = { status: "follow_up", followUpNote: note, updatedAt: now() };
      if (callAt == null) changes.nextCallAt = null; else changes.nextCallAt = new Date(callAt);
      if (smsAt == null) changes.nextSmsAt = null; else changes.nextSmsAt = new Date(smsAt);
      await c("campaignCustomers").updateOne({ _id: new ObjectId(id) }, { $set: changes });
      const campaignId = text(member.campaignId, 64);
      if (callAt != null && Number.isFinite(callAt)) await insertScheduled(c("schedules"), { customerId: member.customerId || "", phone: member.phone || "", runAt: new Date(callAt), status: "pending", note, campaignId, campaignCustomerId: id, attempts: 0, idempotencyKey: `${id}:call:${callAt}` });
      if (smsAt != null && Number.isFinite(smsAt)) await insertScheduled(c("smsSchedules"), { customerId: member.customerId || "", phone: member.phone || "", runAt: new Date(smsAt), message: note, status: "queued", campaignId, campaignCustomerId: id, note, attempts: 0, idempotencyKey: `${id}:sms:${smsAt}` });
      return { scheduled: true };
    }
    case "campaign.advance": {
      const id = text(input.campaignCustomerId, 64);
      if (!validId(id)) return { advanced: false };
      const current = await c("campaignCustomers").findOne({ _id: new ObjectId(id) });
      if (!current) return { advanced: false };
      const next = await c("campaignCustomers").find({ campaignId: current.campaignId, status: "queued" }).sort({ sequence: 1 }).limit(1).next();
      const campaign = validId(current.campaignId) ? await c("campaigns").findOne({ _id: new ObjectId(current.campaignId) }) : null;
      if (!next || !campaign) return { advanced: false };
      const at = Date.now();
      if (String(campaign.channel || "").includes("call")) {
        await insertScheduled(c("schedules"), { customerId: next.customerId || "", phone: next.phone || "", runAt: new Date(at), status: "pending", note: "", campaignId: current.campaignId, campaignCustomerId: next._id.toString(), attempts: 0, idempotencyKey: `${current.campaignId}:call:${next._id}:${at}` });
        if (campaign.channel === "call_sms" && text(campaign.smsTemplate, 1000).trim()) await insertScheduled(c("smsSchedules"), { customerId: next.customerId || "", phone: next.phone || "", runAt: new Date(at), message: campaign.smsTemplate, status: "queued", campaignId: current.campaignId, campaignCustomerId: next._id.toString(), note: "", attempts: 0, idempotencyKey: `${current.campaignId}:sms:${next._id}:${at}` });
        await c("campaignCustomers").updateOne({ _id: next._id }, { $set: { status: "call_scheduled" } });
        return { advanced: true };
      }
      return { advanced: false };
    }
    default:
      throw Object.assign(new Error("unsupported_operation"), { status: 400 });
  }
}

async function insertScheduled(collection, doc) {
  if (doc.idempotencyKey) {
    const existing = await collection.findOne({ idempotencyKey: doc.idempotencyKey }, { projection: { _id: 1 } });
    if (existing) return existing._id;
  }
  try {
    const result = await collection.insertOne(doc);
    return result.insertedId;
  } catch (error) {
    if (error?.code !== 11000 || !doc.idempotencyKey) throw error;
    const existing = await collection.findOne({ idempotencyKey: doc.idempotencyKey }, { projection: { _id: 1 } });
    if (!existing) throw error;
    return existing._id;
  }
}
