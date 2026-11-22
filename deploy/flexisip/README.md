# Flexisip deployment for the Android SIP-to-GSM gateway

This directory prepares a **private Flexisip proxy/registrar/media relay** for the
voice-sip-agent-selfhost gateway. It is a deployment track only; it does not change
the Android application or deploy anything to a cloud provider.

## Target VPS

Preferred production-like starting size for one active call:

- Ubuntu Server 24.04 LTS, x86_64 / amd64
- 2 vCPU
- 4 GB RAM
- 30 GB SSD/NVMe
- Dedicated public IPv4 (not shared IPv4 behind provider NAT)
- Provider firewall/security-group control for inbound UDP and TCP

The 4 GB size is a practical starting target, not a Flexisip minimum. Flexisip's
documentation says RAM needs depend on workload and cites about 8 GB for up to
10,000 connections. We are targeting one simultaneous call, not thousands.

**Architecture check:** the official Belledonne Ubuntu 24.04 APT repository currently
documents `amd64` packages. The installer deliberately stops on ARM64 rather than
silently trying an unsupported package architecture.

## No-card hosting reality

Do not use the 192 MB NAT-only free VPS offers for this service. SIP registration may
work in a narrow test, but shared IPv4/limited port forwarding and insufficient RAM
make reliable SIP + RTP relay unsuitable.

The VPSFree website currently displays different offers: its short-term free NAT plan
is 192 MB / 3 GB / shared IPv4, while a separate Linux VPS page advertises a 2 vCPU,
2 GB, 40 GB KVM example with IPv4 and IPv6. The live inventory/checkout must confirm
that exact KVM plan is actually free, has a dedicated public IPv4, and exposes arbitrary
UDP/TCP ports before we rely on it. Do not assume the headline example is the free tier.

AWS's new Free Plan may allow most new users to sign up without a payment method, but
it is a time-limited credit plan (up to six months), not permanent free hosting; AWS
may request additional verification. It is a possible route only if the signup flow
accepts the account without a card and EC2 is available under the selected plan.

## What is implemented in the Android gateway today

The inspected branch `audit/rtp-local-server-hardening` has an external-PBX mode:
the Android gateway sends a SIP REGISTER to the configured PBX and handles incoming
INVITEs from that upstream. The built-in SIP stack is **UDP-only**; the settings
documentation says TCP/TLS are not implemented in the current builtin stack. Therefore:

- Android gateway -> Flexisip: use SIP/UDP on port 5060 for the first integration.
- External softphones that support it -> Flexisip: SIP/TLS on 5061 can be enabled.
- Do not set the Android gateway to TLS until a TLS-capable SIP stack is added and tested.
- The app's incoming INVITE handler reads `X-VSA-Dial`; Flexisip proxy routing must
  preserve this header. The proxy does not invent the header automatically.
- A normal softphone dial string does not necessarily add `X-VSA-Dial`. For the first
  SIP-to-GSM test, use a client/test tool that can send the header, or implement a
  controlled dial-plan/B2BUA route. This is a separate integration test, not yet claimed
  as working end-to-end.

For GSM-to-SIP, configure the Android app's "Cellular inbound -> SIP target" to the
registered remote SIP account on the Flexisip domain.

## Install package (does not start the service)

The script is intentionally conservative. It installs the official package and creates
the default config only. It does **not**:
- open/enable a firewall;
- write SIP passwords;
- issue TLS certificates;
- change the proxy routing/authentication config;
- start or enable the proxy service.

Run it from an SSH session on the new VPS (Termux on the HONOR can be the SSH client):

```bash
sudo apt-get update
sudo apt-get install -y curl
curl -fsSL https://raw.githubusercontent.com/assessment72/voice-sip-agent-selfhost/deploy/flexisip-ubuntu24/deploy/flexisip/install-ubuntu24.sh -o install-flexisip.sh
sudo bash install-flexisip.sh
```

The installer checks Ubuntu 24.04 and amd64, adds Belledonne's signed Ubuntu Noble
repository, installs `bc-flexisip`, and generates `/etc/flexisip/flexisip.conf` using
Flexisip's documented default-config command.

## Network plan (apply only after VPS is provisioned)

| Port | Protocol | Purpose |
|---|---|---|
| 22 | TCP | SSH administration (restrict source IP where possible) |
| 5060 | UDP + TCP | SIP |
| 5061 | TCP | SIP over TLS |
| 10000-20000 | UDP | RTP/RTCP media relay range |
| 3478 | UDP | STUN, only if enabled/needed |
| 80 | TCP | Temporary HTTP-01 certificate issuance/renewal, if using Certbot standalone |

Open these ports in **both** the cloud-provider firewall/security group and Ubuntu UFW.
Never enable UFW before allowing SSH on the actual SSH port.

## Configuration sequence after we know the domain and public IP

1. Create an A record such as `sip.example.net -> VPS_PUBLIC_IPV4`.
2. Confirm the DNS record resolves to the VPS.
3. Obtain a TLS certificate for that hostname (Let's Encrypt/Certbot).
4. Configure `[global]` transports for SIP UDP/TCP and SIPS/TLS, and point Flexisip
   at the certificate chain and private key.
5. Enable `[module::Registrar]` for the SIP domain. For a one-server test, the internal
   in-memory registrar is acceptable; registrations return after clients reconnect.
6. Enable `[module::Authentication]`, restrict `auth-domains` to the chosen SIP domain,
   and use a password database. Do not disable authentication on a public server.
7. Enable `[module::MediaRelay]` and constrain `sdp-port-range-min=10000`,
   `sdp-port-range-max=20000`, and `max-calls=1` for the first live test.
8. Create two distinct SIP accounts: one for the HONOR gateway and one for the remote
   softphone. Use strong, unique passwords.
9. Validate config and service logs before opening public SIP to all sources.
10. Test REGISTER, INVITE signalling, SDP relay, two-way RTP, hangup, and reconnect after
    mobile-network changes.

The exact configuration file will be finalized against the installed Flexisip version
and the actual hostname. Do not paste credentials into GitHub or chat.

## First call topology

```
Remote SIP client
      |
      | SIP/TLS 5061 or SIP/UDP 5060
      v
Flexisip proxy + registrar + MediaRelay (public IPv4)
      ^
      | SIP/UDP REGISTER + keepalive (outbound from phone; NAT friendly)
      |
HONOR Android gateway (external-PBX mode)
      |
      | Android Telecom / audio bridge
      v
SIM voice network
```

The phone does not need a public IP. It initiates registration to Flexisip from behind
NAT. Flexisip's media relay anchors RTP so both sides send media to the VPS.

## Current status

- [x] Dedicated deployment branch created from `audit/rtp-local-server-hardening`.
- [x] Ubuntu 24.04 amd64 package installer prepared.
- [x] Architecture and app transport limitations documented.
- [ ] VPS account and instance provisioned.
- [ ] Public IPv4, hostname and DNS verified.
- [ ] TLS certificate issued.
- [ ] SIP auth + registrar + MediaRelay config reviewed.
- [ ] Android external-PBX settings tested against Flexisip.
- [ ] SIP-to-GSM custom-header route tested.
- [ ] Two-way audio verified on the HONOR MTN-NX1.

No cloud instance has been created and no service has been exposed to the internet yet.
