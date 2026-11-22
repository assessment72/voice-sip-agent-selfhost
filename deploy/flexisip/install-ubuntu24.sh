#!/usr/bin/env bash
set -Eeuo pipefail

# Installs the official Flexisip package on Ubuntu 24.04 x86_64.
# Intentionally does NOT open firewall ports, issue certificates, edit SIP
# credentials, or start the proxy. Those steps require a domain and review.

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

if [[ ! -r /etc/os-release ]]; then
  echo "Cannot identify operating system." >&2
  exit 1
fi
# shellcheck disable=SC1091
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" || "${VERSION_ID:-}" != "24.04" ]]; then
  echo "This installer supports Ubuntu 24.04 LTS only." >&2
  exit 1
fi

ARCH="$(dpkg --print-architecture)"
if [[ "$ARCH" != "amd64" ]]; then
  echo "Unsupported architecture: $ARCH. The official Ubuntu repository currently documents amd64 packages; do not continue on ARM." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg

install -d -m 0755 /usr/share/keyrings
TMP_KEY="$(mktemp)"
POLICY_RC_D="/usr/sbin/policy-rc.d"
POLICY_BACKUP=""
if [[ -e "$POLICY_RC_D" ]]; then
  POLICY_BACKUP="$(mktemp)"
  cp -a "$POLICY_RC_D" "$POLICY_BACKUP"
fi
restore_policy() {
  if [[ -n "$POLICY_BACKUP" ]]; then
    cp -a "$POLICY_BACKUP" "$POLICY_RC_D"
    rm -f "$POLICY_BACKUP"
  else
    rm -f "$POLICY_RC_D"
  fi
}
trap 'rm -f "$TMP_KEY"; restore_policy' EXIT

curl --fail --show-error --silent --location \
  https://download.linphone.org/snapshots/ubuntu/pubkey.gpg \
  --output "$TMP_KEY"
gpg --batch --yes --dearmor --output /usr/share/keyrings/belledonne-archive-keyring.gpg "$TMP_KEY"
chmod 0644 /usr/share/keyrings/belledonne-archive-keyring.gpg

cat > /etc/apt/sources.list.d/belledonne.list <<'EOF'
deb [arch=amd64 signed-by=/usr/share/keyrings/belledonne-archive-keyring.gpg] https://download.linphone.org/snapshots/ubuntu noble stable
EOF

apt-get update

# Prevent package maintainer scripts from starting daemons before we configure
# SIP authentication, TLS and the media-relay range.
cat > "$POLICY_RC_D" <<'EOF'
#!/bin/sh
exit 101
EOF
chmod 0755 "$POLICY_RC_D"
apt-get install -y bc-flexisip ufw certbot
restore_policy
trap 'rm -f "$TMP_KEY"' EXIT

FLEXISIP_BIN="/opt/belledonne-communications/bin/flexisip"
if [[ ! -x "$FLEXISIP_BIN" ]]; then
  FLEXISIP_BIN="$(command -v flexisip || true)"
fi
if [[ -z "$FLEXISIP_BIN" || ! -x "$FLEXISIP_BIN" ]]; then
  echo "Flexisip package installed but executable was not found. Inspect package files before continuing." >&2
  exit 1
fi

install -d -m 0750 /etc/flexisip
if [[ ! -s /etc/flexisip/flexisip.conf ]]; then
  "$FLEXISIP_BIN" --dump-default all > /etc/flexisip/flexisip.conf
  chmod 0640 /etc/flexisip/flexisip.conf
fi

echo
echo "Flexisip package installation completed."
echo "Binary: $FLEXISIP_BIN"
echo "Config: /etc/flexisip/flexisip.conf"
echo
echo "NOT done: DNS, TLS certificate, SIP users, MediaRelay range, firewall, service start."
echo "Review deploy/flexisip/README.md before enabling flexisip-proxy."
