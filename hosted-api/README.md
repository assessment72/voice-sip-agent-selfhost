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

## Create a call

```sh
curl --fail-with-body https://YOUR_TEST_HOST/v1/calls \
  -H "Authorization: Bearer $CALL_API_TOKEN" \
  -H "Idempotency-Key: $(python -c 'import uuid; print(uuid.uuid4())')" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+9677XXXXXXX"}'
```

The API returns HTTP 202 and a call ID. Poll `GET /v1/calls/{id}` with the same API token.

## Containerized test deployment (not deployed by this change)

1. Copy `.env.example` to `.env` on the server. Generate two independent random tokens (one caller token and one handset token), then replace both placeholders. Set `ALLOWED_PREFIXES` to only the numbers permitted for this test.
2. Copy `Caddyfile.example` to `Caddyfile` and replace `calls.example.com` with a DNS name whose A/AAAA records point to the server.
3. Run `docker compose up -d --build` from `hosted-api/`. Caddy terminates HTTPS; the API container is reachable only on the private Compose network. Port 8080 is not published.
4. Check `https://YOUR_DOMAIN/health`. Configure the Android app with that HTTPS base URL and the device token only. Use the separate caller token for clients that submit `POST /v1/calls`.
5. Back up the `hosted-call-data` volume and rotate tokens through the host's secret-management process.

Generate independent tokens, for example:
```sh
openssl rand -hex 32
openssl rand -hex 32
```

Do not commit `.env`, TLS private keys, or database files. The Compose template is deployment documentation only; no VPS, DNS, billing account, or public service was created by this work.

## Device protocol

The Android agent authenticates separately with `DEVICE_API_TOKEN` and polls `GET /v1/device/jobs/next` over HTTPS. Jobs are claimed with a short lease so an offline handset does not permanently strand a queued job. It reports only the documented state transitions through `POST /v1/device/calls/{id}/state`. Device token and caller token must never be the same.

## Safety notes

- Set `ALLOWED_PREFIXES` to the exact destinations approved for testing. Empty means unrestricted at the API layer; do not use that in a deployed environment.
- Android must independently reject emergency numbers using `TelephonyManager.isEmergencyNumber` before dialing.
- Keep queue/call timeouts bounded; add a reverse-proxy request-size limit and IP allow-list where practical.
- Rotate both API tokens independently. Do not put secrets in Git, APK resources, query strings, or logs.
- This API is an initial test control plane, not a production-hardened multi-tenant service. Use a managed database, durable job leases, audit retention, monitoring, and per-principal credentials before production.


## MongoDB Atlas persistence foundation (Preview preparation)

The branch now includes `mongodb_store.py` and the PyMongo SRV driver dependency. The module provides:
- a cached PyMongo client suitable for warm Vercel function instances, with a conservative per-instance pool;
- explicit database selection through `MONGODB_DATABASE`;
- idempotent index definitions for calls, idempotency, tenant identities, devices, commands, events, heartbeats, and rate-limit windows;
- a ping helper and transaction wrapper.

**Important:** this is a persistence foundation only. The current API routes in `main.py` still use SQLite (`DATABASE_PATH`), and startup still executes SQLite DDL and the SQL migration. The MongoDB module is not yet wired into request handlers. Setting `MONGODB_URI` does not change the active storage backend.

Do not deploy this revision as a MongoDB-backed production service. The next migration increment must port the call lifecycle and atomic job-claim path first, with race-condition tests, before migrating organization/device control-plane endpoints. The final cutover must remove SQLite startup and verify transaction behavior on the selected Atlas topology.
