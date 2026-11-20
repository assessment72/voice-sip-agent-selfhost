# Dynamic SIP target → Yemeni SIM dialing

This feature lets one fixed SIP account receive calls for different Yemeni mobile numbers. The
gateway does **not** create a SIP account for each destination. The authenticated inbound INVITE
carries the destination in its headers, and the Android gateway normalizes it to E.164 before
placing the cellular leg.

## Supported target contract

The gateway reads the first non-empty field in this order:

1. `X-VSA-Dial` (custom VSA header)
2. `To`
3. `P-Called-Party-ID`
4. Request-URI

A fixed gateway AoR user (for example `gateway`, or the configured SIP account username) is
treated as routing metadata and skipped in `To` / Request-URI so the next destination-bearing field
can be considered. Otherwise, the first present field is authoritative: if it is malformed, the
INVITE is rejected and the gateway does not fall through to a lower-priority field. This prevents
a malformed high-priority target from being silently replaced by a different destination.

Accepted user value:

```text
sip777405578
```

The value must contain the literal `sip` prefix followed by exactly nine ASCII digits, and the
national number must be a supported Yemeni mobile prefix (70, 71, 73, 77, or 78). It becomes:

```text
sip777405578
    → 777405578
    → +967777405578
```

Bare national numbers, short codes, emergency numbers, non-numeric values, and other country
formats are rejected with `484 Address Incomplete`. The device's Android emergency-number check,
the gateway `allowedPrefixes` policy, SIP digest authentication, and SIM 2 selection remain
additional safeguards. Prefix allow-list entries may be written as a national prefix (for example
`77`) or an international prefix (for example `+96777`).

## Proxy/PBX configuration

Keep one stable account/AoR, for example:

```text
gateway@sip2sip.info
```

Your SIP proxy or PBX must route each inbound call to the gateway's registered contact while
preserving the called destination in one of the supported fields. The preferred wire format is:

```text
INVITE sip:gateway@<gateway-registered-domain> SIP/2.0
To: <sip:sip777405578@sip2sip.info>
X-VSA-Dial: sip777405578
```

If the proxy rewrites the Request-URI to the fixed gateway AoR, it must preserve the original
dynamic `To` header. Alternatively, it may set `X-VSA-Dial` to the encoded user value. The app
also accepts `P-Called-Party-ID` as a fallback when `To` does not carry the dynamic user.

For a PBX that creates the outbound leg, the required behavior is conceptually:

- receive a call for a Yemeni mobile number;
- construct `sip<9-digit-national-number>` (for example `sip777405578`);
- add `X-VSA-Dial: sip777405578` to the INVITE sent to the fixed gateway account;
- keep the gateway account/AoR fixed for every call.

The exact dialplan depends on the PBX and trunk. A normal SIP registrar alone does not necessarily
provide inbound DID routing, dialplan execution, custom-header insertion, or To-header preservation.
Confirm those capabilities with the provider/PBX administrator. In particular, do not assume that
the public `sip2sip.info` service supports custom header rewriting or arbitrary inbound routing;
the gateway implementation does not control that provider-side behavior.

## Authentication and relay protection

- Set a strong registrar/SIP password in the gateway and configure the matching digest credentials
  on the PBX/softphone.
- In built-in server mode, INVITEs without valid digest authentication are challenged/rejected
  before target parsing or call creation.
- Keep the existing source-IP brute-force throttling and `allowedPrefixes` policy enabled.
- Do not expose the SIP/RTP ports to the public internet without a firewall/VPN and strong
  authentication.
- Do not use this gateway to dial emergency services. Emergency destinations are refused.

## Diagnostics

The gateway logs the target source and masked forms of the encoded and normalized numbers, for
example:

```text
incoming INVITE received; target source=To; encoded SIP target=***5578;
normalized cellular target=***5578
placing cellular call via SIM 2 ***5578
media bridged
```

Credentials and Authorization headers are not logged. Number masking is intentional.

## Verification checklist

1. Send an authenticated INVITE with `X-VSA-Dial: sip777405578`; confirm the SIM call is placed
   to `+967777405578`.
2. Repeat with a dynamic `To` while the Request-URI remains the fixed gateway AoR.
3. Repeat using `P-Called-Party-ID` and then Request-URI as fallbacks.
4. Confirm unsupported and malformed values receive `484 Address Incomplete` and do not start
   a cellular call.
5. Confirm SIM 2 is selected explicitly; no default-SIM fallback is allowed.
6. Confirm the existing bridge connects media in both directions and a BYE, CANCEL, cellular
   disconnect, or remote hangup tears down the paired leg.
7. Run JVM tests, Android unit tests, and a live call on the target handset. A passing unit test
   does not prove carrier routing, SIM audio capture, or real-world two-way media.
