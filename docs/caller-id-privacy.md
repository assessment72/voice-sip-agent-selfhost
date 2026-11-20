# Caller ID privacy

The gateway stores one default caller-ID mode for both outgoing SIP and SIM legs:

- `NORMAL`: preserve the configured/received caller identity (default).
- `PRIVATE`, `UNKNOWN`, `HIDDEN`: request network-side caller-ID restriction.

## SIP

For a restricted outgoing SIP leg, the built-in SIP stack sends an anonymous `From` URI and
`Privacy: id;user`, and omits `P-Asserted-Identity`. The SIP provider/PBX must honor RFC 3323
and RFC 3325 privacy processing. A display label is only a hint; the network or recipient client
may replace it. SIP authentication remains unchanged and is not the caller identity.

## SIM / GSM

For a restricted outgoing cellular leg, the gateway prefixes the dial string with `#31#`
(per-call CLIR invocation). `NORMAL` sends the number unchanged. The prefix is applied only to
gateway-originated calls; it does not alter the subscription-wide CLIR setting.

This is a request to the mobile network, not proof that the network accepted it. CLIR must be
provisioned by the operator and may be overridden by operator policy or the called party's
override category. The three privacy labels map to the same network-level CLIR request; GSM does
not provide separate controls for displaying the words “Private”, “Unknown”, or “No Caller ID”.

## Verification

Test each mode with a receiving handset on the actual SIM operator and, separately, the actual SIP
provider. Record the receiver's displayed identity and call completion. The app must report only
that restriction was requested unless the network provides an authoritative result.

## Standards

- [RFC 3323 — SIP Privacy Mechanism](https://www.rfc-editor.org/rfc/rfc3323)
- [RFC 3325 — Asserted Identity in Trusted SIP Networks](https://www.rfc-editor.org/rfc/rfc3325)
- [3GPP TS 27.007 — AT command set, +CLIR](https://www.etsi.org/deliver/etsi_ts/127000_127099/127007/13.04.00_60/ts_127007v130400p.pdf)
- [3GPP TS 22.081 — Line Identification supplementary services](https://www.etsi.org/deliver/etsi_ts/122000_122099/122081/15.00.00_60/ts_122081v150000p.pdf)
