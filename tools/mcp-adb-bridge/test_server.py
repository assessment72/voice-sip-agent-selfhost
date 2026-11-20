import importlib.util
import pathlib
import unittest
from unittest.mock import patch

SERVER = pathlib.Path(__file__).with_name("server.py")
SPEC = importlib.util.spec_from_file_location("voice_sip_mcp_server", SERVER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class McpProtocolTests(unittest.TestCase):
    @patch.object(
        MODULE.subprocess,
        "run",
        return_value=__import__("subprocess").CompletedProcess(
            args=["adb"], returncode=0,
            stdout='Broadcast completed: result=0, data="{\\"ok\\":true}"',
            stderr="",
        ),
    )
    def test_broadcast_decodes_ordered_result_data(self, mocked_run):
        self.assertEqual({"ok": True}, MODULE.broadcast("call-status"))
        self.assertIn("call-status", mocked_run.call_args.args[0])

    def test_initialize_advertises_tools(self):
        result = MODULE.handle({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {"protocolVersion": "2024-11-05"},
        })
        self.assertEqual("2024-11-05", result["result"]["protocolVersion"])
        self.assertIn("tools", result["result"]["capabilities"])

    def test_initialized_notification_has_no_response(self):
        self.assertIsNone(MODULE.handle({
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
        }))

    def test_tools_list_contains_approval_gate(self):
        result = MODULE.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in result["result"]["tools"]}
        self.assertIn("sip_request_call_action", names)
        self.assertIn("sip_pending_call_approvals", names)
        self.assertNotIn("sip_approve_call_action", names)
        self.assertIn("sip_execute_approved_action", names)

    def test_approval_cannot_be_granted_through_mcp_tool(self):
        result = MODULE.handle({
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/list",
        })
        names = {tool["name"] for tool in result["result"]["tools"]}
        self.assertNotIn("sip_approve_call_action", names)

    @patch.object(MODULE, "broadcast")
    def test_invalid_dial_request_is_rejected_before_adb(self, mocked_broadcast):
        with self.assertRaises(ValueError):
            MODULE.invoke_tool("sip_request_call_action", {"action": "DIAL"})
        mocked_broadcast.assert_not_called()

    @patch.object(MODULE, "broadcast", return_value={"ok": True, "detail": "not approved"})
    def test_execute_only_forwards_approval_id(self, mocked_broadcast):
        MODULE.invoke_tool("sip_execute_approved_action", {"approval_id": "approval-123"})
        mocked_broadcast.assert_called_once_with(
            "call-execute", {"approval_id": "approval-123"}
        )

    @patch.object(MODULE, "broadcast", return_value={"ok": True, "approval": {"id": "test"}})
    def test_request_tool_only_creates_pending_approval(self, mocked_broadcast):
        result = MODULE.handle({
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "sip_request_call_action",
                "arguments": {"action": "DIAL", "target": "+967700000000"},
            },
        })
        mocked_broadcast.assert_called_once_with("call-request", {
            "action": "DIAL",
            "call_id": None,
            "target": "+967700000000",
            "route": "auto",
            "digit": None,
        })
        self.assertFalse(result["result"]["isError"])


if __name__ == "__main__":
    unittest.main()
