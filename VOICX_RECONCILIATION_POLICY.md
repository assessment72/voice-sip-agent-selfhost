# Voicx reconciliation policy — implementation note

## Scope
This change adds a pure Android policy gate for schedules already quarantined as `needs_reconciliation`. It does not claim that a call or SMS was delivered and does not connect to carrier or messaging-provider receipts.

## Allowed manager decisions
- `MARK_COMPLETED`: requires manager identity and a non-empty evidence/reference note.
- `MARK_FAILED`: requires manager identity and a non-empty evidence/reference note.
- `RETRY`: requires manager identity, evidence/reference note, and an explicit confirmation flag. The resulting state is `pending`; the normal scheduler may then pick it up.

The policy rejects decisions for any state other than `needs_reconciliation`. It returns an audit action name for the persistence layer to record with the actor, evidence, previous state, resulting state, and timestamp.

## Important integration boundary
This is a tested policy primitive, not yet a complete reconciliation workflow. The SQLite repository, Android review screen, Telegram command, and provider/SIP receipt lookup are not wired to it in this commit. Until that integration is complete, quarantined schedules must not be retried manually by editing database records. No automatic retry is enabled on phone reconnect.

## Validation
Four unit tests cover quarantine detection, evidence and actor requirements, explicit retry confirmation, and rejection of non-quarantined states. CI is required to establish test/build status.
