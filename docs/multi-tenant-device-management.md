# Multi-tenant device and call management architecture

Status: implemented on `feature/hosted-call-api`; PR #10 remains Draft and unmerged. Tenant-facing runtime persistence is MongoDB when configured. Preview connectivity has been verified; live Atlas transaction CI and device/SIP validation remain outstanding.

## Product boundary

One hosted control plane serves multiple organizations. Each organization owns its members, branches, devices, credentials, configurations, events, commands, and call history. All organization-scoped reads and writes must derive the organization from the authenticated principal; never trust an `organization_id` supplied by an untrusted client as authorization.

The existing SIP account settings remain device-local and unchanged. Hosted API credentials are separate from SIP credentials.

## Logical architecture

- Admin web UI: Arabic starter dashboard at `/admin` for organization device presence, call history and safe allow-listed device commands. Organization authentication is entered for the active page session; the token is not persisted in localStorage.
- Control-plane API: authentication, organization membership, device enrollment, presence, configuration, commands, hosted calls, metrics and audit.
- Device agent: outbound HTTPS only; enrollment, heartbeat, long-poll command/job retrieval, state/event reporting.
- Persistence: MongoDB Atlas collections for durable identity, ownership, devices, commands, audit and call records; atomic document updates and transactions for multi-document operations. SQLite is local-development compatibility only.
- Realtime delivery: begin with heartbeat + long polling; add SSE/WebSocket for the admin UI only when required. Devices never expose an inbound public listener.

## Tenant and role model

Roles are organization-scoped:
- `owner`: full organization control, billing/credential lifecycle.
- `admin`: manage members, branches, devices, policies and calls.
- `operator`: view assigned devices and operate permitted call workflows.
- `viewer`: read-only dashboards, device status and reports.

Every tenant-owned row carries `organization_id` directly or is reachable through a parent row with enforced ownership. Cross-tenant access must return 404/403 without disclosing whether another tenant's resource exists.

## Device identity and lifecycle

1. An admin creates a short-lived, limited-use enrollment token for one organization.
2. The Android agent enrolls over HTTPS and receives an opaque device ID plus a unique device credential. The raw token is shown once and stored only in Android Keystore-backed storage where supported.
3. Server stores only a cryptographic hash of the device credential; credentials can be revoked/rotated independently.
4. Device sends heartbeat every 30 seconds and immediately after meaningful state changes.
5. Server stores latest status on `devices`; heartbeat history is sampled/retained under a bounded retention policy.
6. Revoked/disabled devices cannot claim jobs, update calls, send events, or receive commands.

Presence policy (initial defaults, configurable):
- online: last heartbeat <= 60 seconds;
- unstable: >60 and <=120 seconds;
- offline: >120 seconds.
Presence is derived from server receive time, not the device clock.

Heartbeat fields are allow-listed: app version, Android version, manufacturer/model, battery, network type, internet state, SIP registration state, SIM readiness, agent state, active-call boolean/reference. Do not collect contacts, message contents, call audio, or precise location as telemetry.

## Command protocol

Commands are allow-listed, organization-scoped, device-scoped, expiring, idempotent and auditable. Lifecycle:
`pending -> delivered -> acknowledged -> completed|failed`.
Expired commands become `expired`; cancellation is allowed only before acknowledgement. Initial commands: `refresh_status`, `sync_config`, `pause_calls`, `resume_calls`, and `restart_agent` only where Android permits it. No arbitrary shell, arbitrary URL, or unrestricted remote execution.

## Call ownership and safety

A call is owned by one organization and optionally assigned to one device. Device claims must be scoped to the same organization and use a lease. The same tenant context must guard create, read, cancel, metrics and state-transition endpoints. Idempotency uniqueness is tenant-scoped. Device state updates are accepted only from the device holding the active lease.

Emergency-number rejection, destination allow-lists, per-organization and per-device rate limits, no-overlap rules, bounded call duration, and audit events remain mandatory. Phone numbers, credentials, authorization headers and SIP secrets must not appear in logs, event details, metrics labels or admin list views.

## MongoDB persistence model

The active tenant backend uses MongoDB collections:
- `organizations`, `memberships`, and `api_credentials`;
- `branches`, `enrollment_tokens`, `devices`, and `device_credentials`;
- `heartbeats`, `device_events`, `device_configs`, and `device_commands`;
- `calls`, `rate_limit_windows`, and `admin_audit_events`.

Unique and query indexes enforce organization-scoped identity, idempotency, branch codes, device credentials, enrollment tokens, and one active call slot per organization. Enrollment, membership lifecycle, ownership transfer, device revocation, and command-plus-audit creation use MongoDB transactions. Device job claims and state transitions use atomic MongoDB operations.

