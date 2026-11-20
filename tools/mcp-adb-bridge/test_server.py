import importlib.util
import pathlib
import unittest
from unittest.mock import patch

SERVER = pathlib.Path(__file__).with_name("server.py")
SPEC = importlib.util.spec_from_file_location("voice_sip_mcp_server", SERVER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class McpProtocolTests(unittest.TestCase):
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
        self.assertIn("sip_request_call_action", names)\n        self.assertIn("sip_pending_call_approvals", names)
        self.assertIn("sip_approve_call_action", names)
        self.assertIn("sip_execute_approved_action", names)

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
