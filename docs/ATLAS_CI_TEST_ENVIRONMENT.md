# Atlas CI test environment — 2026-11-23

Repository: `assessment72/voice-sip-agent-selfhost`
Branch: `feature/hosted-call-api`
Pull request: #10 (Draft; do not merge or deploy Production)

## Provisioned resources

- Atlas project: `Adasms` (`6b00d5d06faa02583006fa10`)
- Cluster: `AdasmsCluster` (Free tier, AWS Singapore)
- Isolated test database: `voice_sip_agent_ci`
- Persistent marker collection: `ci_metadata`
- Marker document: `_id=environment`, purpose `isolated-integration-tests`
- Dedicated least-privilege test user: `voice_sip_agent_ci_runner2`
- Granted role: `readWrite` on `voice_sip_agent_ci` only, scoped to `AdasmsCluster`
- Vercel project: `voice-sip-agent-hosted-api`
- Vercel variables added as Preview-only, restricted to branch `feature/hosted-call-api`:
  - `MONGODB_TEST_URI` (sensitive)
  - `MONGODB_TEST_DATABASE=voice_sip_agent_ci` (encrypted)
- No Production variables were changed.

## Atlas probe

Using the Atlas administrative connector, a temporary collection `__ci_permission_probe` was created in `voice_sip_agent_ci`, one document was inserted and counted, then that exact temporary collection was dropped. The persistent `ci_metadata` collection and its marker document were then created.

**Important validation boundary:** the probe above used the Atlas administrative connector. It did not authenticate as `voice_sip_agent_ci_runner2`, and therefore does not prove that the least-privilege application credential can connect. The URI remains stored as a sensitive Vercel Preview variable and was not revealed.

## CI integration status

The current GitHub Actions workflow is `.github/workflows/hosted-api-ci.yml`. It runs pytest and Docker smoke tests but does not yet consume `MONGODB_TEST_URI` / `MONGODB_TEST_DATABASE`.

The GitHub integration available in this execution does not expose a repository Actions Secrets write operation. The Vercel Preview variables cannot be read by GitHub Actions automatically. Do not copy the secret into source code, workflow YAML, logs, or a commit.

An opt-in live Atlas integration test is now present at `hosted-api/tests/test_mongodb_atlas_integration.py`. The Hosted API workflow invokes it only when both GitHub Actions secrets are available. Add repository Actions secrets named `MONGODB_TEST_URI` and `MONGODB_TEST_DATABASE` through GitHub's repository Settings > Secrets and variables > Actions. Until then the live integration test is skipped. Keep it limited to `voice_sip_agent_ci`; never point it at `voice_sip_agent`.

## Known cleanup item

An initial generated Atlas database user `voice_sip_agent_ci_runner` was also created during provisioning. Its generated password was not retained or stored. The Atlas tools available in this execution do not expose a delete-database-user operation, so this unused account could not be removed here. Both test users are restricted to `readWrite` on `voice_sip_agent_ci`; remove the unused `voice_sip_agent_ci_runner` from Atlas Database Access manually when convenient.

## Application migration status — updated

The branch now wires the tenant-facing FastAPI routes to MongoDB repositories when MongoDB is configured:

- Organization bootstrap and authentication; member creation, ownership transfer, revocation, and credential rotation.
- Enrollment-token creation and transactional device enrollment; device credential authentication and revocation.
- Device heartbeat history, event ingestion, device/activity listing, branch CRUD, device commands, and audit listing.
- Tenant call creation, idempotency, queue claim/lease, state transitions, call listing/detail, and tenant latency metrics.
- Shared distributed rate-limit windows.

MongoDB startup now creates the required indexes and does not initialize SQLite. Vercel startup fails closed unless MongoDB is configured. Legacy shared-token call/device routes are explicitly unavailable when MongoDB is active, preventing accidental fallback to ephemeral SQLite. Local development without MongoDB continues to use SQLite.

The isolated Preview credentials `MONGODB_TEST_URI` and `MONGODB_TEST_DATABASE` can serve as the Preview runtime backend only when `VERCEL_ENV` is not `production`. Production requires separate explicit `MONGODB_URI` and `MONGODB_DATABASE` variables. No Production variables were changed.

## Remaining validation

- GitHub Actions live Atlas integration remains skipped until repository Actions secrets are added. The Atlas administrative probe did not validate the application user's credentials.
- Transactional operations need a successful live Atlas test against the least-privilege test account and the selected Atlas topology.
- Backup/restore, monitoring/alerting, physical Android/SIM testing, SIP/RTP/audio verification, and signed Release APK validation remain outside the current CI evidence.
- PR #10 remains Draft and unmerged. No Production deployment, production database migration, SIP credential change, or paid call has been performed.

Do not merge or declare commercial readiness solely from mocked repository tests or CI.
