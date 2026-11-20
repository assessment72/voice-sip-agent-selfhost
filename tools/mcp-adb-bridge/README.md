# Voice SIP Agent — local MCP over ADB

This MCP server runs on a trusted computer and communicates with the Android gateway through the existing ADB-only receiver. It uses MCP stdio (stdin/stdout), so it does not open a TCP listener or expose the phone to the internet.

## Requirements

- Python 3.10+
- Android platform-tools (adb)
- Gateway app installed on the phone with USB debugging or wireless debugging enabled
- The computer authorized in Android's debugging prompt

## Start

From the repository root:

~~~bash
adb devices
python tools/mcp-adb-bridge/server.py
~~~

Configure the MCP client to launch the command above as a stdio server. If more than one Android device is connected, set ANDROID_SERIAL. You may override ADB or SIP_GATEWAY_COMPONENT through environment variables.

## Available tools

- sip_gateway_status — read gateway health
- sip_list_calls — list active calls and recent history\n- sip_pending_call_approvals — list pending approval requests
- sip_request_call_action — create a pending DIAL / ANSWER / REJECT / HANGUP / DTMF approval
- sip_reject_call_action — reject a pending operation
- sip_execute_approved_action — execute a previously approved operation once
- sip_call_audit — read recent approval audit entries

## Mandatory approval sequence

1. Call sip_request_call_action.
2. Show the exact action, target/call ID, route and DTMF digit to the user.
3. Open the gateway app on the phone and tap **Approve** on the matching request. Approval is deliberately unavailable as an MCP tool.
4. Call sip_execute_approved_action using that approval ID.

Approvals expire after 90 seconds, are bound to the exact operation, and can be consumed only once. A request alone never places or changes a call.

## Security boundaries

- Android's receiver is protected by android.permission.DUMP; only an authorized ADB shell should invoke it.
- Keep USB/wireless debugging authorized only for trusted computers. Do not forward ADB over the public internet.
- The MCP server has no network listener and writes protocol messages only to stdout.
- The ADB/MCP path cannot approve requests. Approval is performed only by tapping the matching request in the Android app.
- The app's call-control layer does not touch RTP/audio. The existing bridge remains responsible for media.
- The first auto route uses the first ready endpoint (cellular then SIP in the app). It is not multi-SIM load balancing.
- Approval and audit data are currently in memory and are lost when the app process restarts. Durable storage and a native approval UI remain follow-up work.
- The ADB result-data round trip must be verified on the target Android device before enabling real call operations. Start with status/list tools and test calls only after confirming the device-side behavior.

The repository CI runs Python syntax checks and protocol-level unit tests for this bridge.\n\nThis integration does not alter SIP credentials or gateway settings and does not initiate any call during startup.
