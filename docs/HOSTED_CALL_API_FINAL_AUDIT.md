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


## MongoDB foundation unit tests (2026-11-23)

- Added `hosted-api/tests/test_mongodb_store.py` covering repeatable index initialization, unique call idempotency index, partial unique branch-code index, required connection configuration, and fail-closed health status.
- Tests use fake database/collection objects; they do **not** connect to Atlas and do not validate live transactions, credentials, network access, or persistence.
- Reused the repository's existing `.github/workflows/hosted-api-ci.yml` test workflow. A temporary duplicate workflow file was removed.
- The Hosted API CI workflow was observed running for the branch after the test addition; its final result must be checked before treating the tests as passing.

**Migration status remains blocked:** all request handlers and startup in `hosted-api/main.py` still use SQLite. No route has been switched to MongoDB. Do not use the Preview deployment for device enrollment or live call control until the full migration and security gates are complete.


## MongoDB foundation verification update — 2026-11-23

- Added focused unit coverage for MongoDB index definitions, uniqueness constraints, configuration validation, and fail-closed health reporting.
- Added read-only `GET /health/database` probe. It performs a MongoDB ping when configured and returns only backend/status; it does not expose credentials or switch the active data backend.
- Added tests for healthy and unavailable probe responses.
- Declared `dnspython` explicitly for MongoDB SRV URI resolution; removed the obsolete `pymongo[srv]` extra.
- First CI execution exposed an incorrect test expectation in the repeatable-index test (the fake recorded one batch per invocation). The assertion was corrected; the rerun is pending.
- **Important:** this remains a connectivity/collection-index foundation. Main API persistence is still SQLite. The health probe passing would prove connectivity only, not MongoDB CRUD, transaction semantics, tenant isolation, or production readiness.


## MongoDB call lifecycle repository — staged implementation

- Added `hosted-api/mongodb_calls.py` with idempotent create, unique active-slot enforcement, atomic job claim/lease reclaim, timeout terminalization, owner-checked state transitions, and active-slot release.
- Added isolated MongoDB behavior tests in `hosted-api/tests/test_mongodb_calls.py`, using `mongomock` from `requirements-dev.txt`.
- Dedicated Hosted API CI now installs development test dependencies.
- This is a repository-level increment only. FastAPI routes, metrics, tenant ownership, device enrollment, and admin/control-plane paths remain SQLite-backed. No backend cutover has been enabled.
- Required before integration: run CI cleanly, verify claim/transition races against a real replica-set Atlas test database, add organization/device scoping to the call document and repository signatures, then migrate all dependent read/write paths together.


## Organization/device isolation increment — 2026-11-23

- Updated `hosted-api/mongodb_calls.py` so `organization_id` is mandatory for create, claim, and transition operations.
- Idempotency uniqueness is now scoped by `(organization_id, principal, idempotency_key)`; the active-call uniqueness slot is scoped per organization.
- Queue expiry, job claim, ownership lookup, and conditional state update all include the authenticated organization scope. Claim records the exact `device_id`; transitions require both organization and claiming device.
- Added mongomock tests for cross-organization idempotency/active-slot isolation, claim isolation, and transition isolation.
- Added optional real-MongoDB concurrency test `hosted-api/tests/test_mongodb_calls_atlas.py`. It is skipped unless both `MONGODB_TEST_URI` and `MONGODB_TEST_DATABASE` are explicitly supplied. It creates a uniquely suffixed database and drops only that database; it checks concurrent idempotent creation and competing claims. No Atlas URI was supplied to CI and no live Atlas integration result is claimed.
- These repository methods still rely on the caller to derive `organization_id` from a verified device credential/membership, not from an untrusted request body. Route/auth integration remains intentionally deferred.

### CI snapshot after the isolation increment

- Previous commit `5bbbaa02681fe9e71377337eeb53dbb1cc52c669`: Hosted Call API tests run [45103629058](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45103629058) and general CI run [45103629064](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45103629064) completed successfully.
- Android run [45103628992](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45103628992) failed in the combined JVM/Android unit-test step; APK assembly was skipped. The available job summary does not expose the failing test/log detail, so the root cause is not yet established.
- After organization scoping and the optional Atlas test were committed, workflows were observed for head `b20ff1b8f478e426edcfa7580159a13eeb38f889`:
  - Hosted Call API tests [45104528368](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45104528368): in progress at audit time.
  - General CI [45104528416](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45104528416): in progress at audit time.
  - Android build/tests [45104528390](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45104528390): pending at audit time.
- No FastAPI route was switched to MongoDB. No Preview or Production deployment, merge, real call, SIP credential change, or production database mutation was performed.


### Android JVM test failure diagnosis and correction

- Downloaded and inspected the test-report artifact for Android run [45103628992](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45103628992).
- The failing cases were in `HostedStateUpdateQueueTest`: Android's mock `org.json.JSONObject.put()` throws `RuntimeException: Method put ... not mocked` when invoked by local JVM tests. This was a test-runtime dependency issue, not evidence of a queue ordering or persistence assertion failure.
- Corrected `app/build.gradle.kts` to add `testImplementation("org.json:json:20240303")`, providing a functional JSON implementation to JVM tests without changing Android runtime behavior.
- Fix commit: `542847430730b3857e231da73883aed9c3062ee5`.
- A new CI result for the fix commit has not yet been observed. Keep Android validation pending until the workflow completes successfully and the APK assembly step runs.


### Shared index alignment correction

