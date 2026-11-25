# Smart Calling AI + Apify + Central CRM

Status: approved architecture; implementation must remain isolated from the existing SIP/SIM audio bridge.

## Non-negotiable compatibility constraints

- Do not replace, refactor, or change the existing SIP registrar, SIP/RTP transport, SIM/telephony capture, audio routing, speaker/microphone handling, or call takeover behavior as part of this integration.
- Keep Gemini Live on Vertex AI behind the server-side integration. The Android APK must not contain Vertex AI, Google, Apify, Telegram, or CRM service credentials.
- Apify is an asynchronous enrichment source only. No Apify request, DNS lookup, scraping task, CRM write, or Telegram operation may run on the real-time audio thread or block call setup, RTP, audio callbacks, or Gemini audio streaming.
- Do not send call audio or raw transcripts to Apify. Send only the minimum business-search query (for example, a company name or public business phone) after applying privacy and authorization rules.
- Do not automatically place calls, send SMS/WhatsApp messages, or contact scraped leads. Human approval is required for outbound contact.
- Keep this work on a feature branch and behind server-side feature flags. No merge, production deployment, live credentials, or real customer outreach without separate approval.

## Current repository baseline

The repository is an Android/Kotlin SIP gateway. Its README documents a built-in SIP registrar and call-audio bridge, but does not document a production CRM/Apify backend. Therefore the AI/CRM integration is designed as an independent server service, not as new work inside the Android audio engine.

## Target architecture

```text
Android gateway / SIP-SIM bridge
   | existing call lifecycle + permitted metadata/events only
   v
Smart Calling API (independent server)
   |-- Call Session service
   |-- Gemini Live / Vertex AI adapter
   |-- Transcript + structured extraction worker
   |-- Customer identity resolution
   |-- Central CRM (PostgreSQL recommended)
   |-- Job queue (Redis-backed worker recommended)
   |-- Apify adapter (async Actor runs + webhook/poll completion)
   |-- Telegram report adapter
   v
Admin/API: customers, calls, enrichment, consent, audit
```

The audio plane and data plane are separate. The Android audio path remains local to its existing SIP/SIM implementation. The server receives only the minimum events/data required by the approved AI workflow. Gemini media streaming is handled independently from CRM and Apify jobs.

## Call workflow

### Before a call
1. Normalize the caller/callee number to E.164 where possible.
2. Query the central CRM by normalized number and known aliases.
3. If no sufficiently reliable match exists, enqueue a business lookup only when there is a legitimate purpose and the configured policy allows it.
4. Return cached, clearly labelled customer/company context to the call UI. Never delay ringing or audio while a lookup runs.

### During a call
1. Create a call-session record and associate it with the current device/user/tenant.
2. Keep Gemini Live streaming independent from all CRM and Apify work.
3. Consume permitted Gemini structured events/transcript segments asynchronously.
4. Extract candidate fields (name, company, role, need, requested follow-up) with confidence and provenance.
5. Enqueue Apify enrichment on company name/domain/public business phone; deduplicate identical jobs and apply per-tenant quotas.
6. Display enrichment as pending/partial/verified; never present a guess as confirmed identity.

### After a call
1. Finalize the call record and structured summary.
2. Resolve or create a customer only after deduplication and confidence checks.
3. Attach transcript/summary according to retention and consent settings.
4. Merge enrichment results as sourced observations, not authoritative identity facts.
5. Send a Telegram report after server-side policy checks; redact sensitive data by default.
6. Retry failed enrichment/report jobs without affecting call media.

## Central CRM data model

Recommended relational entities:
- tenants / users / devices
- customers
- customer_phone_numbers (E.164, type, verification status, source)
- companies (name, domain, country, industry)
- customer_company_roles (role, confidence, source)
- call_sessions (provider/session IDs, direction, start/end, result)
- call_events and extracted_facts (field, value, confidence, source, observed_at)
- transcripts / summaries (optional, access-controlled, retention-managed)
- enrichment_jobs (provider, actor, input hash, status, attempts, cost, timestamps)
- enrichment_observations (value, source URL, fetched_at, confidence)
- consent_records / suppression_list
- audit_events and tenant_usage

