# Linphone iOS → SIP PBX → Android SIM gateway

## Important limitation

The stock Linphone iOS dial field accepts a SIP/TEL URI. Appending
`?X-VSA-Dial=...` is not a supported way to add a SIP message header and may be rejected
as an invalid SIP/TEL URI. Percent-encoding the query does not turn it into an INVITE header.

The public `sip.linphone.org` community registrar is not a programmable PBX for this
account: it does not give the account owner a dialplan that can rewrite INVITEs or insert
arbitrary SIP headers. A fixed Linphone AoR also does not automatically route arbitrary
mobile-number usernames to that AoR.

## Supported call path

Use a programmable SIP proxy/PBX under your control (Asterisk/PJSIP or another provider
that explicitly supports dialplan routing and custom SIP headers):

1. Register Linphone iOS to the PBX as a normal SIP extension.
2. Register the Android gateway to that same PBX using its fixed gateway account/AoR.
3. From Linphone iOS, dial the Yemeni national number as a SIP destination at the PBX,
   for example `sip:737445440@<pbx-domain>`, or use the PBX's configured speed-dial route.
4. The PBX validates the caller and the destination, then creates a new INVITE to the
   Android gateway's registered Contact.
5. On that outgoing INVITE, the PBX adds:
   
   ```sip
   X-VSA-Dial: sip737445440
   ```

6. The PBX must preserve that header while routing the INVITE to the Android registered
   Contact. Android validates the Yemeni mobile prefix, normalizes the number to
   `+967737445440`, and starts the configured SIM 2 call path.

The Android app already resolves the destination in this order:
`X-VSA-Dial`, `To`, `P-Called-Party-ID`, then Request-URI. A malformed selected
`X-VSA-Dial` value is rejected; it must not silently fall back to another field.

## INVITE expected at Android

The destination-bearing request must arrive at Android in this form (illustrative values):

```sip
INVITE sip:gateway@<pbx-domain> SIP/2.0
To: <sip:gateway@<pbx-domain>>
X-VSA-Dial: sip737445440
```

The Request-URI and To identify the fixed Android gateway account; the custom header carries
the mobile destination. Do not put the query string in the Linphone URI and do not dial a
nonexistent `sip737445440@sip.linphone.org` user.

## Security requirements

- Require SIP digest authentication for the Android gateway and authenticate/authorize the
  originating Linphone extension at the PBX.
- Allow only the trusted PBX to send calls to the gateway; keep the existing trusted-upstream
  and allowed-prefix protections enabled.
- Do not expose an unauthenticated GSM relay to the public Internet.
- Keep SIM selection explicit (SIM 2); do not silently fall back to another SIM.
- Do not log SIP passwords, Authorization headers, or full unmasked destination numbers.

## Verification

- Capture the INVITE at the Android gateway and verify that `X-VSA-Dial: sip737445440`
  is present exactly once.
- Verify that the gateway logs identify `X-VSA-Dial` as the target source and normalize to
  `+967737445440`.
- Verify malformed and unsupported numbers are rejected with SIP 484 and do not start a
  cellular call.
- Verify the real handset can place the SIM call and that audio is bridged in both directions.
  Passing unit tests alone does not prove provider routing, carrier call setup, or two-way audio.

## What this repository can and cannot do

The Android gateway already parses and validates the custom header; this change adds regression
coverage for header precedence and malformed-header rejection. The PBX that inserts the header
is a separate network component and must be configured separately. The stock Linphone iOS app
is not modified by this Android repository.
