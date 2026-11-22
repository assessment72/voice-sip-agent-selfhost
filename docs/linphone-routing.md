# Linphone account as the SIP registration hop

## What the free Linphone service does

A free address such as `ofkkr8214t@sip.linphone.org` is a SIP account on Linphone's community service. The service supports registering a SIP client and making/receiving ordinary SIP calls between SIP addresses. Linphone's published FAQ says free `sip.linphone.org` accounts cannot be associated with a virtual phone number. The service is powered by Flexisip, but users of the public service do not administer its proxy routing or dialplan.

Therefore, the public service is not a user-configurable PSTN trunk or a wildcard DID gateway. Dialing:

```text
sip:sip737445440@sip.linphone.org
```

asks the public service to reach the SIP user `sip737445440`. It does not mean "forward this number to account ofkkr8214t". If that SIP user is not registered/routable, a 404 is expected. Registering Android as `ofkkr8214t` does not create aliases for arbitrary `sip<mobile-number>` users.

## Supported integration shape

The Android gateway can register its PJSUA2 account to the public Linphone service:

- registrar/proxy: `sip.linphone.org`
- SIP domain: `sip.linphone.org`
- account username: `ofkkr8214t`
- password: the account's SIP password (enter only in the app; never commit it)
- transport: UDP, TCP, or TLS as accepted by the account/service and the app build

A separate programmable SIP proxy/PBX must then originate an INVITE to the fixed registered AoR and carry the mobile destination in one of these fields:

1. `X-VSA-Dial: sip737445440` (preferred)
2. `To: <sip:sip737445440@...>`
3. `P-Called-Party-ID: <sip:737445440@...>`

The active Android runtime uses the built-in Kotlin SIP user agent (UDP transport) and its Yemeni target resolver reads these fields in that order, skips the fixed gateway AoR in To/Request-URI, validates the Yemeni mobile prefix, and normalizes the value to E.164 before creating the cellular leg. The optional PJSUA2 module is not currently wired into the app runtime/build, so selecting it is not a supported way to enable this route. The upstream proxy must preserve the selected destination field while routing the INVITE to the registered Android Contact. The proxy must also authenticate the originating caller and restrict who can reach the gateway.

Conceptual SIP exchange at the Android gateway:

```text
INVITE sip:ofkkr8214t@sip.linphone.org SIP/2.0
To: <sip:sip737445440@sip.linphone.org>
X-VSA-Dial: sip737445440
```

If the proxy routes to the registered Contact and preserves `X-VSA-Dial`, Android extracts `737445440` and proceeds through the existing SIM 2 call path. This example describes the INVITE as it must arrive at the gateway; it is not a dial string that the public Linphone service itself is documented to generate.

## What is and is not complete

- Implemented in the active app stack: target extraction from X-VSA-Dial, To, P-Called-Party-ID, and Request-URI; fixed AoR skipping; Yemeni number validation and E.164 normalization; JVM tests including the fixed Linphone AoR `ofkkr8214t`.
- Not provided by the public free Linphone account: a configurable wildcard route from arbitrary `sip<mobile-number>` users to `ofkkr8214t`, or a user-managed dialplan that inserts `X-VSA-Dial`.
- Still required: a programmable SIP proxy/PBX or a provider that explicitly supports dynamic destination routing and custom SIP headers. This can be hosted separately; it does not require a public IP on the Android phone if Android maintains an outbound registration.
- Still unverified: successful delivery through a real Linphone account, cellular call placement, and two-way call audio on the HONOR handset.

Do not treat a successful REGISTER as proof that dynamic routing or two-way media works.
