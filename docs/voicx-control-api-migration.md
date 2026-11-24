# Voicx SIP Control API — Migration Foundation

## Status

This document records the target architecture for the Voicx SIP Control migration.
It is a design baseline, not a claim that the API or multi-device runtime has already
been implemented. The existing SIP engine and current call behavior must remain intact
until replacement paths pass device-level tests.

## Android background persistence policy

- The Android foreground gateway host is intended to remain running regardless of the
  call-takeover switch. Disabling takeover disables SIP/call-bridge work, not the host
  service or its persistent status notification.
- The service returns `START_STICKY` and is started on boot and app replacement.
  A partial CPU wake lock is held while the foreground host is active.
- The app provides a shortcut to Android's battery-optimization settings. On HONOR,
  users should set the app to unrestricted / allow background activity and enable
  auto-launch where the firmware exposes that option.
- Android does not permit an app to guarantee survival after a user force-stop,
  Settings > Force stop, reboot-before-first-unlock restrictions, severe resource
  pressure, or OEM task-killer policies. Reboot recovery and system restarts are
  best-effort, not an absolute uptime guarantee.
- The persistent notification is intentional and must not be hidden or removed while
  the gateway host is expected to stay active.

## Target topology

- One always-on **local bridge** is the control-plane host. It is not a VPS and does
  not replace the SIP engine.
- Up to 20 Android installations connect outbound to the bridge using an authenticated
  persistent channel (WSS). Outbound connections avoid requiring inbound access to
  each handset and tolerate ordinary NAT more reliably.
- The bridge exposes a versioned HTTPS API to authorized clients.
- Telegram is an optional API client/notification adapter, not a required component.
- Remote API access is through a private VPN/overlay network or an equivalently
  authenticated private tunnel. Do not expose an unauthenticated API or SIP port
  directly to the public internet.
- Each Android installation has a stable device ID, display name, phone/SIP identity,
  and its own revocable credential. Secrets are stored using Android Keystore.

## API contract (proposed v1)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /api/v1/devices | List registered devices and last-seen status |
| GET | /api/v1/devices/{deviceId} | Device details, service state and battery |
| POST | /api/v1/devices/{deviceId}/commands | Submit an allow-listed device command |
| GET | /api/v1/commands/{commandId} | Read command state and result |
| GET | /api/v1/calls | Read call events/history |
| POST | /api/v1/devices/{deviceId}/calls | Request an authorized SIP call, only after outbound calling is implemented and tested |
| POST | /api/v1/calls/{callId}/hangup | Request hangup for a call controlled by this app |
| PUT | /api/v1/devices/{deviceId}/audio | Update supported per-device audio preferences |

Commands are asynchronous and return a command ID. Suggested states:
queued, delivered, running, succeeded, failed, expired.
Use an idempotency key for operations that must not be duplicated.

## Events

The device-to-bridge channel reports:
- device online/offline and last-seen timestamp;
- battery level and charging state when Android exposes them;
- app/service state;
- incoming/outgoing call state transitions supported by the SIP stack;
- command acknowledgements and sanitized failure details.

Do not report a call as completed merely because a command was accepted. Distinguish
command acceptance, SIP progress, connected state, and terminal call result.

## Security requirements

- HTTPS/WSS only; authenticate every client and device.
- Separate, revocable credentials per device and per API client.
- Role-based permissions for monitoring, service control, and call control.
- Explicit authorization before initiating a real call; record actor, target device,
  destination, timestamp, result, and idempotency key.
- Rate-limit authentication and call-control endpoints; audit security events.
- Never log SIP passwords, API tokens, SMS contents, or call audio.
- Keep the bridge bound to the private interface; use a private VPN/overlay for remote
  access. No public unauthenticated listener and no direct public SIP exposure.

## Compatibility and migration constraints

1. Preserve the existing SIP stack, SIP account settings, registrar behavior, and
   current incoming-call path while the control plane is introduced.
2. First implement device enrollment, heartbeat, battery/service status, command
   delivery, and audit logging. Do not couple these features to call routing.
3. Add call-control only after confirming the current SIP engine supports the exact
   requested outbound call path. The current README states that outgoing calls/SMS
   from the remote phone over the gateway SIM are not yet implemented; the API must
   not pretend otherwise.
4. Validate on the HONOR MTN-NX1 before enrolling additional devices, then load-test
   20 simulated/real device sessions.
5. Keep the old behavior available behind a feature flag until the new path passes
   acceptance tests. No real call is initiated during migration tests without explicit
   operator approval.

## Acceptance gates

- Device identity remains unique after app restart and bridge restart.
- Lost connectivity causes a stale/offline state, not a false online state.
- Commands are authenticated, scoped to one device, acknowledged, time-limited,
  idempotent where applicable, and auditable.
- Battery/service/call events are correctly attributed to the originating device.
- Remote access works through the private tunnel without exposing the bridge or SIP
  listener publicly.
- Existing SIP registration and incoming-call behavior remain unchanged.
- Outbound call capability is separately demonstrated on the target handset before
  its endpoint is marked supported.
