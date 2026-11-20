#!/usr/bin/env python3
"""MCP stdio bridge for the Android gateway's DUMP-protected ADB receiver.

Run this on a trusted computer with Android platform-tools installed and the gateway
phone connected/authorized over USB or wireless debugging. No TCP listener is opened.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from typing import Any

ADB = os.environ.get("ADB", "adb")
SERIAL = os.environ.get("ANDROID_SERIAL", "").strip()
COMPONENT = os.environ.get(
    "SIP_GATEWAY_COMPONENT",
    "io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver",
)
PROTOCOL_VERSION = "2024-11-05"


def broadcast(op: str, extras: dict[str, Any] | None = None) -> dict[str, Any]:
    command = [ADB]
    if SERIAL:
        command += ["-s", SERIAL]
    command += ["shell", "am", "broadcast", "-n", COMPONENT, "--es", "op", op]
    for key, value in (extras or {}).items():
        if value is None:
            continue
        flag = "--ei" if isinstance(value, int) and not isinstance(value, bool) else "--es"
        command += [flag, key, str(value)]

    completed = subprocess.run(
        command, capture_output=True, text=True, timeout=15, check=False
    )
    output = (completed.stdout or "") + "\n" + (completed.stderr or "")
    if completed.returncode != 0:
        raise RuntimeError(f"ADB command failed ({completed.returncode}): {output.strip()}")

    # am broadcast prints ordered-broadcast result data as data="...".
    match = re.search(r'(?:^|, )data=("(?:\\.|[^"])*"|.*)$', output, re.MULTILINE)
    if not match:
        raise RuntimeError(
            "Android did not return broadcast result data. Confirm the phone is connected, "
            "the app is installed, and the shell has the DUMP permission. Output: "
            + output.strip()
        )
    encoded = match.group(1).strip()
    try:
        decoded = json.loads(encoded) if encoded.startswith('"') else encoded
        payload = json.loads(decoded) if isinstance(decoded, str) else decoded
    except (json.JSONDecodeError, TypeError) as exc:
        raise RuntimeError(f"Could not decode Android response: {encoded}") from exc
    if not isinstance(payload, dict):
        raise RuntimeError("Android returned a non-object response")
    return payload


TOOLS = [
    {
        "name": "sip_gateway_status",
        "description": "Read gateway health and SIP/cellular readiness. Read-only.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "sip_list_calls",
        "description": "List active calls and recent call history. Read-only.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "sip_pending_call_approvals",
        "description": "List pending call-action approvals awaiting an explicit user decision.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "sip_request_call_action",
        "description": (
            "Create a pending approval for one exact call action. This does NOT execute the call. "
            "The user must explicitly approve the returned approval_id before execution. "
            "Actions: DIAL, ANSWER, REJECT, HANGUP, DTMF. DIAL requires target; other actions "
            "require call_id; DTMF also requires digit. route defaults to auto."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["DIAL", "ANSWER", "REJECT", "HANGUP", "DTMF"]},
                "call_id": {"type": "string"},
                "target": {"type": "string"},
                "route": {"type": "string", "default": "auto"},
                "digit": {"type": "string", "minLength": 1, "maxLength": 1},
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
    {
        "name": "sip_approve_call_action",
        "description": (
            "Approve one pending call action only after the user explicitly confirms the exact "
            "number/call and route. Approval expires after 90 seconds and is single-use."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"approval_id": {"type": "string"}},
            "required": ["approval_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "sip_reject_call_action",
        "description": "Reject a pending call-action approval. Does not affect an active call.",
        "inputSchema": {
            "type": "object",
            "properties": {"approval_id": {"type": "string"}},
            "required": ["approval_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "sip_execute_approved_action",
        "description": (
            "Execute a previously approved call action. The Android controller rechecks the exact "
            "operation and consumes the approval atomically; replay or mismatch is blocked."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"approval_id": {"type": "string"}},
            "required": ["approval_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "sip_call_audit",
        "description": "Read recent approval and call-action audit entries. Read-only.",
        "inputSchema": {
            "type": "object",
            "properties": {"limit": {"type": "integer", "minimum": 1, "maximum": 500}},
            "additionalProperties": False,
        },
    },
]


def invoke_tool(name: str, args: dict[str, Any]) -> dict[str, Any]:
    if name == "sip_gateway_status":
        return broadcast("call-status")
    if name == "sip_list_calls":
        return broadcast("call-list")
    if name == "sip_pending_call_approvals":
        return broadcast("call-pending")
    if name == "sip_request_call_action":
        extras = {
            "action": args.get("action"),
            "call_id": args.get("call_id"),
            "target": args.get("target"),
            "route": args.get("route", "auto"),
            "digit": args.get("digit"),
        }
        return broadcast("call-request", extras)
    if name == "sip_approve_call_action":
        return broadcast("call-approve", {"approval_id": args["approval_id"]})
    if name == "sip_reject_call_action":
        return broadcast("call-reject", {"approval_id": args["approval_id"]})
    if name == "sip_execute_approved_action":
        return broadcast("call-execute", {"approval_id": args["approval_id"]})
    if name == "sip_call_audit":
        return broadcast("call-audit", {"limit": args.get("limit", 100)})
    raise ValueError(f"Unknown tool: {name}")


def response(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def handle(message: dict[str, Any]) -> dict[str, Any] | None:
    method = message.get("method")
    request_id = message.get("id")
    params = message.get("params") or {}

    if method == "notifications/initialized":
        return None
    if method == "initialize":
        return response(request_id, {
            "protocolVersion": params.get("protocolVersion", PROTOCOL_VERSION),
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "voice-sip-agent-adb-mcp", "version": "0.1.0"},
        })
    if method == "ping":
        return response(request_id, {})
    if method == "tools/list":
        return response(request_id, {"tools": TOOLS})
    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        try:
            result = invoke_tool(name, args)
            return response(request_id, {
                "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                "isError": not result.get("ok", False),
            })
        except Exception as exc:
            return response(request_id, {
                "content": [{"type": "text", "text": str(exc)}],
                "isError": True,
            })
    if request_id is None:
        return None
    return error(request_id, -32601, f"Method not found: {method}")


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
            if not isinstance(message, dict):
                raise ValueError("JSON-RPC message must be an object")
            result = handle(message)
            if result is not None:
                sys.stdout.write(json.dumps(result, separators=(",", ":"), ensure_ascii=False) + "\n")
                sys.stdout.flush()
        except Exception as exc:
            sys.stderr.write(f"MCP bridge error: {exc}\n")
            sys.stderr.flush()


if __name__ == "__main__":
    main()
