# NAT traversal design for the embedded SIP registrar

Status: architecture decision / implementation plan. This document does not claim that public-Internet calling is implemented.

## Product constraints

- The SIP registrar, digest authentication, registered-phone state, call routing, and SIM/GSM control remain on the gateway Android device.
- The gateway may be behind carrier-grade NAT (CGNAT), have no public IPv4 address, and have no router port-forwarding.
- No VPN is required.
- A public rendezvous/transport relay is permitted, but it must not become a PBX, registrar, credential store, or call-control authority.
- A public TURN service is used for media only when a direct ICE path cannot be established.
- The main branch is not modified; implementation belongs on feat/embedded-sip-nat-server.

## Why TURN alone is insufficient

TURN (RFC 8656) allocates a relayed transport address for media. It does not make the gateway's SIP UDP socket publicly reachable and does not route REGISTER, INVITE, responses, or in-dialog requests. STUN alone is not reliable through symmetric NAT or CGNAT. Therefore signalling reachability and media reachability are separate problems and require separate mechanisms.

## Target topology

1. **Gateway Android app**
   - Runs the existing embedded SIP registrar and remains authoritative for SIP accounts and Digest authentication.
   - Opens an outbound, authenticated, persistent TLS WebSocket (wss) connection to a public rendezvous relay. Outbound connections work through ordinary NAT/CGNAT.
   - Sends/receives SIP datagrams through this tunnel. The tunnel is a transport path, not a registrar.
   - Runs an ICE agent for each media session and gathers host, server-reflexive, and relayed candidates.

2. **Public signalling rendezvous relay**
   - Exposes a stable public TLS endpoint.
   - Associates one authenticated gateway tunnel with an opaque gateway/device route identifier.
   - Forwards SIP messages between remote SIP clients and that live gateway tunnel. SIP Digest challenges and verification remain on the gateway.
   - Does not create accounts, store SIP passwords, decide call routing, originate calls, or own dialog state beyond the minimum transient routing needed to forward packets and return responses.
   - Enforces TLS, per-device credentials, rate limits, message-size limits, connection limits, and short-lived routing state. It must not log SIP bodies, phone numbers, Authorization headers, or SDP credentials.

3. **Remote SIP clients / other gateways**
   - Reach the public signalling endpoint from any network.
   - Authenticate against the embedded registrar; a failed or missing Digest authentication must never trigger GSM dialing.
   - Use ICE for media. A client must support the selected SIP transport and ICE/TURN profile.

4. **TURN service**
   - Publicly reachable over UDP and, where needed, TCP/TLS (commonly 3478/5349).
   - Issues short-lived credentials (for example, TURN REST-style time-limited credentials); no permanent shared TURN secret is embedded in the APK.
   - Is selected as a media candidate only when direct candidate pairs fail or policy requires relay.
   - Is not used as a substitute for the SIP signalling relay.

## Required implementation changes

### Signalling transport
- Introduce a transport boundary under SipUserAgent so SIP message dispatch is independent of the current UDP socket.
- Keep the current LAN UDP transport for local deployments.
- Add a reconnecting WSS tunnel transport with heartbeat, bounded queues, backoff, TLS certificate validation, and clean shutdown.
- Preserve the actual remote logical peer and tunnel route for transaction responses, ACK/CANCEL, retransmissions, and in-dialog requests. Do not trust a user-supplied Contact or source address as an authenticated route.
- Bind the gateway tunnel to a provisioned opaque device identity; do not use the SIP password as tunnel authentication.
- Add replay protection / connection generation checks so messages from a superseded tunnel cannot mutate current registration state.

### Registrar and device model
- Replace the current single peerAor / peerContactUri registration slot with a bounded, expiring registration table keyed by normalized AoR and Contact/instance identity.
- Keep Digest auth, nonce lifetime, failure throttling, and INVITE authorization on the gateway.
- Support explicit unregister, expiry, refresh, duplicate registration replacement, and per-device call delivery.
- Ensure one device cannot overwrite or receive another device's registration without valid credentials and ownership checks.

### Media (ICE + TURN)
- Add a real ICE agent; merely adding a=ice-* strings to SDP is not sufficient.
- Gather and exchange ICE ufrag/password and candidates in SDP; perform connectivity checks, nomination, consent freshness, and restart after network changes.
- Implement TURN Allocate, Refresh, CreatePermission, ChannelBind, Send/Data indications, and lifetime/error handling through a maintained ICE/TURN library or a separately tested implementation.
- Change RTP send/receive to use the nominated ICE candidate pair / TURN channel rather than assuming one fixed RtpParams.remote UDP address.
- Validate inbound packets against the selected candidate pair and negotiated media parameters; do not accept arbitrary packets just because they are syntactically RTP.
- Retain the existing direct RTP path for LAN compatibility when ICE is disabled.

## Configuration and secrets

The gateway configuration will eventually need:
- signalling relay wss URL and opaque device identity;
- a per-device tunnel credential stored using Android Keystore-backed storage;
- TURN URI list and a short-lived credential provider;
- an explicit mode: LAN-only, public-relay, or automatic.

No relay password, TURN shared secret, or long-lived credential may be committed to source control or hard-coded in an APK. TURN credentials should be fetched or minted through an authenticated provisioning flow and expire quickly.

## Acceptance tests (must pass before calling this production-ready)

1. Gateway on a mobile-data connection behind CGNAT can establish and recover its outbound WSS tunnel.
2. A remote client on an unrelated network can REGISTER through the relay; Digest challenge and verification are performed by the gateway.
3. Invalid credentials, expired credentials, replayed messages, and an unprovisioned device are rejected.
4. Remote INVITE reaches the gateway; an unauthenticated INVITE never starts a GSM call.
5. SIP responses, ACK, CANCEL, BYE, re-INVITE, and MESSAGE route correctly through the same logical tunnel.
6. Two or more remote devices can register without overwriting one another; expiry and unregister work.
7. ICE selects a direct path when possible and TURN relay when direct connectivity fails.
8. Audio works in both directions through TURN across different NAT types; DTMF, packet loss, call teardown, and ICE restart are tested.
9. Network loss, Android process recreation, Doze/backgrounding, tunnel reconnect, and TURN credential expiry recover safely.
10. Security tests confirm that relay logs and diagnostics contain no SIP passwords, Authorization values, phone numbers, SDP ICE passwords, or message bodies.

## Current repository gaps observed during review

- SipTransport is UDP-only and owns a DatagramSocket.
- SipUserAgent dispatch is coupled to SipTransport.
- Server mode has one peerAor and one peerContactUri, so it is not a multi-device registrar.
- Sdp emits a basic RTP/AVP offer with a local address and port; it does not implement ICE.
- RtpSession sends to one fixed remote address and has no STUN/TURN allocation or ICE candidate-pair selection.
- docs/internet-security.md currently describes public-IP/port-forwarding as the only Internet route; that is a separate legacy mode and does not satisfy the no-public-IP requirement.

## External dependencies / deployment blockers

End-to-end implementation and Internet acceptance testing require:
- a public signalling relay endpoint (or an agreed hosting target) with WSS ingress and the narrow forwarding contract above;
- a reachable TURN service and a secure way to issue short-lived credentials;
- test accounts/devices on at least two independent networks, including one CGNAT/mobile-data path.

A random public TURN server cannot be assumed to be reliable, secure, or available for production. Do not claim the system works from any location until the acceptance tests above pass.