The SQLite file `hosted-api/migrations/001_tenant_device_foundation.sql` remains for local-development compatibility and legacy test coverage. It is not initialized by Vercel. Vercel startup requires MongoDB and fails closed when the durable backend is not configured. No automatic SQLite data migration is performed; any existing persistent SQLite data requires a separate verified backup and an explicit, reviewed migration procedure before cutover.

## Admin web UI information architecture

1. Organization dashboard: online/unstable/offline counts, active calls, failures, recent events.
2. Devices: searchable/filterable table; status, branch, last seen, app version, SIP/SIM status.
3. Device detail: profile, live status, activity timeline, call history, config version, command history and credential revocation.
4. Calls: tenant-scoped live/history views, state timeline, reason codes and aggregate latency.
5. Organization: branches, members, roles, enrollment tokens, API credentials and policies.
6. Audit/reports: who changed what, device connectivity trends, call outcomes and latency percentiles.

## API surface to implement in stages

- `POST /v1/organizations` — bootstrap-only organization provisioning.
- `POST /v1/organizations/{id}/members` creates a member with a role-bound API credential shown once.
- `POST /v1/organizations/{id}/branches` and `GET /v1/admin/branches` manage organization-owned branches.
- `POST /v1/organizations/{id}/enrollment-tokens`.
- `POST /v1/device/enroll` — exchange a one-time enrollment token for a unique device credential.
- `POST /v1/device/heartbeat` and `POST /v1/device/events`.
- `GET /v1/admin/devices` and `GET /v1/admin/devices/{id}/activity` provide tenant-scoped device presence and event history.
- `POST /v1/admin/device-commands`, `GET /v1/device/commands/next`, and `POST /v1/device/commands/{id}/result` provide an allow-listed command lifecycle.
- `POST /v1/organizations/{id}/calls`, `GET /v1/device/call-jobs/next`, `POST /v1/device/tenant-calls/{id}/state`, and `GET /v1/admin/calls` provide tenant-scoped call creation, device claiming and monitoring.
- `GET /v1/device/commands/next?wait=...` and command acknowledgement/result endpoints.
- `GET /v1/admin/devices`, `GET /v1/admin/devices/{id}`, `GET /v1/admin/calls`.
- Tenant-aware hosted-call create/read/state/metrics endpoints.

All admin endpoints require organization membership and role checks. All device endpoints require the unique device credential and derive tenant/device identity from that credential.

## Delivery sequence

1. Architecture + additive schema foundation.
2. Organization bootstrap, membership and role enforcement.
3. Enrollment tokens and per-device credential lifecycle.
4. Heartbeat, presence, device profile and activity timeline.
5. Tenant-scoped hosted calls and per-device job leasing.
6. Commands and versioned configuration.
7. Admin web UI and realtime dashboard.
8. Tenant isolation, abuse/rate-limit, migration, retention and Android integration tests.

## Compatibility and release gates

- Legacy shared-token call/device routes remain only for local compatibility. They are disabled by default and explicitly blocked whenever MongoDB is active.
- Do not alter SIP credentials or existing Linphone registration settings.
- No public deployment, production database migration, paid resource provisioning, live call or release signing without separate approval.
- Required tests: cross-tenant denial, role matrix, revoked/expired tokens, enrollment replay, heartbeat presence boundaries, command expiry/idempotency, concurrent device claims, call assignment isolation, and legacy API regression.


## Implemented backend slice

The current branch now includes:
- bootstrap-only organization creation using `ORG_BOOTSTRAP_TOKEN`;
- one-time organization API credentials bound to a specific membership identity;
- organization-scoped member creation and role enforcement;
- first-class organization branches with branch-to-device assignment;
- limited-use enrollment tokens and one-time per-device credentials;
- heartbeat ingestion, server-time-based online/unstable/offline calculation, device list and activity timeline;
- role-bound member API credentials, device revocation and a browser-based Arabic admin dashboard served at `/admin`;
- device revocation and allow-listed command delivery/result lifecycle;
- organization-scoped call creation, device job claiming, call state updates and admin call history.

Required environment secret: `ORG_BOOTSTRAP_TOKEN` must be a long random secret configured out-of-band before organization bootstrap is enabled. Do not commit it. The bootstrap endpoint must remain disabled when the secret is absent.

## Remaining production blockers

- The admin UI is an initial operational dashboard, not yet a full organization-management console; branch management, member management screens, charts, filters and realtime push updates remain to be built.
- Android integration code now supports enrollment, per-device tokens, heartbeat, tenant job polling, call-state outbox and commands; physical HONOR MTN-NX1 / Android 16 verification remains outstanding.
- The legacy shared-token routes are disabled by default and cannot run against the MongoDB runtime.
- Add bounded heartbeat/event retention, tenant metrics, API credential revocation/rotation, organization suspension behavior, and audit coverage.
- Production MongoDB environment variables, backup/restore, monitoring/alerting, transaction integration test and cutover require separate approval. The current Preview uses only the isolated CI database.