Use tenant isolation in every query and write. A phone number is a lookup key, not proof of a person's identity. Keep raw phone numbers encrypted at rest where feasible; use a keyed HMAC index for exact lookup. Restrict access to transcripts and exports.

## Server API (proposed)

- `POST /v1/call-sessions` — create session; idempotency key required.
- `POST /v1/call-sessions/{id}/events` — append permitted lifecycle/AI events.
- `POST /v1/call-sessions/{id}/complete` — finalize summary and enqueue post-call work.
- `GET /v1/customers/lookup?phone=...` — authorized tenant-scoped lookup.
- `GET /v1/customers/{id}` — customer profile and call history.
- `POST /v1/enrichment/search` — enqueue a company/business lookup.
- `POST /v1/webhooks/apify` — receive authenticated Apify completion callbacks.
- `GET /v1/enrichment/jobs/{id}` — retrieve job state/results.
- `GET /v1/reports/calls/{id}` — retrieve report.

All endpoints require authentication, authorization, rate limits, input validation, audit logging, and tenant scoping. Webhooks require signature/token verification and replay protection.

## Apify integration rules

- Use the Apify API token only as a server-side secret (Secret Manager/environment secret); never put it in the APK, repository, logs, or Telegram.
- Prefer specific business/company Actors and public business information. Validate the selected Actor's input/output schema and pricing before enabling it.
- Use asynchronous Actor runs with bounded timeouts, concurrency limits, cache, idempotency, retry/backoff, and per-tenant spend caps.
- Store source URLs and retrieval timestamps for each observation.
- Respect site terms, robots/access restrictions, privacy law, opt-outs, and Apify Actor policies. Do not scrape private accounts, bypass access controls, or collect sensitive personal data.
- Apify outage/timeout must degrade to "enrichment unavailable"; call audio and Gemini Live continue normally.

## Reliability and security requirements

- Separate process/worker or queue for Apify, Telegram, and CRM enrichment.
- Bounded queues and circuit breakers; no unbounded work during call spikes.
- Never hold a call/audio request open waiting for Apify.
- Per-tenant authorization on every customer/call/enrichment query.
- Encrypt secrets and sensitive records; rotate credentials; redact logs.
- Retention controls for transcripts and call metadata; consent/notice controls appropriate to deployment jurisdictions.
- Confidence + provenance on every extracted/enriched field; conflicting sources remain visible rather than silently overwriting.
- Metrics: call audio errors, Gemini session health, event delivery lag, queue depth, Apify latency/cost/failure, duplicate-match rate, Telegram delivery.

## Delivery plan and acceptance gates

1. **Repository audit + contract tests:** map existing call lifecycle hooks without changing audio classes; define server contracts and prove the bridge is unchanged.
2. **Independent backend foundation:** authentication, tenant isolation, migrations, CRM schema, health checks, audit trail, feature flags.
3. **Call event integration:** send only lifecycle/structured events from the app; verify no synchronous dependency on the audio path.
4. **Gemini/Vertex adapter:** server-side ADC, session lifecycle, structured extraction; no credentials in APK.
5. **Apify async adapter:** actor configuration, webhook verification, cache, quota/cost guard, failure isolation.
6. **Central CRM + matching:** customer/company profiles, E.164 normalization, dedupe and confidence/provenance.
7. **Post-call reports:** Telegram integration, redaction, retry, delivery audit.
8. **Verification:** unit/integration tests, audio regression checks on the target HONOR device, network-failure tests, load/queue tests, security review, build APK only after tests pass.

### Required acceptance criteria

- Existing SIP↔SIM call setup, two-way audio, mute/speaker routing, RTP behavior, and takeover controls remain unchanged in regression tests and device testing.
- Apify can be disabled or unavailable without changing call success, audio latency, or Gemini Live streaming.
- No secret is present in APK, Git history, or client logs.
- Duplicate calls/events/jobs do not create duplicate customers or enrichment charges.
- Cross-tenant customer access is denied and tested.
- Every enriched field has provenance and a timestamp.
- No outbound contact is initiated automatically.
- No production rollout occurs until the user separately approves it.

## Open implementation dependency

The current repository is the Android gateway only. Before production backend implementation, choose/provision the server runtime and central database, and provide server-side credentials through a secure secret manager. Do not commit credentials or place them in Android resources/build config.
