import { randomUUID } from "node:crypto";
import { MongoClient } from "mongodb";

const text = (v, n = 500) => String(v ?? "").trim().slice(0, n);
function phoneKey(value) {
  let digits = text(value, 40).replace(/\D/g, "");
  if (digits.startsWith("00")) digits = digits.slice(2);
  if (/^7\d{8}$/.test(digits)) digits = "967" + digits;
  if (digits.length < 7 || digits.length > 15) throw Object.assign(new Error("invalid_phone_number"), { status: 400 });
  return "+" + digits;
}
function pub(doc) {
  if (!doc) return null;
  const { _id, tenantId, ...rest } = doc;
  return { id: _id, ...rest };
}
const iso = value => {
  const d = value ? new Date(value) : new Date();
  if (Number.isNaN(d.getTime())) throw Object.assign(new Error("invalid_date"), { status: 400 });
  return d.toISOString();
};

export async function createMongoCrmStore() {
  const uri = text(process.env.MONGODB_URI, 2000);
  if (!uri) return null;
  const tenantId = text(process.env.VOICX_CRM_TENANT_ID, 100);
  if (!tenantId) throw new Error("VOICX_CRM_TENANT_ID is required");
  const databaseName = text(process.env.MONGODB_DATABASE || "voice_sip_agent_control", 100);
  const client = new MongoClient(uri, { appName: "voicx-crm", maxPoolSize: 5, serverSelectionTimeoutMS: 10000 });
  await client.connect();
  const db = client.db(databaseName);
  const contacts = db.collection("contacts");
  const interactions = db.collection("contact_interactions");
  const followUps = db.collection("follow_ups");
  await Promise.all([
    contacts.createIndexes([
      { key: { tenantId: 1, normalizedPhone: 1 }, unique: true, name: "uq_crm_tenant_phone" },
      { key: { tenantId: 1, lastContactAt: -1 }, name: "ix_crm_last_contact" },
      { key: { tenantId: 1, nextFollowUpAt: 1 }, name: "ix_crm_next_followup" },
    ]),
    interactions.createIndexes([
      { key: { tenantId: 1, contactId: 1, occurredAt: -1 }, name: "ix_crm_interaction_contact" },
      { key: { tenantId: 1, normalizedPhone: 1, occurredAt: -1 }, name: "ix_crm_interaction_phone" },
      { key: { tenantId: 1, idempotencyKey: 1 }, unique: true, sparse: true, name: "uq_crm_interaction_idem" },
    ]),
    followUps.createIndexes([{ key: { tenantId: 1, status: 1, dueAt: 1 }, name: "ix_crm_followup_due" }]),
  ]);
  const scope = { tenantId };
  const get = id => contacts.findOne({ ...scope, _id: id, status: { $ne: "archived" } });

  async function addContact(input) {
    const now = new Date().toISOString();
    const doc = { _id: randomUUID(), ...scope, name: text(input.name, 160), phone: text(input.phone, 40),
      normalizedPhone: phoneKey(input.phone), company: text(input.company, 160), jobTitle: text(input.jobTitle, 120),
      city: text(input.city, 100), email: text(input.email, 180), notes: text(input.notes, 2000),
      tags: Array.isArray(input.tags) ? input.tags.map(x => text(x, 40)).filter(Boolean).slice(0, 30) : [],
      status: "active", contactCount: 0, createdAt: now, updatedAt: now };
    if (!doc.name) throw Object.assign(new Error("contact_name_required"), { status: 400 });
    await contacts.insertOne(doc);
    const orphanCalls = await interactions.find({ ...scope, normalizedPhone: doc.normalizedPhone, contactId: null }).sort({ occurredAt: -1 }).limit(100).toArray();
    if (orphanCalls.length) {
      await interactions.updateMany({ ...scope, normalizedPhone: doc.normalizedPhone, contactId: null }, { $set: { contactId: doc._id } });
      const last = orphanCalls[0];
      await contacts.updateOne({ ...scope, _id: doc._id }, { $set: { lastContactAt: last.occurredAt,
        lastContactChannel: last.channel, lastContactResult: last.outcome, lastContactSummary: last.summary },
        $inc: { contactCount: orphanCalls.length } });
    }
    return pub(await get(doc._id));
  }

  async function searchContacts(filters = {}) {
    const q = { ...scope, status: { $ne: "archived" } };
    if (filters.neverContacted) q.lastContactAt = { $exists: false };
    const days = Number(filters.lastContactDays || filters.olderThanDays || 0);
    if (Number.isInteger(days) && days > 0 && days <= 3650) q.lastContactAt = { $lt: new Date(Date.now() - days * 86400000).toISOString() };
    for (const key of ["city", "company"]) if (filters[key]) q[key] = { $regex: text(filters[key], 100), $options: "i" };
    if (filters.query) q.$or = ["name", "phone", "company", "jobTitle", "city"].map(k => ({ [k]: { $regex: text(filters.query, 100), $options: "i" } }));
    const rows = await contacts.find(q).sort({ lastContactAt: 1, updatedAt: -1 }).limit(Math.min(200, Math.max(1, Number(filters.limit || 50)))).toArray();
    return rows.map(row => ({ ...pub(row), daysSinceLastContact: row.lastContactAt ? Math.max(0, Math.floor((Date.now() - Date.parse(row.lastContactAt)) / 86400000)) : null, neverContacted: !row.lastContactAt }));
  }

  async function addInteraction(input) {
    const normalizedPhone = input.phone ? phoneKey(input.phone) : null;
    const contact = input.contactId ? await get(input.contactId) : normalizedPhone
      ? await contacts.findOne({ ...scope, normalizedPhone, status: { $ne: "archived" } }) : null;
    if (input.contactId && !contact) throw Object.assign(new Error("contact_not_found"), { status: 404 });
    const item = { _id: text(input.id, 80) || randomUUID(), ...scope, contactId: contact?._id || null,
      phone: text(input.phone || contact?.phone, 40), normalizedPhone: normalizedPhone || contact?.normalizedPhone || null,
      channel: text(input.channel || "call", 24), outcome: text(input.outcome || "completed", 80),
      summary: text(input.summary, 2000), occurredAt: iso(input.occurredAt),
      durationSeconds: Math.max(0, Math.min(86400, Number(input.durationSeconds || 0))),
      nextFollowUpAt: input.nextFollowUpAt ? iso(input.nextFollowUpAt) : null,
      idempotencyKey: input.idempotencyKey || undefined, source: text(input.source || "telegram", 40),
      createdAt: new Date().toISOString() };
    try { await interactions.insertOne(item); } catch (e) {
      if (e?.code === 11000 && item.idempotencyKey) {
        const old = await interactions.findOne({ ...scope, idempotencyKey: item.idempotencyKey });
        return { interaction: pub(old), contact: contact ? pub(contact) : null, duplicate: true };
      }
      throw e;
    }
    if (contact) {
      await contacts.updateOne({ ...scope, _id: contact._id }, { $set: { lastContactAt: item.occurredAt,
        lastContactChannel: item.channel, lastContactResult: item.outcome, lastContactSummary: item.summary,
        ...(item.nextFollowUpAt ? { nextFollowUpAt: item.nextFollowUpAt } : {}), updatedAt: new Date().toISOString() },
        $inc: { contactCount: 1 } });
      if (item.nextFollowUpAt) await followUps.insertOne({ _id: randomUUID(), ...scope, contactId: contact._id,
        title: "متابعة بعد التواصل", dueAt: item.nextFollowUpAt, status: "open", interactionId: item._id, createdAt: new Date().toISOString() });
    }
    return { interaction: pub(item), contact: contact ? pub(await get(contact._id)) : null, duplicate: false };
  }

  return {
    tenantId, databaseName, addContact, searchContacts, addInteraction,
    async getContact(id) { return pub(await get(id)); },
    async listInteractions(id, limit = 50) {
      return (await interactions.find({ ...scope, contactId: id }).sort({ occurredAt: -1 }).limit(Math.min(100, Number(limit) || 50)).toArray()).map(pub);
    },
    async listFollowUps(today = false, limit = 100) {
      const q = { ...scope, status: { $nin: ["done", "cancelled"] } };
      if (today) q.dueAt = { $lte: new Date(new Date().setUTCHours(23, 59, 59, 999)).toISOString() };
      const rows = await followUps.find(q).sort({ dueAt: 1 }).limit(Math.min(200, Number(limit) || 100)).toArray();
      const people = await contacts.find({ ...scope, _id: { $in: rows.map(x => x.contactId) } }).toArray();
      const byId = new Map(people.map(x => [x._id, pub(x)]));
      return rows.map(x => ({ ...pub(x), contact: byId.get(x.contactId) || null }));
    },
    async recordCall(input) {
      const key = phoneKey(input.phone);
      let contact = input.name ? await contacts.findOne({ ...scope, normalizedPhone: key, status: { $ne: "archived" } }) : null;
      if (!contact && input.name) contact = await addContact(input);
      return addInteraction({ ...input, contactId: contact?.id, channel: "call", source: "gateway" });
    },
    async updateContact(id, patch) {
      const set = { updatedAt: new Date().toISOString() };
      for (const key of ["name", "phone", "company", "jobTitle", "city", "email", "notes", "tags"]) if (patch[key] !== undefined) set[key] = patch[key];
      if (set.phone) set.normalizedPhone = phoneKey(set.phone);
      const result = await contacts.findOneAndUpdate({ ...scope, _id: id, status: { $ne: "archived" } }, { $set: set }, { returnDocument: "after" });
      if (!result) throw Object.assign(new Error("contact_not_found"), { status: 404 });
      return pub(result);
    },
    async archiveContact(id) {
      const r = await contacts.updateOne({ ...scope, _id: id, status: { $ne: "archived" } }, { $set: { status: "archived", updatedAt: new Date().toISOString() } });
      if (!r.matchedCount) throw Object.assign(new Error("contact_not_found"), { status: 404 });
      return true;
    },
    async importContacts(rows, dryRun = true) {
      if (!Array.isArray(rows) || !rows.length || rows.length > 500) throw Object.assign(new Error("import_rows_must_be_1_to_500"), { status: 400 });
      let valid = 0, duplicates = 0; const errors = [], accepted = [];
      for (let i = 0; i < rows.length; i++) {
        try {
          const phone = phoneKey(rows[i].phone);
          if (!text(rows[i].name, 160)) throw new Error("name_required");
          if (accepted.some(x => x.phone === phone) || await contacts.findOne({ ...scope, normalizedPhone: phone, status: { $ne: "archived" } })) { duplicates++; continue; }
          accepted.push({ ...rows[i], phone }); valid++;
        } catch (e) { errors.push({ row: i + 1, error: e.message || "invalid_row" }); }
      }
      return { total: rows.length, valid, duplicates, errors: errors.slice(0, 100), dryRun,
        ...(dryRun ? {} : { imported: (await Promise.all(accepted.map(addContact))).length }) };
    },
  };
}
