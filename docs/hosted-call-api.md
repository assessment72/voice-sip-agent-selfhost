# Hosted Call API and cellular-first SIP bridge

This work is isolated to `feature/hosted-call-api`, based on `feat/sip-to-cellular-outbound`. It must not be merged or deployed without explicit approval.

## What is integrated from CallGate

The call-control workflow is integrated into Voice SIP Agent. A separate CallGate installation and its local HTTP listener are not required. The legacy CallGate Basic Auth/local-server model is intentionally replaced by a hosted HTTPS API with separate caller and handset credentials.

The CallGate call-start operation maps to `POST /v1/calls`. Call progress is represented by persisted call states. The existing cellular endpoint, SIP user agent, and bidirectional media bridge in Voice SIP Agent perform the actual call and audio work.

CallGate's USSD and generic webhook-management features are not part of this cellular-to-Linphone call flow.

## Call flow

1. The handset uses a bounded 20-second HTTPS long poll on a dedicated network worker. A separate serial network worker sends state updates, so a slow long poll or state POST cannot block the gateway/call-control executor. When a job arrives the server responds immediately; after each response the handset starts the next long poll. The handset opens no listener, and no local API port is exposed.
2. A caller authenticates with a dedicated `CALL_API_TOKEN`. The phone authenticates with a different `DEVICE_API_TOKEN`. Neither credential is a SIP or legacy CallGate password.
3. The server validates the phone number, requires an explicit allow-list, rejects malformed/emergency short codes, applies rate limits and idempotency, and queues one job.
4. The handset validates the number again with Android's emergency-number API, then places the cellular leg through the app's configured cellular endpoint.
5. The SIP leg is deliberately not created until Telecom reports the cellular leg as connected/active.
6. The SIP leg targets the currently registered Linphone peer when using the built-in registrar, or the existing saved SIP target setting in external-PBX mode.
7. Once both legs are connected and both media ports are ready, the existing Voice SIP Agent media bridge cross-connects audio in both directions. Either leg ending tears down the other.
8. The app reports each state to the hosted API. A 30-minute local call-duration guard is also applied.

## HTTP API

### Caller endpoints

- `POST /v1/calls` — requires `Authorization: Bearer <CALL_API_TOKEN>`, `Idempotency-Key`, and JSON `{"phoneNumber":"7XXXXXXXX"}` or an E.164 number such as `+9677XXXXXXXX`. Returns HTTP 202 with a call ID.
- `GET /v1/calls/{call_id}` — returns state, timestamps, expiry, and a safe reason code. It does not return the destination number.

### Handset endpoints

- `GET /v1/device/jobs/next?wait=20` — requires `Authorization: Bearer <DEVICE_API_TOKEN>`; holds the request for up to 20 seconds, returns immediately when a queued job arrives, and returns `{\"job\":null}` when the wait expires. The wait is bounded to 25 seconds. Jobs use a short claim lease.
- `POST /v1/device/calls/{call_id}/state` — reports the next permitted state and an optional non-sensitive reason code.

### State sequence

`queued → ringing_cellular → cellular_answered → calling_sip → bridged → ended`

Any failure before a successful bridge ends in `failed`. The server also expires queued work, cellular/SIP answer waits, and calls exceeding the configured maximum duration.

## Android configuration

1. Install the Voice SIP Agent build and complete its existing call-access / Shizuku / audio setup.
2. Keep the existing SIP account and credentials unchanged.
3. In Settings, enter the hosted API base URL beginning with `https://`.
4. Enter the handset's `DEVICE_API_TOKEN`. It is encrypted using an Android Keystore key; it is not written to logs.
5. Keep the existing Linphone peer registration active. In built-in registrar mode the app uses the registered peer URI; in external-PBX mode it uses the existing saved outbound SIP target/extension.
6. Leave the hosted API URL blank to disable hosted job polling.

The cellular endpoint currently uses the SIM 2 phone account, matching the existing outbound cellular implementation in this branch. Confirm that the intended SIM is active in slot 2 before live testing.

## Test server setup

See [hosted-api/README.md](../hosted-api/README.md) for the Docker Compose + Caddy HTTPS template, environment variables, token generation, and API examples. The template is not deployed by this change. Do not commit `.env`, database files, or TLS private keys.

## Security boundaries

- No Android-local HTTP server is exposed.
- HTTPS is mandatory in the handset client; redirects are disabled to prevent bearer-token forwarding.
- Caller and device bearer tokens are independent and separate from SIP credentials.
- The device token is sealed in Android Keystore-backed encrypted preferences.
- Destination allow-list must be explicitly configured; an empty allow-list fails closed.
- Idempotency keys are scoped to the caller principal; reusing a key with a different destination returns conflict.
- Request bodies, bearer tokens, SIP passwords, and full destination numbers are not logged or returned by the status endpoint.
- The handset rejects emergency numbers again immediately before dialing.

## Validation status

Automated hosted API tests cover authentication, number validation/normalization, allow-list enforcement, idempotency, rate limiting, job claim recovery, state transitions, ownership checks, long-poll timeout, and prompt wakeup when a job is queued.

Gateway unit tests cover cellular-first ordering, no SIP leg when the cellular call is unanswered, two-way media bridging, and teardown. Android CI runs JVM/Android unit tests and creates a debug APK artifact.

A release is **not ready** until the current Android workflow succeeds, a signed APK is produced, and the complete live-device test matrix passes on the target HONOR Android 16 handset: cellular answer/no-answer, loss of network, SIP failure, call termination from either side, audio in both directions, and duration timeout. CI cannot substitute for those physical-device checks.


## Responsiveness and telemetry

- Long polling is performed on a dedicated Android network executor; call control, Telecom callbacks, SIP events, and gateway configuration remain on the gateway executor.
- State writes are queued on a separate single-thread network executor so they cannot sit behind the outstanding long poll. Their order is preserved.
- HTTP requests retain bounded connect/read timeouts. The long-poll read timeout is 28 seconds for a maximum 25-second server wait.
- The server stores per-call state event timestamps against the opaque call ID. Destination numbers are not returned by status queries and credentials are never logged.
- FCM is intentionally not required in this stage. It may be added later as a best-effort wake hint; HTTPS long polling remains the authoritative job delivery path.
- The backend long-poll endpoint currently checks the queue at 250 ms intervals while waiting. The handset itself does not issue repeated short polling requests.
