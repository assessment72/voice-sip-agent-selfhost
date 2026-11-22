#!/usr/bin/env bash
set -u

DOMAIN="${1:-}"
fail=0
warn=0

ok()   { printf '[OK]   %s\n' "$1"; }
bad()  { printf '[FAIL] %s\n' "$1"; fail=$((fail+1)); }
note() { printf '[NOTE] %s\n' "$1"; warn=$((warn+1)); }

if [[ -r /etc/os-release ]]; then
  . /etc/os-release
  [[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 24.04 ]] && ok "Ubuntu 24.04 LTS" || bad "Expected Ubuntu 24.04 LTS; found ${PRETTY_NAME:-unknown}"
else
  bad "Cannot read /etc/os-release"
fi

ARCH="$(dpkg --print-architecture 2>/dev/null || uname -m)"
[[ "$ARCH" == amd64 || "$ARCH" == x86_64 ]] && ok "amd64 architecture" || bad "Flexisip's documented Ubuntu package is amd64; found $ARCH"

CPU="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
(( CPU >= 2 )) && ok "$CPU vCPU" || bad "Need at least 2 vCPU; found $CPU"

MEM_KB="$(awk '/MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
MEM_GB="$(awk -v k="$MEM_KB" 'BEGIN {printf "%.2f", k/1048576}')"
awk -v k="$MEM_KB" 'BEGIN {exit !(k >= 3670016)}' && ok "$MEM_GB GiB RAM" || bad "Need about 4 GiB RAM; found $MEM_GB GiB"

ROOT_GB="$(df -BG --output= size / 2>/dev/null | tail -n 1 | tr -dc '0-9')"
ROOT_GB="${ROOT_GB:-0}"
(( ROOT_GB >= 30 )) && ok "$ROOT_GB GiB root filesystem" || bad "Need at least 30 GiB root filesystem; found $ROOT_GB GiB"

PUBLIC_IP=""
if command -v curl >/dev/null 2>&1; then
  PUBLIC_IP="$(curl -4fsS --max-time 8 https://api.ipify.org 2>/dev/null || true)"
fi
if [[ "$PUBLIC_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  ok "Outbound public IPv4 detected: $PUBLIC_IP"
  note "Confirm this address is reserved/static and inbound ports are allowed by the cloud firewall."
else
  bad "Could not detect outbound public IPv4; check internet connectivity and IPv4 egress."
fi

if [[ -n "$DOMAIN" ]]; then
  if command -v getent >/dev/null 2>&1 && getent ahostsv4 "$DOMAIN" >/dev/null 2>&1; then
    DNS_IP="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1 {print $1}')"
    ok "DNS resolves $DOMAIN to $DNS_IP"
    [[ -z "$PUBLIC_IP" || "$DNS_IP" == "$PUBLIC_IP" ]] || note "DNS IP differs from outbound public IP; verify the VPS public-IP mapping."
  else
    bad "DNS name $DOMAIN does not resolve to IPv4 yet."
  fi
else
  note "Pass a SIP hostname as the first argument to test its DNS A record."
fi

if command -v flexisip >/dev/null 2>&1 || [[ -x /opt/belledonne-communications/bin/flexisip ]]; then
  ok "Flexisip executable found"
else
  note "Flexisip is not installed yet."
fi

echo
echo "Preflight summary: $fail failure(s), $warn note(s)."
(( fail == 0 )) || exit 1
