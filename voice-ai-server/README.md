# Voice Realtime Relay

A small server-side WebSocket relay for the Android SIP gateway. The OpenAI API key stays on the server and is never shipped in the APK.

## Requirements

- Node.js 22+
- TLS termination in front of this service (clients must use `wss://`)
- Server environment variables from `.env.example`
- A firewall that exposes only the TLS reverse proxy, not an unauthenticated relay port

## Run

```sh
cd voice-ai-server
npm install
npm run check
OPENAI_API_KEY='...' VOICE_GATEWAY_TOKEN='a-long-random-secret' npm start
```

Set secrets through the host's secret manager or environment configuration. Do not commit a real `.env` file. Configure the Android client with the WSS URL and the gateway token stored using Android Keystore-backed encryption.

## Protocol

- `GET /healthz` reports only whether required server configuration exists.
- `GET wss://HOST/v1/realtime` requires `Authorization: Bearer <VOICE_GATEWAY_TOKEN>`.
- The relay opens a server-authenticated WebSocket to the OpenAI Realtime API and forwards text events in both directions.
- The client is responsible for sending the Realtime session configuration and audio events. The API key is attached only to the upstream connection.
- Audio/transcript payloads, bearer tokens, and phone numbers are never logged.

## Limits

- 10 concurrent sessions by default, max 3 per source IP.
- 30-minute maximum session lifetime by default.
- 1 MiB maximum WebSocket message.
- No persistent audio or transcript storage.
- This relay does not answer SIP calls itself and does not implement the Android PCM/G.711 adapter. Those remain separate integration steps.

## Production hardening required

Run behind a TLS reverse proxy; restrict ingress to the proxy; use a high-entropy per-install token or add token rotation; monitor connection counts without payload logging; and configure provider spend limits. Add account-level identity and per-device revocation before exposing it to multiple customers.
