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
