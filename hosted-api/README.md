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

## Device protocol

The Android agent authenticates separately with `DEVICE_API_TOKEN` and polls `GET /v1/device/jobs/next` over HTTPS. It reports only the documented state transitions through `POST /v1/device/calls/{id}/state`. Device token and caller token must never be the same.

## Safety notes

- Set `ALLOWED_PREFIXES` to the exact destinations approved for testing. Empty means unrestricted at the API layer; do not use that in a deployed environment.
- Android must independently reject emergency numbers using `TelephonyManager.isEmergencyNumber` before dialing.
- Keep queue/call timeouts bounded; add a reverse-proxy request-size limit and IP allow-list where practical.
- Rotate both API tokens independently. Do not put secrets in Git, APK resources, query strings, or logs.
- This API is an initial test control plane, not a production-hardened multi-tenant service. Use a managed database, durable job leases, audit retention, monitoring, and per-principal credentials before production.
