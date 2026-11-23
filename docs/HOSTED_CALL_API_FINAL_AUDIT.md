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
