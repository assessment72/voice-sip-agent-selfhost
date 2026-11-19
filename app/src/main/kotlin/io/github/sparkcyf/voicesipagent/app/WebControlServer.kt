package io.github.sparkcyf.voicesipagent.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small, dependency-free HTTP dashboard for the gateway phone.
 * It intentionally uses a separate port from SIP (5062) and RTP.
 * The HTTP Basic password is the existing SIP Registrar password; keep this port on HTTPS/VPN.
 */
class WebControlServer(private val runtime: GatewayRuntime, private val employeeStore: ClinicEmployeeStore) {
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "web-control-client").apply { isDaemon = true }
    }

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({
            try {
                ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0")).use { server ->
                    serverSocket = server
                    AppLog.i("web control listening on 0.0.0.0:$PORT")
                    while (running) {
                        try {
                            clients.submit { handle(server.accept()) }
                        } catch (_: SocketException) {
                            if (running) AppLog.w("web control accept failed")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) AppLog.w("web control failed to start", e)
            } finally {
                serverSocket = null
            }
        }, "web-control-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.shutdownNow()
        acceptThread = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val query = parseQuery(rawTarget.substringAfter('?', ""))
            if (!authorized(headers["authorization"])) {
                respond(client.getOutputStream(), 401, "Authentication required", "text/plain; charset=utf-8",
                    "WWW-Authenticate: Basic realm=\"Voice SIP Agent\"")
                return
            }
            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(client.getOutputStream(), 200, dashboardHtml(), "text/html; charset=utf-8")
                method == "GET" && path == "/api/status" ->
                    respond(client.getOutputStream(), 200, statusJson(), "application/json; charset=utf-8")
                method == "GET" && path == "/api/logs" ->
                    respond(client.getOutputStream(), 200, logsJson(), "application/json; charset=utf-8")
                method == "GET" && path == "/api/employees" ->
                    respond(client.getOutputStream(), 200, employeesJson(), "application/json; charset=utf-8")
                method == "POST" && path == "/api/employees" -> addEmployee(query, client)
                method == "DELETE" && path == "/api/employees" -> deleteEmployee(query, client)
                method == "POST" && path == "/api/takeover" -> changeBoolean(query, client, "on", runtime::setTakeover)
                method == "POST" && path == "/api/debug-log" -> changeBoolean(query, client, "on", runtime::setSipDebugLog)
                method == "POST" && path == "/api/mute-earpiece" -> changeBoolean(query, client, "on", runtime::setMuteEarpiece)
                method == "POST" && path == "/api/keep-dialer" -> changeBoolean(query, client, "on", runtime::setKeepDefaultDialer)
                method == "POST" && path == "/api/restart-sip" -> {
                    runtime.restartSip()
                    respond(client.getOutputStream(), 202, "{\"accepted\":true}", "application/json")
                }
                else -> respond(client.getOutputStream(), 404, "Not found", "text/plain; charset=utf-8")
            }
        }
    }

    private fun changeBoolean(query: Map<String, String>, client: Socket, key: String, action: (Boolean) -> Unit) {
        val on = query[key]?.toBooleanStrictOrNull()
        if (on == null) respond(client.getOutputStream(), 400, "{\"error\":\"on must be true or false\"}", "application/json")
        else {
            action(on)
            respond(client.getOutputStream(), 202, "{\"accepted\":true}", "application/json")
        }
    }

    private fun authorized(header: String?): Boolean {
        val password = runtime.settings.registrarPassword
        if (password.isEmpty() || header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(header.substringAfter(' ')), StandardCharsets.UTF_8)
        }.getOrNull() ?: return false
        val separator = decoded.indexOf(':')
        return separator > 0 && decoded.substring(0, separator) == "admin" && decoded.substring(separator + 1) == password
    }

    private fun statusJson(): String {
        val h = runtime.health.value
        val n = runtime.networkMetrics.value
        val s = runtime.settings
        return "{" +
            "\"takeover\":${h.takeover},\"takeoverPending\":${h.takeoverOnPending || h.takeoverOffPending}," +
            "\"sipReady\":${h.sip.ready},\"sipDetail\":\"${jsonEscape(h.sip.detail)}\"," +
            "\"serverMode\":${s.serverMode},\"sipPort\":${s.localSipPort}," +
            "\"internetConnected\":${n.internetConnected},\"internetTransport\":\"${jsonEscape(n.internetTransport)}\"," +
            "\"signalLevel\":${n.sim2SignalLevel},\"signalDbm\":${n.sim2Dbm ?: 0}," +
            "\"simNetwork\":\"${jsonEscape(n.sim2Network)}\",\"downloadKbps\":${n.downstreamKbps},\"uploadKbps\":${n.upstreamKbps}," +
            "\"muteEarpiece\":${s.muteEarpiece},\"debugLog\":${s.sipDebugLog},\"keepDialer\":${s.keepDefaultDialer}," +
            "\"status\":\"${jsonEscape(runtime.statusReport())}\"}"
    }

    private fun logsJson(): String = "[${AppLog.tail.value.joinToString(",") { "\"${jsonEscape(it)}\"" }}]"

    private fun employeesJson(): String = "[${employeeStore.list().joinToString(",") { e ->
        val target = when (val d = e.destination) {
            is io.github.sparkcyf.voicesipagent.gateway.EmployeeDestination.Sip -> "sip"
            is io.github.sparkcyf.voicesipagent.gateway.EmployeeDestination.Mobile -> "mobile"
        }
        val value = when (val d = e.destination) {
            is io.github.sparkcyf.voicesipagent.gateway.EmployeeDestination.Sip -> d.uri
            is io.github.sparkcyf.voicesipagent.gateway.EmployeeDestination.Mobile -> d.e164
        }
        "{\"id\":\"${jsonEscape(e.id)}\",\"name\":\"${jsonEscape(e.displayName)}\",\"kind\":\"$target\",\"destination\":\"${jsonEscape(value)}\",\"priority\":${e.priority},\"enabled\":${e.enabled}}"
    }}]"

    private fun addEmployee(query: Map<String, String>, client: Socket) {
        val name = query["name"].orEmpty()
        val kind = query["kind"].orEmpty()
        val destination = query["destination"].orEmpty()
        val priority = query["priority"]?.toIntOrNull() ?: 0
        val employee = runCatching { employeeStore.upsert(name, kind, destination, priority) }.getOrNull()
        if (employee == null) respond(client.getOutputStream(), 400, "{\"error\":\"invalid employee data\"}", "application/json")
        else respond(client.getOutputStream(), 201, "{\"accepted\":true,\"id\":\"${jsonEscape(employee.id)}\"}", "application/json")
    }

    private fun deleteEmployee(query: Map<String, String>, client: Socket) {
        val deleted = query["id"]?.let(employeeStore::delete) == true
        respond(client.getOutputStream(), if (deleted) 200 else 404, "{\"deleted\":$deleted}", "application/json")
    }

    private fun respond(output: OutputStream, code: Int, body: String, contentType: String, extraHeader: String? = null) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        val extra = if (extraHeader == null) "" else "$extraHeader\r\n"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').mapNotNull { item ->
        if (item.isBlank()) return@mapNotNull null
        val pair = item.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }.toMap()

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c) }
        }
    }

    private fun dashboardHtml() = """<!doctype html>
<html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Voice SIP Agent — التحكم</title>
<style>body{font-family:system-ui,sans-serif;max-width:900px;margin:24px auto;padding:0 14px;background:#f6f7fb;color:#172033}main{background:white;border-radius:16px;padding:22px;box-shadow:0 4px 18px #0001}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px}.card{border:1px solid #eaecf0;border-radius:12px;padding:14px}.label{color:#667085;font-size:13px}.value{font-size:20px;font-weight:700;margin-top:4px}button{border:0;border-radius:10px;padding:10px 14px;margin:4px;background:#1769e0;color:white;font-size:14px}button.off{background:#b42318}button.gray{background:#475467}pre{white-space:pre-wrap;background:#101828;color:#d0d5dd;border-radius:10px;padding:14px;direction:ltr;text-align:left;overflow:auto;max-height:320px}.muted{color:#667085}.ok{color:#067647;font-weight:600}.bad{color:#b42318;font-weight:600}.row{display:flex;flex-wrap:wrap;align-items:center;gap:5px;margin:8px 0}</style>
<main><h1>لوحة تحكم Voice SIP Agent</h1><p class="muted">المنفذ 8787 — اسم المستخدم <b>admin</b> والرمز هو Registrar password.</p><div class="grid"><div class="card"><div class="label">SIP</div><div id="sip" class="value">—</div></div><div class="card"><div class="label">Gateway</div><div id="gateway" class="value">—</div></div><div class="card"><div class="label">الإنترنت</div><div id="internet" class="value">—</div></div><div class="card"><div class="label">الشبكة الخلوية</div><div id="radio" class="value">—</div></div></div><h2>التحكم</h2><div class="row"><button onclick="takeover(true)">تشغيل Gateway</button><button class="off" onclick="takeover(false)">إيقاف Gateway</button><button onclick="restartSip()">إعادة تشغيل SIP</button><button class="gray" onclick="loadAll()">تحديث الآن</button><button class="gray" onclick="copyStatus()">نسخ الحالة</button></div><h2>الإعدادات السريعة</h2><div class="row"><button onclick="toggle('/api/debug-log',true)">تشغيل سجل SIP</button><button class="gray" onclick="toggle('/api/debug-log',false)">إيقاف سجل SIP</button><button onclick="toggle('/api/mute-earpiece',true)">كتم سماعة الهاتف</button><button class="gray" onclick="toggle('/api/mute-earpiece',false)">إلغاء كتم السماعة</button><button onclick="toggle('/api/keep-dialer',true)">إبقاء دور الهاتف</button><button class="gray" onclick="toggle('/api/keep-dialer',false)">عدم إبقاء الدور</button></div><h2>موظفو العيادة</h2><div class="row"><input id="empName" placeholder="اسم الموظف"><select id="empKind"><option value="sip">SIP</option><option value="mobile">جوال</option></select><input id="empDest" placeholder="sip:agent@host أو +9665..."><input id="empPriority" type="number" value="0" min="0" placeholder="الأولوية"><button onclick="addEmployee()">إضافة موظف</button></div><pre id="employees">جارٍ التحميل…</pre><h2>التفاصيل</h2><pre id="details">جارٍ التحميل…</pre><h2>آخر سجل التطبيق</h2><pre id="logs">جارٍ التحميل…</pre><p class="muted">آخر تحديث: <span id="updated">—</span>. لا تعرض المنفذ على الإنترنت دون HTTPS أو VPN.</p></main>
<script>let last='';async function api(path,opt){let r=await fetch(path,opt);if(!r.ok)throw Error(await r.text());return r.json()}function val(id,text,good){let e=document.getElementById(id);e.textContent=text;e.className='value '+(good===true?'ok':good===false?'bad':'')}async function loadEmployees(){let a=await api('/api/employees');document.getElementById('employees').innerHTML=a.length?a.map(e=>e.name+' — '+e.kind+' — '+e.destination+' <button class=\"off\" onclick=\"removeEmployee(\''+e.id+'\')\">حذف</button>').join('\n'):'لا يوجد موظفون'}async function addEmployee(){let q=new URLSearchParams({name:document.getElementById('empName').value,kind:document.getElementById('empKind').value,destination:document.getElementById('empDest').value,priority:document.getElementById('empPriority').value});await api('/api/employees?'+q,{method:'POST'});document.getElementById('empName').value='';document.getElementById('empDest').value='';loadEmployees()}async function removeEmployee(id){if(confirm('حذف الموظف؟')){await api('/api/employees?id='+encodeURIComponent(id),{method:'DELETE'});loadEmployees()}}async function loadAll(){try{let s=await api('/api/status');last=s.status;val('sip',s.sipReady?'جاهز':'غير جاهز',s.sipReady);val('gateway',s.takeover?(s.takeoverPending?'قيد التغيير':'يعمل'):'متوقف',s.takeover);val('internet',s.internetConnected?s.internetTransport:'منقطع',s.internetConnected);val('radio',s.simNetwork+' / '+s.signalLevel+'/4',s.signalLevel>1);document.getElementById('details').textContent=s.status;let l=await api('/api/logs');document.getElementById('logs').textContent=l.join('\n')||'لا يوجد سجل';document.getElementById('updated').textContent=new Date().toLocaleTimeString();await loadEmployees()}catch(e){document.getElementById('details').textContent='فشل تحميل الحالة: '+e}}async function takeover(on){await api('/api/takeover?on='+on,{method:'POST'});setTimeout(loadAll,500)}async function toggle(path,on){await api(path+'?on='+on,{method:'POST'});setTimeout(loadAll,500)}async function restartSip(){await api('/api/restart-sip',{method:'POST'});setTimeout(loadAll,700)}async function copyStatus(){await navigator.clipboard.writeText(last);alert('تم نسخ الحالة')}loadAll();setInterval(loadAll,5000)</script>
</html>"""

    companion object { const val PORT = 8787 }
}
