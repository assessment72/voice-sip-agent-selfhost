# Voicx CI finding — 2026-11-27

## Failure identified
Workflow run `45919908662` failed in `control-bridge-smoke` before executing assertions. Node reported a duplicate declaration of `concurrentContacts` in `control-bridge/smoke-test.mjs`; the concurrent CRM-write smoke block had been duplicated.

## Correction
Commit `3341147006057035c6ceaf64d8a9724a651457e8` removes the duplicate block while preserving the original concurrent-write assertion.

## Re-validation
Workflow run `45919961001` on commit `3341147006057035c6ceaf64d8a9724a651457e8`:
- `control-bridge-smoke`: completed successfully.
- JVM tests: running at the time of this note.
- Android unit tests: running / setup in progress at the time of this note.
- Upgrade-compatible APK build: running / setup in progress at the time of this note.

Do not treat the full workflow as passed until all jobs complete successfully. Vercel status is not a substitute for Android CI.

## Reconciliation implementation boundary
The policy primitive and unit tests are added in:
- `app/src/main/kotlin/io/github/sparkcyf/voicesipagent/app/ScheduleReconciliationPolicy.kt`
- `app/src/test/kotlin/io/github/sparkcyf/voicesipagent/app/ScheduleReconciliationPolicyTest.kt`

This is not yet wired to SQLite, the Android review UI, Telegram, or provider/SIP receipt lookup. No calls or SMS were sent. No merge or production deployment was performed.
