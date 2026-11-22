# Hosted Call API — implementation plan

This feature is developed only on `feature/hosted-call-api`, based on `feat/sip-to-cellular-outbound`. Do not merge or deploy it without explicit approval.

## Security and call flow

1. The Android agent establishes an outbound HTTPS long-poll/WebSocket connection to the hosted control plane. The handset exposes no listener or public port.
2. The hosted API authenticates callers with a dedicated, revocable API token. SIP credentials and any legacy CallGate credentials are never accepted as API credentials.
3. The API validates the destination, applies E.164 normalization, emergency-number rejection, per-token rate limits, allow-list checks, request expiry, and idempotency.
4. The handset accepts one leased job at a time, initiates the cellular call, and waits for Telecom `STATE_ACTIVE`.
5. Only after the cellular leg is active does the agent place the SIP leg to the existing saved Linphone destination. The existing SIP account and sealed credentials remain unchanged.
6. Once both legs are connected and both audio ports are available, the existing media bridge cross-connects audio in both directions. Either leg ending tears down the other.
7. State transitions are persisted by the API: `queued`, `ringing_cellular`, `cellular_answered`, `calling_sip`, `bridged`, `failed`, `ended`.

## HTTP contract

- `POST /v1/calls` — Bearer API token + mandatory `Idempotency-Key`; JSON `{"phoneNumber":"7XXXXXXXX"}`; returns `202` and a call ID.
- `GET /v1/calls/{id}` — returns current state and safe timestamps/reason codes.
- `POST /v1/devices/{deviceId}/heartbeat` — authenticated handset heartbeat and capability state.
- Device job delivery is over an outbound authenticated TLS connection; never expose an Android-local HTTP endpoint.

## Operational requirements

- HTTPS only; secrets are stored in a server-side secret manager and Android Keystore-backed encrypted preferences.
- Never log bearer tokens, SIP passwords, full destination numbers, or request bodies.
- Idempotency keys are scoped to the API principal and retained beyond the maximum request lifetime.
- Enforce queue TTL, cellular answer timeout, SIP answer timeout, maximum call duration, per-token rate limit, and one active call per handset.
- Fail closed when the emergency-number classifier or allow-list cannot be evaluated.
- API tokens are separate from SIP registration credentials. The app's existing SIP account configuration is not rewritten by this feature.

## Current implementation status

This document defines the target contract. A working release requires the hosted service, Android job client, runtime call orchestration, automated tests, and physical-device validation. Do not claim the feature is release-ready until the HONOR Android 16 test matrix and signed APK are complete.
