# Voicx CRM customer follow-ups

## Implemented on this feature branch

- MongoDB-backed contacts, call interactions, and follow-ups.
- Unique normalized phone per tenant and tenant-scoped reads/writes.
- Contact search, never-contacted and older-than-N-days filters.
- Import preview with duplicate detection; imports are limited to 500 rows per request.
- Idempotent call interaction writes when an idempotency key is supplied.
- Private Telegram CRM commands, isolated from device-control authorization.
- Device-token-authenticated call-report endpoint for completed SIM/Gemini calls.
- Android reports completed Gemini and Telegram SIM calls only when the optional control bridge URL, device ID, and device token are configured.
- Legacy JSON-backed CRM endpoints remain available when MongoDB is not configured.

## MongoDB configuration

Set these variables on the control-bridge host only:

```dotenv
MONGODB_URI=
MONGODB_DATABASE=voice_sip_agent_control
VOICX_CRM_TENANT_ID=your-organization-id
VOICX_CRM_TELEGRAM_ADMIN_IDS=123456789
```

Never put MongoDB credentials or the bridge admin token in the Android APK or commit them to Git. The Android app uses only its per-device token for the call-report endpoint.

## Telegram commands

The CRM allowlist is separate from `TELEGRAM_ADMIN_IDS`. CRM-only users cannot start or stop device gateways.

- `/crm_help`
- `/crm_add name | phone | company | city`
- `/crm_search query`
- `/crm_never`
- `/crm_due`
- `/crm_log phone | outcome | summary | follow-up-ISO`

Use a private chat only. Phone numbers are normalized to E.164-like digits; local Yemen mobile numbers beginning with 7 are normalized with country code 967.

## Call reporting

The device calls:

`POST /api/v1/devices/{deviceId}/calls/report`

using its device bearer token. The bridge checks that token before accepting the report. MongoDB call interactions are deduplicated by `idempotencyKey`; reports are attached to an existing contact by normalized phone, or kept as an unlinked interaction until a contact is created. When a name is available, a new contact is created if one does not already exist.

The report stores the call outcome, end timestamp, duration, and a bounded text summary. It does not upload audio recordings or full conversation transcripts.

## Explicit limitations

- MongoDB tenant scope is currently selected by one server-side `VOICX_CRM_TENANT_ID` value per bridge deployment. This is not yet a multi-organization identity and authorization service.
- CRM Telegram commands cover contact add/search, never-contacted, due follow-ups, and manual interaction logging. Campaign drafts, AI-generated per-customer messages, WhatsApp evidence tracking, and Haraj publication workflow are not yet connected to these commands.
- The existing marketing, campaign, messaging, task, and publication routes remain in the legacy JSON store and have not yet been migrated to MongoDB.
- No outbound calls, SMS, WhatsApp messages, or Haraj publications are initiated by this work.
- A successful GitHub commit is not proof of a successful CI run or live MongoDB integration. Verify CI and test with a dedicated non-production MongoDB database before deployment.
