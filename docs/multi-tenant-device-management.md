# Multi-tenant device and call management architecture

Status: approved design; implementation is staged on `feature/hosted-call-api`.
No merge, deployment, production migration, or live call action is authorized.

## Product boundary

One hosted control plane serves multiple organizations. Each organization owns its members, branches, devices, credentials, configurations, events, commands, and call history. All organization-scoped reads and writes must derive the organization from the authenticated principal; never trust an `organization_id` supplied by an untrusted client as authorization.

The existing SIP account settings remain device-local and unchanged. Hosted API credentials are separate from SIP credentials.

## Logical architecture

- Admin web UI: organization switcher, dashboard, devices, calls, live activity, commands, users/roles, audit and reports.
- Control-plane API: authentication, organization membership, device enrollment, presence, configuration, commands, hosted calls, metrics and audit.
- Device agent: outbound HTTPS only; enrollment, heartbeat, long-poll command/job retrieval, state/event reporting.
- Persistence: relational database for durable identity, ownership and call records; queue/lease mechanism for commands and call jobs.
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

## Relational model

The additive SQLite foundation migration is in `hosted-api/migrations/001_tenant_device_foundation.sql`. It introduces:
- organizations and organization memberships;
- organization API credentials and limited-use device enrollment tokens;
- devices and unique per-device credentials;
- latest presence on devices plus heartbeat history;
- typed device events, versioned device configuration and expiring commands;
- call-to-tenant/device assignments without rewriting existing call rows.

The migration is additive and intentionally does not delete or rewrite existing calls. It must be run only after a verified backup in any persistent environment. Production should move to a managed relational database with migrations and backups before multi-tenant launch.

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

- Keep the current global device token only as a clearly marked development compatibility mode; disable it for multi-tenant production.
- Do not alter SIP credentials or existing Linphone registration settings.
- No public deployment, production database migration, paid resource provisioning, live call or release signing without separate approval.
- Required tests: cross-tenant denial, role matrix, revoked/expired tokens, enrollment replay, heartbeat presence boundaries, command expiry/idempotency, concurrent device claims, call assignment isolation, and legacy API regression.


## Implemented backend slice

The current branch now includes:
- bootstrap-only organization creation using `ORG_BOOTSTRAP_TOKEN`;
- one-time organization API credentials bound to a specific membership identity;
- organization-scoped member creation and role enforcement;
- limited-use enrollment tokens and one-time per-device credentials;
- heartbeat ingestion, server-time-based online/unstable/offline calculation, device list and activity timeline;
- device revocation and allow-listed command delivery/result lifecycle;
- organization-scoped call creation, device job claiming, call state updates and admin call history.

Required environment secret: `ORG_BOOTSTRAP_TOKEN` must be a long random secret configured out-of-band before organization bootstrap is enabled. Do not commit it. The bootstrap endpoint must remain disabled when the secret is absent.

## Remaining production blockers

- Admin web UI is not implemented yet.
- Android agent has not yet been updated to use enrollment, per-device tokens, heartbeat, tenant job polling or commands.
- Existing legacy global-token call routes remain for compatibility and must be disabled or isolated before a multi-tenant production release.
- Add bounded heartbeat/event retention, tenant metrics, API credential revocation/rotation, organization suspension behavior, and audit coverage.
- Production database deployment and migrations require backup/restore validation and a separate approval.
