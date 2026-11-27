const collections = ["contacts", "filters", "outbox", "calls", "automations", "publications", "tasks", "reports", "campaigns", "templates"];

function ensure(state) {
  state.crm ||= {};
  for (const name of collections) state.crm[name] ||= [];
}
function fail(res, status, error) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify({ error }));
}
function ok(res, status, value) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(value));
}
function id() { return crypto.randomUUID(); }
function cleanString(value, max = 500) { return typeof value === "string" ? value.trim().slice(0, max) : ""; }
function phoneKey(value) {
  let digits = cleanString(value, 40).replace(/\\D/g, "");
  if (digits.startsWith("00")) digits = digits.slice(2);
  if (digits.startsWith("967")) return digits;
  if (digits.length === 9 && digits.startsWith("7")) return "967" + digits;
  if (digits.length === 10 && digits.startsWith("07")) return "967" + digits.slice(1);
  return digits;
}

export async function handleCrmRoute(req, res, url, ctx) {
  const p = url.pathname;
  if (!p.startsWith("/api/v1/crm/")) return false;
  if (!ctx.authAdmin(req)) { fail(res, 401, "unauthorized"); return true; }
  ensure(ctx.state);
  const crm = ctx.state.crm;
  const parts = p.split("/").filter(Boolean).slice(3);
  const resource = parts[0] || "";
  const itemId = parts[1] || "";
  const action = parts[2] || "";
  const b = ["POST", "PUT", "PATCH"].includes(req.method) ? await ctx.body(req) : {};

  // This route must precede generic task-id lookup so /tasks/today is not
  // interpreted as a task whose id is literally "today".
  if (resource === "tasks" && itemId === "today" && req.method === "GET") {
    const today = new Date().toISOString().slice(0, 10);
    return ok(res, 200, { tasks: crm.tasks.filter(x => String(x.dueAt || "").slice(0, 10) === today && !["done", "cancelled"].includes(x.status)) }), true;
  }

  if (resource === "contacts" && itemId && action === "messages" && req.method === "GET") {
    const contact = crm.contacts.find(x => x.id === itemId);
    return contact ? ok(res, 200, { messages: Array.isArray(contact.previousMessages) ? contact.previousMessages : [] }) : fail(res, 404, "contact_not_found"), true;
  }

  if (resource === "contacts" && itemId && action === "call-summary" && req.method === "PATCH") {
    const contact = crm.contacts.find(x => x.id === itemId);
    if (!contact) return fail(res, 404, "contact_not_found"), true;
    contact.previousCallSummary = cleanString(b.summary, 4000);
    contact.lastCallAt = b.lastCallAt || new Date().toISOString();
    contact.updatedAt = new Date().toISOString();
    await ctx.save();
    return ok(res, 200, { contact }), true;
  }

  const specs = {
    contacts: ["contacts", ["name","phone","email","jobTitle","company","professionalEmail","previousMessages","previousCallSummary","nextSendAt","tags","notes","optedOut","doNotContact"]],
    filters: ["filters", ["name","description","rules","enabled"]],
    tasks: ["tasks", ["title","description","dueAt","status","priority","contactId","channel"]],
    automations: ["automations", ["name","channel","trigger","enabled","config","replyPolicy"]],
    publications: ["publications", ["channel","title","description","content","url","scheduledAt","status","externalId"]],
    reports: ["reports", ["periodStart","periodEnd","summary","metrics","createdAt"]],
    campaigns: ["campaigns", ["name","channel","filterId","message","status","scheduledAt","createdAt","updatedAt"]],
    templates: ["templates", ["name","channel","subject","message","variables","enabled"]]
  };

  if (specs[resource] && (req.method === "GET" || req.method === "POST")) {
    const [key, fields] = specs[resource];
    if (req.method === "GET" && !itemId) {
      const limit = Math.min(200, Math.max(1, Number(url.searchParams.get("limit") || 100)));
      return ok(res, 200, { [key]: crm[key].slice(-limit).reverse() }), true;
    }
    if (req.method === "GET" && itemId === "today" && resource === "tasks") {
      const today = new Date().toISOString().slice(0,10);
      return ok(res, 200, { tasks: crm.tasks.filter(x => String(x.dueAt || "").slice(0,10) === today && !["done","cancelled"].includes(x.status)) }), true;
    }
    if (req.method === "GET" && itemId) {
      const found = crm[key].find(x => x.id === itemId);
      return found ? ok(res, 200, { [resource.slice(0,-1)]: found }) : fail(res, 404, "not_found"), true;
    }
    if (req.method === "POST" && !itemId) {
      const record = { id: id(), createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
      for (const field of fields) if (b[field] !== undefined) record[field] = b[field];
      if (resource === "contacts" && (!cleanString(record.name, 160) || !cleanString(record.phone, 40)))
        return fail(res, 400, "contact_name_and_phone_required"), true;
      if (resource === "tasks" && !cleanString(record.title, 200)) return fail(res, 400, "task_title_required"), true;
      crm[key].push(record);
      ctx.audit("crm." + resource + ".created", "api-client", { recordId: record.id });
      await ctx.save();
      return ok(res, 201, { [resource.slice(0,-1)]: record }), true;
    }
  }

  if (specs[resource] && itemId && ["PATCH","DELETE"].includes(req.method)) {
    const [key, fields] = specs[resource];
    const index = crm[key].findIndex(x => x.id === itemId);
    if (index < 0) return fail(res, 404, "not_found"), true;
    if (req.method === "DELETE") crm[key].splice(index, 1);
    else {
      for (const field of fields) if (b[field] !== undefined) crm[key][index][field] = b[field];
      crm[key][index].updatedAt = new Date().toISOString();
    }
    ctx.audit("crm." + resource + (req.method === "DELETE" ? ".deleted" : ".updated"), "api-client", { recordId: itemId });
    await ctx.save();
    return ok(res, 200, { ok: true }), true;
  }

  if (resource === "contacts" && itemId === "search" && req.method === "POST") {
    const q = cleanString(b.query, 200).toLowerCase();
    const filterId = cleanString(b.filterId, 100);
    let rows = crm.contacts;
    if (filterId) {
      const filter = crm.filters.find(x => x.id === filterId && x.enabled !== false);
      if (!filter) return fail(res, 404, "filter_not_found"), true;
      const tags = Array.isArray(filter.rules?.tags) ? filter.rules.tags : [];
      if (tags.length) rows = rows.filter(x => tags.some(t => (x.tags || []).includes(t)));
      if (filter.rules?.hasEmail) rows = rows.filter(x => x.email || x.professionalEmail);
      if (filter.rules?.hasPhone) rows = rows.filter(x => x.phone);
    }
    if (q) rows = rows.filter(x => [x.name,x.phone,x.email,x.jobTitle,x.company].some(v => String(v || "").toLowerCase().includes(q)));
    return ok(res, 200, { contacts: rows.slice(0, 500) }), true;
  }

  if (["sms","whatsapp","calls"].includes(resource) && req.method === "POST" && !itemId) {
    const channel = resource === "calls" ? "call" : resource;
    const to = cleanString(b.to || b.phone, 40);
    const contactId = cleanString(b.contactId, 100);
    if (!to && !contactId) return fail(res, 400, "recipient_required"), true;
    const contact = contactId ? crm.contacts.find(x => x.id === contactId) : null;
    if (contactId && !contact) return fail(res, 404, "contact_not_found"), true;
    const recipient = to || contact?.phone || "";
    const recipientContact = contact || crm.contacts.find(x => phoneKey(x.phone) === phoneKey(recipient));
    const scheduledAt = b.scheduledAt || new Date().toISOString();
    if (Number.isNaN(Date.parse(scheduledAt))) return fail(res, 400, "invalid_scheduled_at"), true;
    if (recipientContact?.doNotContact === true || recipientContact?.optedOut === true)
      return fail(res, 409, "contact_opted_out"), true;
    const idempotencyKey = cleanString(req.headers["idempotency-key"] || b.idempotencyKey, 128);
    if (idempotencyKey) {
      const existing = crm.outbox.find(x => x.idempotencyKey === idempotencyKey);
      if (existing) {
        if (existing.channel !== channel || phoneKey(existing.to) !== phoneKey(recipient))
          return fail(res, 409, "idempotency_key_conflict"), true;
        return ok(res, 200, { job: existing, deduplicated: true,
          delivery: "queued_only_provider_not_configured" }), true;
      }
    }
    const job = { id: id(), channel, to: recipient, contactId: recipientContact?.id || null,
      content: cleanString(b.message || b.prompt, 4000), scheduledAt,
      status: "queued", provider: "not_configured", createdAt: new Date().toISOString(),
      ...(idempotencyKey ? { idempotencyKey } : {}) };
    if (channel !== "call" && !job.content) return fail(res, 400, "message_required"), true;
    crm.outbox.push(job);
    if (channel === "call") crm.calls.push(job);
    if (contact && channel !== "call") {
      contact.previousMessages = [...(Array.isArray(contact.previousMessages) ? contact.previousMessages : []),
        { id: job.id, channel, direction: "outbound", message: job.content, scheduledAt, status: "queued" }].slice(-100);
      contact.updatedAt = new Date().toISOString();
    }
    ctx.audit("crm." + channel + ".queued", "api-client", { jobId: job.id });
    await ctx.save();
    return ok(res, 202, { job, delivery: "queued_only_provider_not_configured" }), true;
  }

  if (resource === "outbox" && req.method === "GET")
    return ok(res, 200, { jobs: crm.outbox.slice(-300).reverse() }), true;

  if (resource === "outbox" && itemId && action === "status" && req.method === "PATCH") {
    const job = crm.outbox.find(x => x.id === itemId);
    if (!job) return fail(res, 404, "not_found"), true;
    const allowed = ["queued","sent","delivered","failed","replied","cancelled"];
    if (!allowed.includes(b.status)) return fail(res, 400, "invalid_status"), true;
    job.status = b.status; job.updatedAt = new Date().toISOString();
    if (b.providerMessageId) job.providerMessageId = cleanString(b.providerMessageId, 200);
    if (b.error) job.error = cleanString(b.error, 500);
    if (job.contactId) {
      const contact = crm.contacts.find(x => x.id === job.contactId);
      if (contact) {
        const history = Array.isArray(contact.previousMessages) ? contact.previousMessages : [];
        const entry = history.find(x => x.id === job.id);
        if (entry) entry.status = job.status;
        contact.updatedAt = new Date().toISOString();
      }
    }
    await ctx.save();
    return ok(res, 200, { job }), true;
  }

  if (resource === "webhooks" && ["sms","whatsapp"].includes(itemId) && req.method === "POST") {
    const expected = process.env.VOICX_MESSAGING_WEBHOOK_TOKEN || "";
    const supplied = req.headers["x-webhook-token"] || "";
    if (expected.length < 32 || supplied !== expected) return fail(res, 401, "invalid_webhook_token"), true;
    const incoming = { id: id(), channel: itemId, from: cleanString(b.from, 40),
      message: cleanString(b.message, 4000), receivedAt: new Date().toISOString(), providerMessageId: cleanString(b.messageId, 200) };
    crm.outbox.push({ ...incoming, direction: "inbound", status: "replied" });
    const contact = crm.contacts.find(x => x.phone === incoming.from);
    if (contact) {
      contact.previousMessages = [...(Array.isArray(contact.previousMessages) ? contact.previousMessages : []), incoming].slice(-50);
      contact.updatedAt = new Date().toISOString();
    }
    ctx.audit("crm." + itemId + ".inbound", "provider-webhook", { from: incoming.from });
    await ctx.save();
    return ok(res, 202, { accepted: true, contactId: contact?.id || null, autoReply: "requires_configured_provider_and_enabled_automation" }), true;
  }

  if (resource === "marketing" && itemId === "status" && req.method === "GET") {
    const count = channel => crm.outbox.filter(x => x.channel === channel);
    const byStatus = Object.fromEntries(["queued","sent","delivered","failed","replied","cancelled"].map(s =>
      [s, crm.outbox.filter(x => x.status === s).length]));
    return ok(res, 200, { contacts: crm.contacts.length, queued: byStatus.queued, byStatus,
      sms: count("sms").length, whatsapp: count("whatsapp").length, calls: count("call").length,
      tasksOpen: crm.tasks.filter(x => !["done","cancelled"].includes(x.status)).length,
      tasksToday: crm.tasks.filter(x => String(x.dueAt || "").slice(0,10) === new Date().toISOString().slice(0,10) && !["done","cancelled"].includes(x.status)).length,
      filters: crm.filters.length, campaigns: crm.campaigns.length, templates: crm.templates.length,
      publications: crm.publications.length, generatedAt: new Date().toISOString() }), true;
  }

  if (resource === "dashboard" && !itemId && req.method === "GET") {
    const today = new Date().toISOString().slice(0,10);
    return ok(res, 200, {
      contacts: crm.contacts.length,
      dueToday: crm.tasks.filter(x => String(x.dueAt || "").slice(0,10) === today && !["done","cancelled"].includes(x.status)),
      upcomingSends: crm.outbox.filter(x => x.status === "queued").sort((a,b) => Date.parse(a.scheduledAt) - Date.parse(b.scheduledAt)).slice(0,100),
      recentCalls: crm.calls.slice(-50).reverse(),
      recentMessages: crm.outbox.slice(-100).reverse(),
      marketing: { queued: crm.outbox.filter(x => x.status === "queued").length, failed: crm.outbox.filter(x => x.status === "failed").length }
    }), true;
  }

  if (resource === "reports" && itemId === "generate" && req.method === "POST") {
    const start = b.periodStart ? Date.parse(b.periodStart) : Date.now() - 7 * 86400000;
    const end = b.periodEnd ? Date.parse(b.periodEnd) : Date.now();
    if (!Number.isFinite(start) || !Number.isFinite(end) || start > end) return fail(res, 400, "invalid_report_period"), true;
    const jobs = crm.outbox.filter(x => Date.parse(x.createdAt) >= start && Date.parse(x.createdAt) <= end);
    const report = { id: id(), periodStart: new Date(start).toISOString(), periodEnd: new Date(end).toISOString(),
      summary: "Marketing activity report", metrics: {
        contacts: crm.contacts.length, messagesQueued: jobs.length,
        sms: jobs.filter(x => x.channel === "sms").length,
        whatsapp: jobs.filter(x => x.channel === "whatsapp").length,
        calls: jobs.filter(x => x.channel === "call").length,
        delivered: jobs.filter(x => x.status === "delivered").length,
        failed: jobs.filter(x => x.status === "failed").length,
        replies: jobs.filter(x => x.direction === "inbound").length
      }, createdAt: new Date().toISOString() };
    crm.reports.push(report); await ctx.save();
    return ok(res, 201, { report }), true;
  }

  if (resource === "tasks" && itemId === "today" && req.method === "GET") {
    const today = new Date().toISOString().slice(0,10);
    return ok(res, 200, { tasks: crm.tasks.filter(x => String(x.dueAt || "").slice(0,10) === today && !["done","cancelled"].includes(x.status)) }), true;
  }

  if (resource === "publications" && itemId === "haraj" && req.method === "POST") {
    const record = { id: id(), channel: "haraj", title: cleanString(b.title, 200), content: cleanString(b.content, 5000),
      scheduledAt: b.scheduledAt || null, status: "draft", delivery: "manual_review_required", createdAt: new Date().toISOString() };
    if (!record.title || !record.content) return fail(res, 400, "title_and_content_required"), true;
    crm.publications.push(record); await ctx.save();
    return ok(res, 202, { publication: record }), true;
  }
  if (resource === "publications" && itemId === "website" && req.method === "POST") {
    const record = { id: id(), channel: "website", title: cleanString(b.title, 200), content: cleanString(b.content, 10000),
      url: cleanString(b.url, 500), status: "draft", delivery: "website_cms_adapter_required", createdAt: new Date().toISOString() };
    if (!record.title || !record.content) return fail(res, 400, "title_and_content_required"), true;
    crm.publications.push(record); await ctx.save();
    return ok(res, 202, { publication: record }), true;
  }

  return fail(res, 404, "crm_route_not_found"), true;
}
