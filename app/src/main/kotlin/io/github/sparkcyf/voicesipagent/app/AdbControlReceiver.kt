package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import io.github.sparkcyf.voicesipagent.voice.ApprovalState
import io.github.sparkcyf.voicesipagent.voice.CallAction
import io.github.sparkcyf.voicesipagent.voice.CallActionSpec
import io.github.sparkcyf.voicesipagent.voice.CallApproval
import io.github.sparkcyf.voicesipagent.voice.CallAuditEntry
import io.github.sparkcyf.voicesipagent.voice.ManagedCall

/**
 * adb-only control (protected by the DUMP permission). Call-control replies use ordered-broadcast
 * result data so an authorized local ADB client can bridge them to an MCP stdio process.
 */
class AdbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rt = GatewayRuntime.get(context)
        when (val op = intent.getStringExtra("op")) {
            "status" -> rt.statusReport().lines().filter { it.isNotBlank() }.forEach { AppLog.i("status: $it") }
            "setup" -> rt.runSetup()
            "sip-restart" -> rt.restartSip()
            "loopback" -> rt.setLoopbackTest(intent.getBooleanExtra("on", true))
            "takeover" -> rt.setTakeover(intent.getBooleanExtra("on", true))
            "earpiece-mute" -> rt.setMuteEarpiece(intent.getBooleanExtra("on", true))
            "sms-test" -> rt.forwardSms(intent.getStringExtra("from") ?: "+10000000000",
                intent.getStringExtra("text") ?: "test message from the gateway")
            "sip-debug" -> rt.setSipDebugLog(intent.getBooleanExtra("on", true))
            "sip-stack" -> {
                val stack = SipStack.of(intent.getStringExtra("stack"))
                if (stack == null) AppLog.w("adb control: sip-stack needs --es stack builtin|pjsua2")
                else rt.applySettings(rt.settings.copy(sipStack = stack))
            }
            "provision" -> {
                val cur = rt.settings
                rt.applySettings(
                    cur.copy(
                        server = intent.getStringExtra("server") ?: cur.server,
                        port = intent.getIntExtra("port", cur.port),
                        sipTransport = intent.getStringExtra("transport") ?: cur.sipTransport,
                        srtp = intent.getStringExtra("srtp") ?: cur.srtp,
                        username = intent.getStringExtra("user") ?: cur.username,
                        password = intent.getStringExtra("password") ?: cur.password,
                        domain = intent.getStringExtra("domain") ?: cur.domain,
                        inboundTarget = intent.getStringExtra("target") ?: cur.inboundTarget,
                        allowedPrefixes = intent.getStringExtra("prefixes") ?: cur.allowedPrefixes,
                        audioMethod = intent.getIntExtra("audio_method", cur.audioMethod),
                        callAccess = intent.getIntExtra("call_access", cur.callAccess),
                    ),
                )
            }
            "call-list", "call-pending", "call-request", "call-reject",
            "call-execute", "call-audit", "call-status" -> {
                val response = runCatching { handleCallControl(rt, intent, op) }
                    .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "invalid request") }
                setResultCode(-1)
                setResultData(response.toString())
            }
            else -> AppLog.w("adb control: unknown op '$op'")
        }
    }

    private fun handleCallControl(
        runtime: GatewayRuntime,
        intent: Intent,
        op: String?,
    ): JSONObject = when (op) {
        "call-list" -> JSONObject()
            .put("ok", true)
            .put("active", JSONArray(runtime.callControl.activeCalls().map(::callJson)))
            .put("history", JSONArray(runtime.callControl.callHistory().map(::callJson)))
        "call-pending" -> JSONObject().put("ok", true)
            .put("approvals", JSONArray(runtime.pendingCallApprovals.value.map(::approvalJson)))
        "call-request" -> {
            val action = CallAction.valueOf(intent.getStringExtra("action")
                ?.uppercase() ?: error("action is required"))
            val digit = intent.getStringExtra("digit")?.singleOrNull()
            val spec = CallActionSpec(
                action = action,
                callId = intent.getStringExtra("call_id"),
                target = intent.getStringExtra("target"),
                route = intent.getStringExtra("route") ?: "auto",
                dtmf = digit,
            )
            JSONObject().put("ok", true).put("approval", approvalJson(runtime.requestCallApproval(spec)))
        }
        "call-reject" -> {
            val id = intent.getStringExtra("approval_id") ?: error("approval_id is required")
            val approval = runtime.rejectCallApproval(id) ?: error("approval not found")
            JSONObject().put("ok", approval.state == ApprovalState.REJECTED)
                .put("approval", approvalJson(approval))
        }
        "call-execute" -> {
            val id = intent.getStringExtra("approval_id") ?: error("approval_id is required")
            val result = runtime.callControl.execute(id)
            JSONObject().put("ok", result.executed)
                .put("detail", result.detail)
                .put("call", result.call?.let(::callJson) ?: JSONObject.NULL)
        }
        "call-audit" -> {
            val limit = intent.getIntExtra("limit", 100).coerceIn(1, 500)
            JSONObject().put("ok", true)
                .put("entries", JSONArray(runtime.callControl.approvals.auditLog(limit).map(::auditJson)))
        }
        "call-status" -> JSONObject().put("ok", true).put("status", runtime.statusReport())
        else -> JSONObject().put("ok", false).put("error", "unsupported call-control operation")
    }

    private fun callJson(call: ManagedCall) = JSONObject()
        .put("id", call.id)
        .put("kind", call.kind)
        .put("direction", call.direction.name)
        .put("remote_number", call.remoteNumber ?: JSONObject.NULL)
        .put("display_name", call.displayName ?: JSONObject.NULL)
        .put("target", call.target ?: JSONObject.NULL)
        .put("state", call.state.name)
        .put("end_cause", call.endCause?.name ?: JSONObject.NULL)
        .put("observed_at", call.observedAt.toString())

    private fun approvalJson(approval: CallApproval) = JSONObject()
        .put("id", approval.id)
        .put("action", approval.spec.action.name)
        .put("call_id", approval.spec.callId ?: JSONObject.NULL)
        .put("target", approval.spec.target ?: JSONObject.NULL)
        .put("route", approval.spec.route)
        .put("digit", approval.spec.dtmf?.toString() ?: JSONObject.NULL)
        .put("state", approval.state.name)
        .put("created_at", approval.createdAt.toString())
        .put("expires_at", approval.expiresAt.toString())
        .put("decided_at", approval.decidedAt?.toString() ?: JSONObject.NULL)

    private fun auditJson(entry: CallAuditEntry) = JSONObject()
        .put("approval_id", entry.approvalId)
        .put("action", entry.action.name)
        .put("outcome", entry.outcome.name)
        .put("timestamp", entry.timestamp.toString())
        .put("detail", entry.detail)
}
