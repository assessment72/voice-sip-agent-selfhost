function send(res, status, value) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(value));
}

function clean(value, max = 500) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

export async function handleMongoCrmRoute(req, res, url, ctx) {
  const path = url.pathname;
  if (!path.startsWith("/api/v1/crm/")) return false;
  const parts = path.split("/").filter(Boolean).slice(3);
  const [resource, itemId, action] = parts;
  const method = req.method;

  // The Mongo-backed routes are deliberately unavailable unless MongoDB has
  // connected successfully. Never silently write CRM data to the JSON device
  // state file when an operator expects persistent CRM storage.
  // Without a configured MongoDB connection, leave the legacy JSON-backed
  // CRM routes available for backward compatibility.
  if (!ctx.crmStore) return false;
  if (!ctx.authAdmin(req)) {
    send(res, 401, { error: "unauthorized" });
    return true;
  }

  try {
    const body = ["POST", "PUT", "PATCH"].includes(method) ? await ctx.body(req) : {};
    if (resource === "contacts" && method === "GET" && !itemId) {
      const filters = Object.fromEntries(url.searchParams.entries());
      if (filters.neverContacted === "true") filters.neverContacted = true;
      return send(res, 200, { contacts: await ctx.crmStore.searchContacts(filters) }), true;
    }
    if (resource === "contacts" && itemId === "search" && method === "POST") {
      return send(res, 200, { contacts: await ctx.crmStore.searchContacts(body) }), true;
    }
    if (resource === "contacts" && !itemId && method === "POST") {
      const contact = await ctx.crmStore.addContact(body);
      return send(res, 201, { contact }), true;
    }
    if (resource === "contacts" && itemId && !action && method === "GET") {
      const contact = await ctx.crmStore.getContact(itemId);
      return contact ? (send(res, 200, { contact }), true) : (send(res, 404, { error: "contact_not_found" }), true);
    }
    if (resource === "contacts" && itemId && !action && method === "PATCH") {
      const contact = await ctx.crmStore.updateContact(itemId, body);
      return send(res, 200, { contact }), true;
    }
    if (resource === "contacts" && itemId && !action && method === "DELETE") {
      await ctx.crmStore.archiveContact(itemId);
      return send(res, 200, { ok: true }), true;
    }
    if (resource === "contacts" && itemId && action === "interactions" && method === "GET") {
      return send(res, 200, { interactions: await ctx.crmStore.listInteractions(itemId, url.searchParams.get("limit")) }), true;
    }
    if (resource === "interactions" && !itemId && method === "POST") {
      return send(res, 201, await ctx.crmStore.addInteraction(body)), true;
    }
    if (resource === "follow-ups" && method === "GET") {
      return send(res, 200, { followUps: await ctx.crmStore.listFollowUps(url.searchParams.get("today") === "true") }), true;
    }
    if (resource === "import" && method === "POST") {
      return send(res, 200, await ctx.crmStore.importContacts(body.rows, body.dryRun !== false)), true;
    }
    return false;
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    send(res, status, { error: status === 500 ? "crm_operation_failed" : clean(error.message, 120) });
    return true;
  }
}
