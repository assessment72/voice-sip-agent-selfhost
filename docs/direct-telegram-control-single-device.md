# Direct Telegram Control — Single Android Device

Status: Android implementation present; build and runtime verification pending.

## Product scope
This feature is for one device only: the user's HONOR MTN-NX1 running Voicx SIP. A single Telegram bot is the remote-control interface. No Termux process, self-managed VPS, or separate Node.js bridge is part of the intended design.

## Transport
- The Android app communicates with the Telegram Bot API directly over HTTPS.
- The app uses one long-polling worker (`getUpdates`). Do not run a second poller for the same bot token or configure a webhook simultaneously.
- Poll only while the existing GatewayService foreground service is active. It uses the app's ongoing notification; force-stop, OEM battery restrictions, or loss of connectivity can interrupt it.
- Outbound status and command-result messages use `sendMessage`.

## Credentials and authorization
- Configure the bot token and authorized numeric Telegram user ID in the app.
- Store the bot token encrypted using the existing Android Keystore-backed SettingsStore pattern. Never put a real token in source, BuildConfig, logs, crash reports, or repository files.
- Accept commands only from the configured Telegram user ID and private chat. Ignore groups and all other senders.
- Persist the Telegram update offset in app preferences and reset it when the bot token changes.
- Never log message bodies, bot tokens, or authorization headers.

## Command allowlist
- `/start` and `/help`: show supported commands.
- `/status`: report gateway/takeover state, SIP readiness, and active-call state.
- `/sip`: report SIP readiness and bounded diagnostic detail.
- `/calls`: report whether a call is active; do not expose phone numbers or call content.
- `/service_on`: enable gateway takeover through the existing app runtime.
- `/service_off`: disable takeover through the existing app runtime, preserving active-call cleanup behavior.
- `/simcall <number>`: start a cellular SIM call, then invite the registered SIP phone when the cellular destination starts ringing. Bridge audio after both legs answer. Emergency numbers and numbers outside configured allowed prefixes are rejected.
- `/simcall_cancel`: end both legs of the active Telegram-originated call.
- `/account`: show local Telegram SIM-call history, measured audio-path frames/peaks, and battery level/charging state at call end. Audio metrics do not guarantee that speech was audible.
- `/balance`: execute the configured carrier query `*144#` using Android's USSD API and send the carrier response to the authorized Telegram chat. This is USSD, not an SMS; it requires the user-granted `CALL_PHONE` permission and carrier/network support.
- `/simcall_delete <id>`: delete one local call-history record; it never hangs up a call.

`/simcall` places a real mobile call and may incur carrier charges. After each answered call, the app automatically requests `*144#` and sends the USSD response to Telegram. The bot also reports audio-path metrics and battery state at call end. USSD/session charges and response text are carrier-dependent, and the app does not intercept unsolicited carrier pop-ups. Delivery is not guaranteed while Android or the foreground service is stopped. No arbitrary shell execution, SMS sending, call recording, or remote access to contacts is permitted.

## Lifecycle and safety
- Integrate polling with the existing foreground GatewayService lifecycle; do not create a second independent always-on service.
- Use one coroutine/job guarded against duplicate starts, bounded network timeouts, a fixed retry delay for transient errors, and cancellation when the service stops or Telegram control is disabled.
- Configure by saving the bot token and authorized numeric user ID in the app; the existing GatewayService notification remains visible while the service runs.
- /service_on and /service_off acknowledge that the request was accepted; final state should be checked with /status.
- Network failures must not change SIP service state.
- Do not claim guaranteed delivery while Android has stopped the app.

## Acceptance tests
1. One app instance can call `getMe` and authenticate without printing the token.
2. Only the configured Telegram user/private chat can invoke commands.
3. Repeated service starts do not create multiple polling loops.
4. `/status`, `/sip`, and `/account` are read-only; `/balance` and post-call automation initiate a USSD session; `/simcall_delete` only removes a local record.
5. `/service_on` and `/service_off` use existing runtime APIs and report pending/failed states honestly.
6. Polling stops on service teardown and retries transient network errors with backoff.
7. No bot token or private chat content appears in logs or repository history.

## Current implementation status
Implemented on the dedicated feature branch:
- Android settings UI for the bot token and authorized numeric Telegram user ID.
- Bot token encrypted using the existing Android Keystore-backed SettingsStore.
- Direct getUpdates long polling and sendMessage calls from the Android app.
- One service-owned polling job, restricted to the configured sender ID in private chats.
- /start, /help, /status, /sip, /calls, /service_on, and /service_off.
- /simcall, /simcall_cancel, /account, and /simcall_delete with bounded local history and progress/result notifications.
- /balance via `*144#` USSD, automatically queried after answered calls; audio-path and battery snapshots included in final notifications and local history.
- No Termux, Node.js relay, VPS, or extra phone is required.

Still pending before release:
- Android build and CI verification, including testing the SIM/SIP bridge on the target handset and carrier.
- Runtime tests with a newly issued bot token and the user's own authorized ID.
- Improving retry/backoff behavior and surfacing Telegram connection health in the UI.
- Handling/reporting Telegram webhook conflicts and exposing connection health in the UI.

The implementation has not been built into an APK or tested against a live bot in this session. Do not treat it as release-ready yet.
