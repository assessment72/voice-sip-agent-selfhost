# Hosted Call API — implementation audit

Audit date: 2026-11-23  
Repository: `assessment72/voice-sip-agent-selfhost`  
Working branch: `feature/hosted-call-api`  
Pull request: [#10 — Hosted Call API and cellular-first SIP bridge](https://github.com/assessment72/voice-sip-agent-selfhost/pull/10)  
Audited commit: `224a81334b74df5ee5ca4492401efc4196bd6d07`

## Scope and safety boundary

This audit records repository and CI evidence only. It does not certify live carrier calls, SIP registration against the intended hosted service, or physical audio behavior. The PR remains Draft and unmerged. No production deployment, production credential change, paid hosting/SIP change, or real call was performed.

## Automated validation evidence

All three workflows completed successfully for the audited commit:

| Workflow | Run | Result |
|---|---:|---|
| Hosted Call API tests | [45091977088](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45091977088) | success |
| General CI | [45091977027](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45091977027) | success |
| Android build and tests | [45091977031](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45091977031) | success |

The Android workflow's only job, **Unit tests and debug APK**, completed successfully. Its steps included JVM/Android unit tests, debug APK assembly, APK upload, and test-report upload.

Artifacts from Android run 45091977031:

- Debug APK artifact: `voice-sip-agent-debug-b05a2c58a77bbcee457c5a0d4e6f1b785439d59a`, artifact ID `14780769928`, SHA-256 `0f316ddd88dda181b5ebe7133b62995f457550df814ca5959e031a08c489f6ca`, expires 2026-12-07.
- Test reports artifact: `android-test-reports-b05a2c58a77bbcee457c5a0d4e6f1b785439d59a`, artifact ID `14781786300`, expires 2026-11-30.

The prior API run on commit `f3a8dd0b091efd7806afb8f9d5ef5e62ecaa1059` passed, but its Android workflow was cancelled. It is superseded by the successful Android run on audited commit `224a813`.

## Implemented and covered by repository tests

- HTTPS-only hosted handset API client; redirects disabled.
- Separate caller and handset bearer credentials.
- Cellular-first sequencing: SIP leg is created only after the cellular leg is active.
- Call state reporting and server-side state/event persistence.
- Idempotency, destination validation/allow-list, rate limiting, ownership checks, and job claim lease recovery.
- Bounded long polling on a dedicated Android worker, separate ordered state-update worker, transient retry delays, and in-flight job guard.
- Aggregate average/p95 latency endpoint without call IDs or destination numbers.
- Concurrent job-claim and latency privacy tests.
- Android JVM/unit tests and debug APK build.

## Remaining release blockers

1. **Physical HONOR MTN-NX1 / Android 16 validation:** install and exercise the debug artifact on the target phone. CI does not prove Telecom, SIM routing, Shizuku permissions, or device-specific audio behavior.
2. **Live call matrix:** cellular answer and no-answer; SIM selection; SIP registration/dial failure; two-way audio; hangup from either leg; network loss/recovery; timeout cleanup; duplicate job delivery; process death/restart.
3. **Durable handset state-update queue:** current Android state notification queue is in-memory. Pending state updates can be lost if Android kills the process. Before relying on it operationally, persist pending updates (for example, Room/WorkManager) and test restart recovery, ordering, deduplication, and bounded retention.
4. **Release signing:** create a signed release APK only with an owner-controlled signing key and validate its signature. Do not place signing keys or passwords in the repository.
5. **Performance evidence:** collect real-device queue-to-ringing, queue-to-bridge, and cellular-answer-to-SIP timings; report sample count, average, and p95. API telemetry is implemented, but no real-call measurements are claimed.
6. **Deployment readiness:** review production hosting, TLS, backups, database durability, monitoring, token rotation, and firewall configuration separately. No deployment is authorized by this audit.

## Acceptance criteria

The feature is not release-ready until the physical-device matrix passes, the handset update queue survives process death, a signed release artifact is verified, and measured real-call latency and recovery behavior are documented.

## Explicitly not performed

- No merge of PR #10.
- No deployment to production or creation of a live hosted service.
- No purchase or paid hosting/SIP change.
- No SIP credential modification.
- No physical-device installation or real call.


## Follow-up implementation — durable Android state outbox (2026-11-23)

Commits after the initial audit:

- `56acba0b8180e0e82584b833654e1f9b1a760da9`: persist hosted call-state updates before network delivery.
- `2d8f14abffa13b720253d9e395758d07885a6885`: add `HostedStateUpdateQueue`, a SharedPreferences-backed ordered outbox.
- `fde3fccaa0a2db16be231469bc6482446c332936`: drain the outbox on its dedicated state worker, not the call-control executor.

Behavior:
- State transitions are synchronously committed to local storage before attempting HTTPS delivery.
- Pending transitions are retried in order with bounded backoff; after retry exhaustion the head item remains persisted and a later retry is scheduled.
- The queue is capped at 500 entries, deduplicates identical pending transitions, and does not silently overwrite a corrupt queue.
- No bearer token, destination phone number, or SIP credential is stored in the outbox.
- Delivery is **at-least-once**: a process death after server acceptance but before local acknowledgement may replay the same state. The server state endpoint must tolerate replay.

Validation status for these follow-up commits:
- GitHub Actions execution for commit `fde3fcc` has not yet been independently confirmed through the available workflow-run lookup (that lookup exposes pull-request-triggered runs only).
- The new code is not yet certified by a successful CI run on this commit.
- Process-death/restart behavior and queue ordering still require device/instrumented tests.


### Replay-safety correction for the durable outbox

Code review found that the API transition function previously returned HTTP 409 when a non-terminal state was replayed. That would prevent an at-least-once outbox from clearing an item if the server had accepted the first request but the response was lost.

Fixed on the working branch:
- `d945e273fa7f5cf718cf05353aa456fe5555ccba`: return the current call row when the requested state already equals the persisted state.
- `4b1e62f52d8c82b382080abdece4f19855d3105c`: add API regression coverage that replays each state in the lifecycle and expects HTTP 200.

This makes same-state replay safe for both legacy and organization-scoped device state routes because both use the shared transition function. It does not make arbitrary out-of-order transitions valid.

The latest commit's CI status remains unverified; the available workflow-run lookup returned no pull-request-triggered runs for this commit. Do not interpret that as a pass.


### Outbox overflow/rejection recovery correction

- `188b5378df6f8387e23f8bebc4be55e23f128417`: do not advance `hostedLastState` unless the state update has been accepted by the durable outbox. If enqueue is rejected (including a full or unreadable queue), schedule another attempt after 30 seconds. This prevents the in-memory duplicate-suppression guard from silently discarding a transition that was never persisted.
- The retry remains ordered behind older outbox entries; no queue contents are discarded to make room.

### CI verification for the latest correction

GitHub Actions runs were created for commit `188b5378df6f8387e23f8bebc4be55e23f128417`:

| Workflow | Run | Observed status at audit update |
|---|---:|---|
| Android build and tests | [45099943469](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45099943469) | in progress |
| General CI | [45099943506](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45099943506) | in progress |
| Hosted Call API tests | [45099943473](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45099943473) | in progress |

These runs are not yet passes. The Android unit-test step, general JVM test step, and hosted API pytest step were running when checked. Final conclusions must be recorded only after GitHub reports completed results.


### Android outbox unit tests added

Commit `730fb79167ebe5576d95e64cb97a17df46a32805` adds `HostedStateUpdateQueueTest` covering:
- FIFO ordering and acknowledgement/removal behavior.
- Deduplication of identical pending transitions while preserving distinct failure reasons.
- Recovery of persisted queue entries through a newly constructed queue instance sharing the same preferences (process-recreation model).
- Rejection of blank call/state values and safe handling of an already-removed/unknown acknowledgement.

These are JVM unit tests using mocked Android preferences. They validate serialization/persistence behavior at the queue abstraction level, not an actual Android OS process kill or device storage failure. The new tests still require a completed Android CI run on this commit before being called passed.


Additional outbox boundary tests on commit `54a9ab8645b8c7887896598257b8a5ecfaf280ac`:
- Verify the 500-entry capacity limit rejects overflow without deleting or reordering earlier transitions.
- Verify malformed persisted JSON is retained unchanged and causes new enqueue attempts to fail closed rather than silently replacing potentially important updates.

The test suite remains pending CI validation on the latest branch head.


### Enrollment identity isolation guard

Commits `24fa5124d1c6dcf366ae6a361027810410670ac4` and `d7822a99f65a8095bd9c05b6c103a70d3ec2fcea` add a fail-closed outbox emptiness check and prevent re-enrolling/replacing the hosted device credential while any pending or unreadable outbox data exists. This avoids sending an older organization's queued call-state updates under a newly enrolled device token. The guard clears only after the pending outbox is drained; corrupt storage is deliberately treated as non-empty.

Commit `d5bd1a642058f90f2b10b7df46e3de592b9948ae` extends the queue unit test to verify the empty-vs-corrupt distinction. Latest CI for these newest commits is still pending confirmation.


### Latest automated validation snapshot (2026-11-23)

For latest code/test commit `d5bd1a642058f90f2b10b7df46e3de592b9948ae`:
- Hosted Call API tests [run 45100662245](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45100662245): **success**.
- General CI [run 45100662265](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45100662265): **success**.
- Android build and tests [run 45100662254](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45100662254): **still in progress** at the latest check, specifically `testDebugUnitTest`. An earlier attempt for the same workflow was cancelled and a job-only rerun was started. No Android APK artifact is claimed until assembly and upload steps complete.


## MongoDB Atlas / Vercel Preview integration review (2026-11-23)

### Infrastructure setup evidence

- Created a dedicated Atlas database user `voice_sip_agent_api`, scoped to cluster `AdasmsCluster` and `readWrite` on database `voice_sip_agent` only.
- Created database `voice_sip_agent` and initial collection `call_jobs`.
- Added Vercel Preview-only variables to project `voice-sip-agent-hosted-api`, restricted to Git branch `feature/hosted-call-api`:
  - `MONGODB_URI` (sensitive)
  - `MONGODB_DATABASE=voice_sip_agent`
- Confirmed both variables are present in the branch-scoped Preview environment. No Production variables were added.
- Existing Atlas network access list contains `0.0.0.0/0`. This entry was pre-existing and was not modified. It is broad and must be narrowed to the actual application egress ranges or a supported private connectivity design before production use.

### Application compatibility finding — BLOCKED

The application has **not** been migrated to MongoDB. The current `hosted-api/main.py` (994 lines at review) imports `sqlite3`, opens `DATABASE_PATH`, runs SQLite-specific startup DDL and PRAGMA statements, reads a SQLite migration containing 13 relational tables, and relies throughout on SQL transactions and SQLite row semantics. The review identified approximately 169 SQLite/SQL-dependent code locations in this module.

The current `hosted-api/requirements.txt` contains FastAPI, Uvicorn, pytest, and httpx only; it does not include a MongoDB driver. The new Vercel variables are therefore configuration only and are not read by the current application. The existing Vercel Preview deployment is **not** evidence of MongoDB persistence.

A safe migration must preserve, at minimum:
- atomic idempotent call creation and per-principal rate limits;
- single-device job claiming and lease recovery under concurrent workers;
- call-state transition validation and at-least-once replay semantics;
- organization/member/branch/device credential isolation;
- enrollment-token use limits, device command delivery/acknowledgement, heartbeat/event history;
- uniqueness constraints, expiry cleanup, and audit/event ordering.

A direct SQL-to-Mongo rewrite or adding a driver without a complete repository abstraction and concurrency tests would risk duplicate calls, double claims, cross-tenant access, or lost device updates. No such partial rewrite was committed.

### Required implementation and release gates

1. Design a document schema and indexes for calls, rate-limit windows, organizations, memberships, branches, devices, credentials, enrollment tokens, commands, heartbeats, events, and tenant assignments.
2. Implement one cached PyMongo client per warm Vercel process and explicit transaction handling compatible with the selected Atlas topology. Verify transaction support before depending on multi-document transactions.
3. Replace SQLite-specific startup migrations with idempotent MongoDB index/schema initialization; never execute SQLite migrations against MongoDB.
4. Add repository-level tests for concurrent idempotency, competing device claims, transaction rollback, replay-safe transitions, tenant isolation, expiry/lease recovery, and restart behavior.
5. Run the full Hosted API CI suite and a Preview deployment using the MongoDB-backed code, then verify persistence across separate function invocations.
6. Restrict the existing broad Atlas network access rule before production; validate Vercel serverless egress/network reachability.
7. Keep PR #10 Draft and unmerged; do not deploy Production, send real calls, or alter SIP credentials as part of this work.

**Status:** Atlas database/user and Preview environment variables are provisioned. Application-level MongoDB migration, CI validation on migrated code, and runtime persistence verification remain incomplete. This service is not production-ready.


## MongoDB persistence foundation increment (2026-11-23)

Commits on `feature/hosted-call-api`:
- `898f996c32c242eeaa949521f71f35eaa9c60be6` — added `hosted-api/mongodb_store.py` with cached PyMongo client, database selection, health ping, transaction wrapper, and idempotent indexes for the API's principal data domains.
- `3f1d79787ca78c88bd2d3175e43784420512e930` — added `pymongo[srv]>=4.9,<5` dependency.
- `9164bfbe47171d9cde4f045c490228b793a53808` — corrected the PyMongo `IndexModel` import.
- `78ac230b22ca379fb789027db4b559252f33b274` — documented the migration boundary in the API README.

The client pool is intentionally conservative for Vercel serverless instances (maxPoolSize 5, minPoolSize 0, 20-second idle timeout); this is a starting assumption, not a measured capacity claim.

**Still not cut over:** `main.py` request handlers and startup continue to use SQLite. The new MongoDB module is not wired into routes; no MongoDB-backed API behavior or transaction semantics have been claimed or verified. CI and deployment checks must be run on this increment before considering it validated. The next implementation slice is the call lifecycle/idempotency/queue claim path, including competing-worker tests, followed by tenant/device endpoints.
