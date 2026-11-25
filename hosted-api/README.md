# Hosted Call API (test environment)

This is a small HTTPS API/control plane for the Voice SIP Agent. It does not open any listener on Android. The handset polls over an outbound TLS connection.

## Run locally for development

Python 3.11+:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# Set independent high-entropy CALL_API_TOKEN and DEVICE_API_TOKEN values.
set -a; . ./.env; set +a
uvicorn main:app --host 127.0.0.1 --port 8080
```

Do not expose this development server directly. Put it behind a TLS reverse proxy for a private test deployment. Set a persistent, backed-up database path and inject secrets through the hosting provider's secret manager.

## Bootstrap an organization and create a call

First provision an organization using the separate bootstrap secret. This is an owner-only provisioning operation; do not expose the bootstrap token to Android devices.

```sh
curl --fail-with-body https://YOUR_TEST_HOST/v1/organizations/bootstrap \
  -H "Authorization: Bearer $ORG_BOOTSTRAP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test organization","slug":"test-org","ownerUserId":"owner-1"}'
```

Store the one-time `apiToken` response securely and keep the returned `organizationId`. Submit calls with the organization credential:

```sh
curl --fail-with-body https://YOUR_TEST_HOST/v1/organizations/$ORG_ID/calls \
  -H "Authorization: Bearer $ORG_API_TOKEN" \
  -H "X-Organization-ID: $ORG_ID" \
  -H "Idempotency-Key: $(python -c 'import uuid; print(uuid.uuid4())')" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+9677XXXXXXX"}'
```

The API returns HTTP 202 and a call ID. Read it through `GET /v1/admin/calls/{id}` with the same organization credential. Enroll an authenticated device with a short-lived enrollment token; devices poll `GET /v1/device/call-jobs/next` using their individual device token.

## Containerized test deployment (not deployed by this change)

1. Copy `.env.example` to `.env` on the server. Generate two independent random tokens (one caller token and one handset token), then replace both placeholders. Set `ALLOWED_PREFIXES` to only the numbers permitted for this test.
2. Copy `Caddyfile.example` to `Caddyfile` and replace `calls.example.com` with a DNS name whose A/AAAA records point to the server.
3. Run `docker compose up -d --build` from `hosted-api/`. Caddy terminates HTTPS; the API container is reachable only on the private Compose network. Port 8080 is not published.
4. Check `https://YOUR_DOMAIN/health`. Bootstrap the organization, issue a one-use enrollment token, enroll each Android device, and configure the app with its individual device token. Do not put the bootstrap or owner credential in the handset.
5. Back up the `hosted-call-data` volume and rotate tokens through the host's secret-management process. This single-node SQLite Compose setup is not a Vercel or multi-instance deployment.

Generate independent tokens, for example:
```sh
openssl rand -hex 32
openssl rand -hex 32
```

Do not commit `.env`, TLS private keys, or database files. The Compose template is deployment documentation only; no VPS, DNS, billing account, or public service was created by this work.

## Device protocol

The Android agent authenticates with the individual device token returned by `POST /v1/device/enroll` and polls `GET /v1/device/call-jobs/next` over HTTPS. Jobs are claimed for the owning organization/device and reported through `POST /v1/device/tenant-calls/{id}/state`. Never use the legacy shared `DEVICE_API_TOKEN` for a tenant device.

## Safety notes

- Set `ALLOWED_PREFIXES` to the exact destinations approved for testing. Empty means all calls are rejected with `allowlist_not_configured`.
- The shared-token compatibility routes (`/v1/calls`, `/v1/device/jobs/next`, and `/v1/device/calls/*`) are disabled by default. Keep `ENABLE_LEGACY_CALL_API=false` and `ENABLE_LEGACY_DEVICE_API=false`; only enable them temporarily in an isolated migration environment.
- Bootstrap organizations only with a separate high-entropy `ORG_BOOTSTRAP_TOKEN`. Store returned organization/device tokens in a secret manager or Android Keystore; they are shown once.
- Owners can transfer ownership through `POST /v1/organizations/{organization_id}/members/{user_id}/transfer-ownership`. Member credentials can be rotated or revoked through the corresponding `/rotate-credential` and `/revoke` endpoints. The former owner is downgraded to admin; the new owner can revoke the former owner later if required.
- `GET /health/database` checks the configured active backend. With MongoDB configured it returns `backend=mongodb`; local development without Atlas uses SQLite.
- API rate limits use atomic MongoDB fixed-window counters when MongoDB is configured (IP + credential + sensitive-route dimensions). Requests fail closed with 503 if the shared limiter is unavailable.
- Organization, membership, credential, branch, device enrollment, heartbeat, event, command, audit, and tenant call lifecycle routes select MongoDB repositories when Atlas is configured. Local development without Atlas retains the SQLite compatibility backend.
- Vercel startup requires MongoDB and never initializes SQLite. Do not set `ALLOW_UNSAFE_SQLITE_ON_VERCEL=true`; the override is rejected.
- The isolated `MONGODB_TEST_URI` / `MONGODB_TEST_DATABASE` pair is accepted as a runtime backend only for non-production Vercel environments. Production requires explicit `MONGODB_URI` and `MONGODB_DATABASE` values.
- Administrative member and device changes are recorded in `admin_audit_events` and can be read by organization owners/admins at `GET /v1/admin/audit`. The audit feed never includes raw API tokens.
- Android must independently reject emergency numbers using `TelephonyManager.isEmergencyNumber` before dialing.
- Keep queue/call timeouts bounded. The included Caddy example caps request bodies at 64 KB; add network-level firewall and source allow-lists appropriate to your SIP topology.
- Rotate both API tokens independently. Do not put secrets in Git, APK resources, query strings, or logs.
- This API is an initial test control plane, not a production-hardened multi-tenant service. Use a managed database, durable job leases, audit retention, monitoring, and per-principal credentials before production.


