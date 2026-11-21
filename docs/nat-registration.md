# SIP registration through NAT

The built-in SIP stack can run in outbound registrar/client mode as well as its existing
device-in-itself registrar mode.

## Gateway app settings

1. Turn **Device-in-itself mode** off. This selects outbound registration to the central SIP server.
2. Set PBX / Registrar host to `104.243.37.23` and the SIP UDP port supplied by the server operator.
3. Enter the assigned SIP username, authentication username (if different), password, and SIP domain.
4. Keep transport set to UDP. The built-in stack does not implement TCP/TLS.
5. Keep local SIP port at its configured value (default 5062). The app sends REGISTER and OPTIONS
   from the same bound UDP socket.
6. The app requests a 300-second registration lease and refreshes before expiry. It sends an OPTIONS
   NAT keepalive every 25 seconds; set `natKeepaliveIntervalSec=0` only to disable it.

Passwords continue to be stored encrypted with Android Keystore.

## NAT and media behavior

- SIP requests include `Via: ...;rport`; responses must return to the observed source address/port.
- The app sends periodic SIP OPTIONS from the REGISTER socket to keep UDP NAT/CGNAT state alive.
- RTP uses the configured UDP range (default 10000-20000). Symmetric RTP learns a changed source
  port after receiving a packet from the IP address advertised in SDP.
- G.711 PCMU/PCMA and RFC 4733 telephone events are supported by the built-in stack.
- The app cannot discover an arbitrary public NAT address by itself. For inbound calls, the central
  registrar/proxy must rewrite the registered Contact to the observed REGISTER source address/port
  (or provide an equivalent outbound-flow mechanism), and route requests back over that flow.
- For reliable two-way audio across NAT, the central server must anchor/relay media and advertise
  its reachable RTP address in SDP. A private RFC1918 address in the server's SDP cannot be reached
  by a gateway on another network. The server's RTP relay must accept symmetric RTP.

## Validation

A successful registration is visible as `registered (<lease> s)` in the app status/log. Verify that
the registrar sees the gateway's public source IP and translated UDP source port, not its private
LAN address. Then place a test call and confirm nonzero RTP transmit and receive counters in both
directions. The app-side NAT keepalive does not replace registrar-side Contact rewriting or an RTP
media relay.
