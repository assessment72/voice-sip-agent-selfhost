export const dashboardHtml = String.raw`<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#101827"><title>مركز إدارة بوابات SIP</title>
<style>
:root{color-scheme:dark;--bg:#0b1220;--panel:#121d2e;--line:#26364d;--muted:#9aabc2;--text:#eef4ff;--green:#35c98b;--red:#ff7272;--blue:#79aaff}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 80% 0%,#1a2b45 0,transparent 42%),var(--bg);color:var(--text);font:15px system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1100px;margin:auto;padding:26px 18px 60px}.top{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}
h1{font-size:25px;margin:0 0 6px}.sub,.muted{color:var(--muted)}.sub{margin:0}
button,input{font:inherit;border-radius:10px;border:1px solid var(--line);padding:11px 14px;background:#0e1929;color:var(--text)}
button{cursor:pointer;background:#244d86;border-color:#315f9e}button.secondary{background:#18263a}button:disabled{opacity:.5;cursor:wait}
.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:24px 0}
.stat,.device,.login{background:linear-gradient(145deg,#142238,#101a2a);border:1px solid var(--line);border-radius:16px;padding:17px}
.stat span{display:block;color:var(--muted);font-size:13px}.stat strong{display:block;font-size:28px;margin-top:6px;font-variant-numeric:tabular-nums}
.section{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:26px 0 13px}.section h2{font-size:18px;margin:0}
.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px}
.device h3{font-size:17px;margin:0}.device-head{display:flex;justify-content:space-between;gap:12px;align-items:center}
.pill{font-size:12px;padding:5px 9px;border-radius:30px;background:#26364d;color:#c5d4e8;white-space:nowrap}.pill.on{background:#123d32;color:#7ee0b7}.pill.off{background:#49262c;color:#ffaaaa}
.meta{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:16px}.meta div{background:#0c1726;border-radius:10px;padding:10px;min-width:0}.meta small{display:block;color:var(--muted);margin-bottom:4px}.meta b{font-size:14px;overflow-wrap:anywhere}
.empty,.error{padding:22px;border:1px dashed var(--line);border-radius:14px;color:var(--muted);text-align:center}.error{color:#ffaaaa;border-color:#75434a}
.login{max-width:470px;margin:70px auto}.login h2{margin-top:0}.login input{width:100%;margin:12px 0}.login button{width:100%}
table{width:100%;border-collapse:collapse;background:#111d2d;border-radius:12px;overflow:hidden}th,td{text-align:right;padding:12px;border-bottom:1px solid var(--line);font-size:13px}th{color:#b5c5da;background:#18263a}td{color:#dce7f7}.table-wrap{overflow:auto}
footer{margin-top:30px;color:var(--muted);font-size:12px}
@media(max-width:700px){main{padding:20px 13px 45px}.stats{gap:8px}.stat{padding:12px}.stat strong{font-size:23px}.grid{grid-template-columns:1fr}.meta{gap:8px}h1{font-size:21px}.section{margin-top:22px}}
@media(max-width:390px){.stats{grid-template-columns:1fr}.stat strong{font-size:22px}}
</style></head>
<body><main>
<header class="top"><div><h1>مركز إدارة بوابات SIP</h1><p class="sub">مراقبة الأجهزة والاتصال المركزي</p></div><div><button id="refresh" class="secondary">تحديث الآن</button> <button id="logout" class="secondary">خروج</button></div></header>
<section id="login" class="login" hidden><h2>دخول المدير</h2><p class="muted">أدخل رمز المدير للوصول إلى بيانات الأجهزة. لا تستخدم رمز جهاز Android هنا.</p><form id="loginForm"><input id="token" type="password" autocomplete="current-password" placeholder="رمز المدير MCP_ADMIN_TOKEN" required><button>دخول آمن</button></form><p id="loginError" class="muted"></p></section>
<section id="app" hidden>
<div class="stats"><div class="stat"><span>إجمالي البوابات</span><strong id="total">—</strong></div><div class="stat"><span>متصلة الآن</span><strong id="online">—</strong></div><div class="stat"><span>غير متصلة</span><strong id="offline">—</strong></div></div>
<div class="section"><h2>الأجهزة المسجلة</h2><span id="updated" class="muted">—</span></div>
<div id="devices" class="grid"><div class="empty">جارٍ تحميل الأجهزة...</div></div>
<div class="section"><h2>سجل أوامر الإدارة</h2><select id="auditDevice" style="max-width:220px"><option value="">اختر بوابة</option></select></div>
<div id="audit" class="table-wrap"><div class="empty">اختر بوابة لعرض السجل المتاح</div></div>
</section><footer>الاتصال والأوامر تُدار عبر الخادم المركزي. هذه النسخة للقراءة والمراقبة فقط؛ لا تنفّذ مكالمات أو تغيّر إعدادات الهاتف.</footer>
</main>
<script>
const $=id=>document.getElementById(id);let token=sessionStorage.getItem("central_admin_token")||"";let gateways=[];
const esc=value=>String(value??"—").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const date=value=>value?new Date(value).toLocaleString("ar",{dateStyle:"medium",timeStyle:"short"}):"—";
function showLogin(message=""){ $("login").hidden=false;$("app").hidden=true;$("loginError").textContent=message; }
function showApp(){ $("login").hidden=true;$("app").hidden=false; }
async function api(path){const r=await fetch(path,{headers:{Authorization:"Bearer "+token},cache:"no-store"});if(r.status===401)throw Error("unauthorized");if(!r.ok)throw Error("تعذر تحميل البيانات ("+r.status+")");return r.json();}
function render(){const online=gateways.filter(g=>g.connected).length;$("total").textContent=gateways.length;$("online").textContent=online;$("offline").textContent=gateways.length-online;
$("devices").innerHTML=gateways.length?gateways.map(g=>{const m=g.metadata||{};return `<article class="device"><div class="device-head"><h3>\${esc(g.gateway_id)}</h3><span class="pill \${g.connected?"on":"off"}">\${g.connected?"متصل":"غير متصل"}</span></div><div class="meta">
<div><small>آخر اتصال / نبضة</small><b>\${esc(date(g.last_seen))}</b></div><div><small>الشركة والطراز</small><b>\${esc([m.manufacturer,m.model].filter(Boolean).join(" ")||"غير معروف")}</b></div>
<div><small>جاهزية SIP</small><b>\${m.sip_ready===true?"جاهز":m.sip_ready===false?"غير جاهز":"غير محدد"}</b></div><div><small>التحكم / الاستلام</small><b>\${m.takeover===true?"مفعّل":m.takeover===false?"متوقف":"غير محدد"}</b></div>
<div><small>المكالمة الحالية</small><b>\${m.active_call===true?"يوجد اتصال":m.active_call===false?"لا يوجد":"غير محدد"}</b></div><div><small>إصدار التطبيق / أندرويد</small><b>\${esc([m.app_version,m.android_release].filter(Boolean).join(" / ")||"—")}</b></div>
</div>\${m.sip_detail?'<p class="muted">'+esc(m.sip_detail)+'</p>':""}</article>`;}).join(""):'<div class="empty">لا توجد بوابات مسجلة حتى الآن.</div>';
const selected=$("auditDevice").value;$("auditDevice").innerHTML='<option value="">اختر بوابة</option>'+gateways.map(g=>'<option value="'+esc(g.gateway_id)+'">'+esc(g.gateway_id)+'</option>').join("");if(selected&&gateways.some(g=>g.gateway_id===selected))$("auditDevice").value=selected;
}
async function load(){ $("refresh").disabled=true;try{const data=await api("/admin/gateways");gateways=data.gateways||[];render();$("updated").textContent="آخر تحديث: "+new Date().toLocaleTimeString("ar");}catch(e){if(e.message==="unauthorized"){sessionStorage.removeItem("central_admin_token");token="";showLogin("رمز المدير غير صحيح أو انتهت صلاحيته.");}else $("devices").innerHTML='<div class="error">'+esc(e.message)+'</div>';}finally{$("refresh").disabled=false;}}
async function loadAudit(){const id=$("auditDevice").value;if(!id){$("audit").innerHTML='<div class="empty">اختر بوابة لعرض السجل</div>';return;}try{const d=await api("/admin/audit?gateway_id="+encodeURIComponent(id));const rows=d.records||[];$("audit").innerHTML=rows.length?'<table><thead><tr><th>الوقت</th><th>الحدث</th><th>اسم الأمر</th><th>معرّف الأمر</th></tr></thead><tbody>'+rows.map(r=>'<tr><td>'+esc(date(r.created_at))+'</td><td>'+esc(r.event_type)+'</td><td>'+esc(r.command_name)+'</td><td>'+esc(r.command_id)+'</td></tr>').join("")+'</tbody></table>':'<div class="empty">لا توجد أحداث محفوظة لهذه البوابة.</div>';}catch(e){$("audit").innerHTML='<div class="error">'+esc(e.message)+'</div>';}}
$("loginForm").addEventListener("submit",async e=>{e.preventDefault();token=$("token").value.trim();if(!token)return;sessionStorage.setItem("central_admin_token",token);showApp();await load();});
$("refresh").addEventListener("click",load);$("logout").addEventListener("click",()=>{sessionStorage.removeItem("central_admin_token");token="";showLogin();});
$("auditDevice").addEventListener("change",loadAudit);
if(token){showApp();load();}else showLogin();setInterval(()=>{if(token&&!$("app").hidden)load();},15000);
</script></body></html>`;
