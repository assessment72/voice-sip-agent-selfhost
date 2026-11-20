# Caller ID privacy and alternate identity

## Available outgoing modes

The app stores a default caller-ID mode and applies it to outgoing calls. The same mode can be overridden per call through the existing call-party metadata / SIP control header.

- `normal`: use the normal line/account identity.
- `private`, `unknown`, and `hidden`: request identity restriction. These are distinct UI labels, but the remote carrier or SIP provider decides whether the recipient sees "Private", "Unknown", or no caller ID.

## Cellular SIM behavior

For restricted modes, the cellular dialer receives the per-call CLIR invocation prefix `#31#`. The app does not change the subscription-wide CLIR setting. Android Telecom / the modem / the carrier may not expose a definitive CLIR acceptance result. A failed or unsupported privacy request must not cause the app to block the call; the call proceeds and logs that carrier confirmation is unavailable.

This app does not send raw AT commands to the phone's internal modem. Android does not provide a general public AT-command port for ordinary apps. Direct AT control requires a separately exposed modem interface (for example, a supported USB serial modem) and its own transport implementation.

## Alternate SIP caller ID

For calls registered to an external SIP PBX (device-in-itself/server mode off), an optional alternate caller ID can be configured in E.164 format, for example `+9677XXXXXXX`.

- Use only a number that the SIP provider has verified and authorized for this account.
- The value is validated as an E.164-like number before it is used; arbitrary SIP URIs and header text are rejected.
- In normal mode, the configured number is used as the SIP From user and asserted identity.
- In private/unknown/hidden modes, the alternate identity is not sent: the anonymous From and SIP Privacy header are used, and P-Asserted-Identity is omitted.
- The alternate SIP caller ID is not applied to cellular SIM calls or the built-in local registrar identity.
- A SIP provider may reject, rewrite, or ignore caller-ID values. Configuration is not proof that the provider will present the number.

## Important limitations

- CLIR hides the caller ID from the called party when supported; it does not hide the call from the carrier or its lawful records.
- "Private", "Unknown", and "Hidden" are presentation requests, not guaranteed distinct network outcomes.
- The app does not impersonate arbitrary third-party numbers or bypass carrier verification.
