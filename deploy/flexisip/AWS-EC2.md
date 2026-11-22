# Create the Flexisip VPS on AWS from an Android phone

This guide uses the AWS **Free account plan** for a temporary development/test server.
It is not permanent free hosting.

## Before creating the instance

1. Create a new AWS account using the **Free account plan**.
2. AWS says a payment method is not required for most new customers, but it may request one or other identity checks in some cases.
3. Do not upgrade to the Paid plan.
4. Confirm the account shows its Free plan and available credits before launching resources.

AWS currently documents up to USD 200 in credits for up to six months. The Free plan ends after six months or when credits run out, whichever comes first. Do not use this as a permanent production guarantee.

## EC2 configuration

In AWS Console (open the browser's Desktop site mode if the mobile layout hides fields):

- Service: EC2 -> Instances -> Launch instance
- Name: `flexisip-lab`
- AMI: Ubuntu Server 24.04 LTS, **64-bit x86**
- Instance type: `c7i-flex.large` (2 vCPU, 4 GiB RAM). AWS lists this among the EC2 Free Tier-eligible types for new-account offers; confirm the console shows the eligibility label in your region before launch.
- Key pair: create a new ED25519 key pair if offered; download the private key once and keep it private. If ED25519 is unavailable, use RSA.
- Storage: 30 GiB gp3.
- Network: default VPC is acceptable for a first test; enable auto-assign public IPv4.
- Public IP: keep the instance's public IPv4 recorded. An automatically assigned IPv4 may change after stop/start; update DNS if it does.
- IAM role: none required for this initial installation.

## Security group — start closed

Initially allow only:
- TCP 22 (SSH), source = your current public IP if known; otherwise use a temporary restricted rule and tighten it after login.

Do **not** open SIP/RTP ports before Flexisip authentication, TLS and firewall rules are configured. After hardening, add:
- UDP 5060 and TCP 5060 for SIP;
- TCP 5061 for SIP/TLS;
- UDP 10000-20000 for RTP/RTCP relay;
- UDP 3478 only if the STUN listener is enabled.

Apply the same policy in the EC2 security group and Ubuntu firewall. Never expose an unauthenticated SIP proxy.

## After the instance is running

1. Copy its public IPv4 address from EC2.
2. Connect from Termux using the private key:
   ```bash
   pkg update
   pkg install openssh
   chmod 600 ~/storage/downloads/flexisip-lab.pem
   ssh -i ~/storage/downloads/flexisip-lab.pem ubuntu@PUBLIC_IPV4
   ```
   Change the key path to the actual download location. Do not send the private key to anyone.
3. Run the repository installer:
   ```bash
   sudo apt-get update
   sudo apt-get install -y curl
   curl -fsSL https://raw.githubusercontent.com/assessment72/voice-sip-agent-selfhost/deploy/flexisip-ubuntu24/deploy/flexisip/install-ubuntu24.sh -o install-flexisip.sh
   sudo bash install-flexisip.sh
   ```
4. Run the read-only hardware/network check:
   ```bash
   bash <(curl -fsSL https://raw.githubusercontent.com/assessment72/voice-sip-agent-selfhost/deploy/flexisip-ubuntu24/deploy/flexisip/preflight-vps.sh)
   ```

## Cost guardrails

- Keep the account on the Free plan. Do not click "Upgrade" or "Convert to Paid".
- Check EC2, EBS and public IPv4 usage/credits before launch.
- The Free plan is temporary. Export configs and logs before expiry.
- Do not create an Elastic IP or extra resources unless their price is confirmed.
- If the console does not show the chosen instance type as eligible or the Free plan cannot launch it, stop and send a screenshot of the pricing/eligibility panel with account IDs and personal data hidden.

## Important Android integration limitation

The current gateway fork's built-in SIP implementation is UDP-only. Configure the HONOR app to register to Flexisip using UDP/5060. TLS/5061 can be offered to other softphones, but the app itself needs a TLS-capable SIP stack before it can use TLS.
