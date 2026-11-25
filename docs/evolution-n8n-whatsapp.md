# Evolution API + MongoDB Atlas + n8n Cloud

This integration is isolated to `feature/evolution-n8n-whatsapp-bridge`, based on the existing `feature/hosted-call-api` backend. It does not change Android SIP/SIM media, Telegram, SMS, or existing call-control routes.

## Hosting boundary

- Vercel runs the existing FastAPI Hosted API and this lightweight Evolution HTTP adapter.
- The Evolution API server itself must run on a persistent container/runtime with durable database/session storage and a stable public HTTPS URL. Do not deploy the full Baileys session engine as Vercel Serverless Functions.
- n8n Cloud receives normalized inbound/status events using its Production Webhook URL.
- MongoDB Atlas remains the system of record for tenant-scoped WhatsApp messages and webhook event deduplication.

Evolution supports both Baileys and the official WhatsApp Cloud API. Configure two distinct Evolution instances and route each send explicitly with `channel=baileys` or `channel=cloud_api`. Baileys is WhatsApp-Web based and is not equivalent to Meta's official Cloud API; use Cloud API for official business messaging and policy-compliant templates where required.

## Vercel environment variables

Set these as encrypted Vercel environment variables (Preview first). Never commit real values:

- `EVOLUTION_API_URL`: HTTPS base URL of the separately hosted Evolution API.
- `EVOLUTION_API_KEY`: Evolution server API key.
- `EVOLUTION_BAILEYS_INSTANCE`, `EVOLUTION_CLOUD_INSTANCE`: the two configured instance names.
- `EVOLUTION_SEND_ENABLED=false`: remains false until the owner explicitly approves live sending.
- `EVOLUTION_WEBHOOK_SECRET_BAILEYS`, `EVOLUTION_WEBHOOK_SECRET_CLOUD`: independent random webhook secrets.
- `EVOLUTION_INSTANCE_ORG_MAP`: JSON object mapping instance names to organization IDs, e.g. `{"wa-web":"<organization-id>","wa-cloud":"<organization-id>"}`.
- `WHATSAPP_ALLOWED_PREFIXES`: explicit destination prefix allow-list, default `+9677`.
- `N8N_WHATSAPP_WEBHOOK_URL`: n8n Cloud Production Webhook URL.
- `N8N_WHATSAPP_WEBHOOK_TOKEN`: independent random token sent as `X-Voicx-Webhook-Token`.

## Evolution webhook configuration

For each Evolution instance, configure its webhook URL to:

- `https://<your-vercel-host>/v1/webhooks/evolution/baileys`
- `https://<your-vercel-host>/v1/webhooks/evolution/cloud_api`

Send `X-Evolution-Webhook-Secret` with the matching per-channel secret. Enable message upsert and message status/update events. The API rejects unknown instances and invalid webhook secrets, deduplicates exact event deliveries, stores normalized text/status metadata (not media base64), and scopes each record to the organization mapped to that instance.

## API

Owner/admin send:

```http
POST /v1/organizations/{organization_id}/whatsapp/send
Authorization: Bearer <organization-api-token>
X-Organization-ID: <organization_id>
Idempotency-Key: <unique-random-value>
Content-Type: application/json

{
  "phone": "+9677XXXXXXX",
  "message": "Message text",
  "channel": "baileys",
  "consentConfirmed": true
}
```

Use `cloud_api` for the official Cloud API instance. The endpoint requires explicit consent confirmation, applies the destination prefix allow-list, stores tenant-scoped idempotency records, and never automatically retries an uncertain provider timeout. Sending is disabled unless `EVOLUTION_SEND_ENABLED=true`.

- `GET /v1/admin/whatsapp`: tenant-scoped message history (masked destination; no message body).
- `POST /v1/webhooks/evolution/{channel}`: authenticated Evolution webhook receiver.

## n8n Cloud

Import `n8n/whatsapp-events.workflow.json` into n8n Cloud. The exported workflow is intentionally inactive. Configure the Production Webhook URL and shared token in Vercel, then activate the workflow only after verifying the authentication header and downstream CRM mapping. n8n provides separate Test and Production webhook URLs; the Production URL is registered when the workflow is published.

The workflow only normalizes the event envelope. It does not send messages, trigger calls, or mutate CRM customer fields. Add future CRM writes as tenant-scoped operations through the existing Hosted API, not by granting n8n unrestricted direct access to Atlas.

## Safety and verification

- No real WhatsApp messages are sent by tests.
- No Evolution instance, Meta app, phone number, n8n workflow, Vercel environment variable, or production deployment is created/changed by these commits.
- Do not set `EVOLUTION_SEND_ENABLED=true` until both channels, consent/suppression policy, webhook authentication, tenant mapping, and the user's separate live-send approval are complete.
- The current send path requires `consentConfirmed=true`; a durable consent/suppression registry and WhatsApp template/window policy are additional release gates before production.
- Webhook payload storage excludes media blobs and stores a normalized envelope. n8n forwarding is best-effort and returns a forwarding flag; persisted events remain available for later replay tooling.