## MongoDB route migration

The active tenant API routes now use MongoDB repositories when MongoDB is configured:

- Organization bootstrap and credential authentication.
- Member creation, ownership transfer, suspension/revocation, and credential rotation.
- Enrollment-token issuance and atomic single-use device enrollment.
- Device heartbeats, events, activity, branch administration, and presence.
- Device command creation, atomic delivery claim, and result acknowledgement.
- Tenant call creation, idempotency, exclusive organization queue slot, device lease claim, state transitions, replay safety, call listing, and latency metrics.
- Shared rate-limit windows and organization audit events.

Multi-document administrative operations and device enrollment use MongoDB transactions. The MongoDB startup path creates the required indexes and does not initialize SQLite. The legacy shared-token routes remain disabled on MongoDB so they cannot accidentally fall back to ephemeral SQLite.

Local development without MongoDB still uses SQLite for compatibility and unit testing. The Vercel runtime requires an explicit MongoDB backend and fails closed if it is unavailable.

## Atlas test environment

The isolated database is `voice_sip_agent_ci`. Vercel Preview can use `MONGODB_TEST_URI` and `MONGODB_TEST_DATABASE` as a fallback when `VERCEL_ENV` is not `production`. Production must use separately provisioned `MONGODB_URI` and `MONGODB_DATABASE` values. The live Atlas GitHub Actions test remains skipped until repository Actions secrets are configured; local/mocked repository tests do not replace the live Atlas test.

This branch is still a Draft PR. CI and repository tests do not certify live carrier calls, SIP/RTP audio, target-device behavior, backup/restore, or production readiness. Do not merge or deploy Production based only on these tests.

## Adasms SMS API (server-side only)

The Hosted Call API can send SMS through the existing Adasms provider endpoint. The provider key is read only from the server environment variable `ADASMS_API_KEY`; it is never returned by an API route and must never be included in Android configuration, Telegram messages, or Git.

Set these values in Vercel Project Settings → Environment Variables:

- `ADASMS_API_KEY`: the newly rotated Adasms API key, stored as a Secret
- `SMS_ALLOWED_PREFIXES=+9677`: allowed destination prefixes
- `SMS_DEVICE_SEND_ENABLED=false`: keep device sending disabled until explicitly approved
- `SMS_DEVICE_ALLOWLIST=`: add only approved device IDs if device sending is later enabled

Send an SMS through the organization API (owner/admin credential only):

```sh
curl --fail-with-body -X POST \
  "https://YOUR_HOSTED_API/v1/organizations/YOUR_ORG_ID/sms/send" \
  -H "Authorization: Bearer $ORG_API_TOKEN" \
  -H "X-Organization-ID: $ORG_ID" \
  -H "Idempotency-Key: $(python -c 'import uuid; print(uuid.uuid4())')" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+9677XXXXXXX","message":"Your message","consentConfirmed":true}'
```

The device endpoint `POST /v1/device/sms/send` uses the enrolled device token and remains disabled unless `SMS_DEVICE_SEND_ENABLED=true` and the device ID is explicitly listed in `SMS_DEVICE_ALLOWLIST`. The Android `HostedCallApiClient.sendSms(...)` method is only a client adapter; no SMS is sent unless an application flow invokes it and the server-side gates are enabled.

Each request requires recipient consent and an `Idempotency-Key`. Delivery attempts are recorded in the tenant-scoped MongoDB `sms_messages` collection (or the local SQLite test database). The API stores status, masked phone in responses, provider message ID, timestamps, source, and actor. A repeated key with the same payload does not send a second SMS; reusing it with a different payload returns HTTP 409. Failed sends are not retried automatically; use a new key only after checking the provider and message history.

Check the organization message history with `GET /v1/admin/sms?limit=50`, using the same organization bearer token and `X-Organization-ID` header. No test sends or real outbound SMS are performed by the automated tests.


## WhatsApp (Evolution API + n8n Cloud)

The isolated Evolution adapter and webhook contract are documented in [docs/evolution-n8n-whatsapp.md](../docs/evolution-n8n-whatsapp.md). Vercel hosts this FastAPI bridge only; the persistent Evolution API server must run on a separate container-capable host. Baileys and official Cloud API use separate Evolution instances. Outbound sends remain disabled unless `EVOLUTION_SEND_ENABLED=true`, require explicit consent confirmation, a destination allow-list, and an idempotency key. No real messages are sent by CI.

Configure the Evolution API URL/key, both instance names, per-channel webhook secrets, instance-to-organization map, and n8n Cloud webhook URL/token as Vercel Secrets. Never commit those values. Import `n8n/whatsapp-events.workflow.json` into n8n Cloud, configure its Header Auth credential, and keep the workflow inactive until reviewed and explicitly activated.


## Telegram main control bot

The single-bot webhook and approval-only command flow are documented in [docs/telegram-main-bot.md](../docs/telegram-main-bot.md). The FastAPI webhook is served by the existing Vercel Hosted API at `POST /v1/telegram/webhook`; it does not require a separate always-on server.

Set `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, and `TELEGRAM_ALLOWED_USER_IDS` as encrypted Vercel environment variables. An empty allowlist denies everyone. Do not run Android `getUpdates` long polling and this webhook with the same bot token at the same time; PR #13 remains untouched and open.

The current Telegram commands can create approval requests, but confirmation only records `approved_not_executed`. External sends, calls, and publishing are not executed. Live Telegram webhook configuration, external actions, and production deployment have not been performed.
