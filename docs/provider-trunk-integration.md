# Single-account SIP trunk integration

Status: development-branch implementation, not yet live-provider verified.

## Selected operating mode

- Android remains the local SIP registrar and the authority for the one local softphone account.
- The local registrar accepts one registered softphone contact.
- The optional provider fields already present in Settings configure one upstream SIP trunk:
  - PBX host: provider SIP server IP or hostname
  - PBX port: provider SIP port
  - Extension / user: provider account username
  - Password: provider account password (stored sealed by Android Keystore)
  - SIP domain: provider domain; defaults to PBX host
- An INVITE from the registered local softphone is routed to the provider using the Request-URI user as the dynamic destination number.
- An inbound INVITE from the configured provider is routed to the currently registered local softphone.
- One active bridged call is allowed. The SIP user agents also enforce their configured dialog capacity.
- The local registrar password remains separate from the provider trunk password. Set a non-empty registrar password before allowing outbound calls from a softphone.

## Current implementation boundaries

- The built-in SIP transport is UDP only.
- The trunk user agent registers to the provider using the configured SIP account.
- The trunk bridge uses the existing call bridge and G.711 media path.
- Dynamic destinations are validated as SIP user parts before a trunk INVITE is created; URI parameters, alternate hosts, header injection and whitespace are rejected.
- Provider inbound calls are accepted only from the configured provider signalling IP by the current client-mode trust check.
- This does not yet solve remote softphone registration when the Android gateway is behind NAT/CGNAT. Direct local registrar access still requires a reachable address/port mapping.
- SIP-over-WSS external signalling rendezvous, SIP Outbound/Path flow handling, ICE, STUN and TURN media traversal remain separate implementation work. Do not describe calls over arbitrary NAT as working until those pieces pass live tests.

## Provider information required before live testing

Obtain from the provider:

1. Confirmation that the account supports SIP REGISTER on the supplied IP and port.
2. Registrar address, outbound proxy address (if different), transport and authentication username.
3. Whether inbound calls are delivered to the registered Contact and whether the provider rewrites private Contact/Via addresses for NAT.
4. The exact Request-URI number format for Yemen destinations (for example +967..., 967..., or another format).
5. Whether inbound signalling may originate from additional provider IPs.
6. Supported codecs (PCMU/PCMA), DTMF method, caller-ID/PAI policy, and RTP port/media requirements.
7. Test account credentials and a permitted test destination.

Never commit provider passwords, registrar passwords, TURN credentials or private keys to the repository. Enter account passwords through the app settings so they are sealed by Android Keystore.

## Acceptance tests

- Provider REGISTER succeeds and refreshes after network changes.
- Local softphone REGISTER succeeds with digest authentication.
- Local softphone outbound INVITE routes the exact validated destination to the provider.
- Provider inbound INVITE reaches the registered softphone.
- ACK, CANCEL, BYE and re-INVITE work in both directions.
- Only one active call is accepted; a second INVITE receives 486 Busy Here.
- Two-way audio and DTMF work after NAT/media traversal is implemented.
- Provider source-IP restrictions and authentication failures fail closed.
- Logs redact phone numbers and never print passwords or digest secrets.