- Reviewed `mongodb_store.ensure_indexes()` after the call repository gained organization-scoped unique indexes.
- Replaced the staged global call idempotency/queue/claim index definitions with the same organization-scoped key patterns and names used by `MongoCallRepository.ensure_indexes()`; added the partial unique active-call slot index.
- Updated repository index declarations and unit assertions so both initialization paths agree.
- This is a staged schema definition only. No Atlas index was created, dropped, or modified by this change. If a prior environment already created the legacy global `uq_call_principal_idempotency` index, a deliberate, separately reviewed index migration must drop/replace it before cross-organization duplicate idempotency keys are supported. Do not silently drop indexes during cold start.


- Atlas integration-test safety refinement: the test now uses the explicitly configured `MONGODB_TEST_DATABASE` and a random per-run collection name, then drops only that collection. This is compatible with the existing database-scoped test user and does not require broader database privileges or database-wide deletion. Commit: `4cf8ddeb82c585a71e203c4ef1d2d3fae5204529`.


---

## Security and lifecycle remediation pass (2026-11-23)

Working branch head after this pass: `7348947dba9ed5f143be9bceca7a9a619c363d0b`.

### Changes committed to `feature/hosted-call-api`

- Organization credentials now require an active organization as well as an active membership and unrevoked/unexpired token.
- Device credentials are rejected when their organization is suspended or closed.
- Only `owner`, `admin`, and `operator` can create organization calls; `viewer` remains read-only.
- Added organization-member revocation. Revocation suspends membership and revokes all active API credentials for that member. Owner membership cannot be revoked through this endpoint; ownership transfer is required.
- Added member credential rotation. Prior active credentials for that member are revoked before the new one-time token is returned. Non-owner administrators cannot rotate owner/admin credentials.
- Legacy shared-token call/device routes are now disabled by default through `ENABLE_LEGACY_CALL_API=false` and `ENABLE_LEGACY_DEVICE_API=false`. The compatibility queue also excludes tenant-assigned calls, and legacy call lookup/state-update cannot access tenant calls.
- Emergency short codes are rejected before E.164 validation, closing the previous unreachable deny-list check.
- SIP server-mode REGISTER now fails closed with 403 when no registrar password is configured. This prevents an unauthenticated remote Contact from taking over the hosted peer destination.
- Docker image now includes the SQL migration, admin dashboard, and MongoDB modules; its default SQLite path is `/data/calls.sqlite3`, suitable for the named Compose volume.
- `/health/database` now probes the active SQLite database. The optional Atlas connectivity probe moved to `/health/mongodb` and is explicitly not presented as the active persistence backend.
- Hosted bridge now reserves its executor slot before queuing the cellular originate task. A queued inbound call cannot steal the bridge between the busy check and the hosted task.
- The hosted-call duration guard now targets the exact bridge session ID, so a stale 30-minute timer cannot terminate a later unrelated call.
- Added API and gateway regression tests for emergency numbers, viewer role denial, suspended organization/device rejection, member credential rotation/revocation, disabled legacy routes, hosted originate race, and stale session timeout.

### CI evidence and limits

The following runs completed successfully on checkpoint commit `2a7f7c6ec1bf8e7c90a86994a884a04ae76acba0`:
- Hosted API pytest: [45173438012](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45173438012) — success.
- General JVM tests: [45173438214](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45173438214) — success.
- Android build: [45173438124](https://github.com/assessment72/voice-sip-agent-selfhost/actions/runs/45173438124) — cancelled before Android SDK setup/tests/APK assembly.

These runs predate the later member-management and Kotlin bridge-race changes. The available workflow lookup returned no PR-triggered workflow runs for the latest branch head `7348947`; therefore **the latest changes are not CI-verified yet**. Do not infer test success from the earlier checkpoint.

The latest commit's combined GitHub status reported Vercel Preview success. This was an automatic repository-integration status; no Production deployment was requested or initiated by this work. No Production environment variables or credentials were changed.

### Remaining blockers — not fixed or not verifiable in this pass

1. **MongoDB is still not the active API datastore.** FastAPI route handlers still use SQLite. `mongodb_store.py` and `mongodb_calls.py` remain staged modules. The health endpoints now report this accurately. Vercel/serverless SQLite is not a durable multi-instance production database.
2. **Rate limiting is still incomplete.** Existing per-principal call limits do not provide a distributed IP + user + device limiter for enrollment, heartbeat, event, and command endpoints. An in-process limiter would not be sufficient for multiple serverless instances.
3. **Membership/credential management still needs a full lifecycle audit.** Rotation/revocation endpoints were added, but owner transfer, invitations/acceptance, audit records for administrative actions, and scope-based authorization are not complete.
4. **SIP brute-force state remains process-local.** It is not shared across multiple SIP instances and has not been tested under distributed load.
5. **RTP/SRTP security and media compatibility are not certified.** No real PBX, codec negotiation, packet capture, G.711 interoperability, NAT traversal, or encrypted-media test was performed.
6. **Android field validation remains outstanding.** No installation on the physical HONOR MTN-NX1, SIM 1/SIM 2 matrix, Shizuku/Doze/reboot test, process-kill recovery, or real carrier call was performed.
7. **Backup/restore, disaster recovery, external monitoring, reverse-proxy body limits/rate limits, and firewall hardening are not demonstrated.**
8. **Secret-history scanning was not completed.** No live secret values were added by this remediation, but that is not proof that Git history contains no secrets.

### Release decision

**NO-GO for commercial production and customer pilot.** The remediation closes several concrete authorization, legacy isolation, SIP registration, Docker packaging, health-reporting, and bridge race defects. It does not complete the MongoDB cutover or the field/security acceptance matrix. Keep PR #10 open and Draft. Do not merge or deploy Production until the remaining blockers are addressed and latest-head CI plus real-device validation pass.

No Production deployment, merge, production secret change, SIP credential change, or paid call was performed.
