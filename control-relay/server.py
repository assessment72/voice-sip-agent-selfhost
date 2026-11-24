"""Local Telegram control relay for Voicx SIP devices.

The relay owns the single Telegram getUpdates poller. Devices initiate outbound
WebSocket connections to this relay; Telegram never connects directly to phones.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Any

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect

load_dotenv()

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("voicx-relay")

BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
ADMIN_IDS = {int(x) for x in os.getenv("TELEGRAM_ADMIN_IDS", "").split(",") if x.strip().isdigit()}
DEVICE_TOKENS: dict[str, str] = json.loads(os.getenv("VOICX_DEVICES_JSON", "{}"))
if not isinstance(DEVICE_TOKENS, dict) or len(DEVICE_TOKENS) > 20:
    raise RuntimeError("VOICX_DEVICES_JSON must be an object with at most 20 devices")
for _device_id, _token in DEVICE_TOKENS.items():
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", _device_id):
        raise RuntimeError("Device IDs must be 1-32 characters: letters, digits, _ or -")
    if not isinstance(_token, str) or len(_token) < 32:
        raise RuntimeError("Each device token must contain at least 32 characters")
BIND = os.getenv("RELAY_BIND", "0.0.0.0")
PORT = int(os.getenv("RELAY_PORT", "8765"))
TELEGRAM_API = f"https://api.telegram.org/bot{BOT_TOKEN}" if BOT_TOKEN else ""

@dataclass
class Device:
    device_id: str
    socket: WebSocket
    connected_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    status: dict[str, Any] = field(default_factory=dict)
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

devices: dict[str, Device] = {}
pending: dict[str, tuple[int, str, float]] = {}
confirmations: dict[str, tuple[int, str, str, float]] = {}
http: httpx.AsyncClient | None = None
poll_task: asyncio.Task | None = None
offset = 0

async def tg(method: str, payload: dict[str, Any]) -> dict[str, Any]:
    if not TELEGRAM_API or http is None:
        raise RuntimeError("Telegram bot is not configured")
    response = await http.post(f"{TELEGRAM_API}/{method}", json=payload, timeout=40)
    response.raise_for_status()
    data = response.json()
    if not data.get("ok"):
        raise RuntimeError(f"Telegram API {method} failed")
    return data["result"]

async def notify_admins(message: str, reply_markup: dict | None = None) -> None:
    for admin_id in ADMIN_IDS:
        try:
            payload: dict[str, Any] = {"chat_id": admin_id, "text": message}
            if reply_markup:
                payload["reply_markup"] = reply_markup
            await tg("sendMessage", payload)
        except Exception:
            log.exception("Telegram notification failed")

def authorized_admin(update: dict[str, Any]) -> int | None:
    message = update.get("message") or update.get("callback_query", {}).get("message") or {}
    sender = (update.get("message", {}).get("from") or update.get("callback_query", {}).get("from") or {})
    user_id = sender.get("id")
    chat_id = (message.get("chat") or {}).get("id")
    if user_id in ADMIN_IDS and chat_id == user_id:
        return user_id
    return None

async def command_device(device_id: str, action: str, admin_id: int) -> str:
    device = devices.get(device_id)
    if device is None:
        return f"الجهاز {device_id} غير متصل حالياً."
    command_id = str(uuid.uuid4())
    allowed = {"status", "gateway_start", "gateway_stop"}
    if action not in allowed:
        return "الأمر غير مسموح."
    packet = {"type": "command", "id": command_id, "action": action, "issued_at": int(time.time())}
    async with device.send_lock:
        await device.socket.send_text(json.dumps(packet))
    pending[command_id] = (admin_id, device_id, time.time())
    return f"تم إرسال أمر {action} إلى {device_id}. رقم المتابعة: {command_id[:8]}"

async def handle_update(update: dict[str, Any]) -> None:
    admin_id = authorized_admin(update)
    if admin_id is None:
        # Ignore unauthorized users and groups; never disclose device inventory.
        return
    callback = update.get("callback_query")
    if callback:
        data = callback.get("data", "")
        await tg("answerCallbackQuery", {"callback_query_id": callback["id"]})
        parts = data.split(":")
        if len(parts) == 2 and parts[0] == "confirm":
            confirmation = confirmations.pop(parts[1], None)
            if confirmation and confirmation[0] == admin_id and confirmation[3] >= time.time():
                _, device_id, action, _ = confirmation
                result = await command_device(device_id, action, admin_id)
                await tg("sendMessage", {"chat_id": admin_id, "text": result})
            else:
                await tg("sendMessage", {"chat_id": admin_id, "text": "انتهت صلاحية التأكيد أو سبق استخدامه."})
        elif len(parts) == 2 and parts[0] == "status":
            result = await command_device(parts[1], "status", admin_id)
            await tg("sendMessage", {"chat_id": admin_id, "text": result})
        elif len(parts) == 2 and parts[0] == "cancel":
            confirmations.pop(parts[1], None)
            await tg("sendMessage", {"chat_id": admin_id, "text": "تم إلغاء الأمر."})
        return
    message = update.get("message", {})
    text = (message.get("text") or "").strip()
    if text == "/start":
        await tg("sendMessage", {"chat_id": admin_id, "text":
            "Voicx SIP Control متصل. استخدم /devices لعرض الأجهزة، ثم /status DEVICE أو /start_device DEVICE أو /stop_device DEVICE."})
    elif text == "/devices":
        if not devices:
            await tg("sendMessage", {"chat_id": admin_id, "text": "لا توجد أجهزة متصلة الآن."})
            return
        rows = []
        for device_id in sorted(DEVICE_TOKENS):
            device = devices.get(device_id)
            if device is None:
                rows.append([{"text": f"{device_id} · غير متصل", "callback_data": f"status:{device_id}"}])
            else:
                age = int(time.time() - device.last_seen)
                rows.append([{"text": f"{device_id} · متصل · آخر تحديث {age}s", "callback_data": f"status:{device_id}"}])
        await tg("sendMessage", {"chat_id": admin_id, "text": "الأجهزة المسجلة والمتصلة:", "reply_markup": {"inline_keyboard": rows}})
    elif text.startswith(("/status ", "/start_device ", "/stop_device ")):
        cmd, _, device_id = text.partition(" ")
        action = {"/status": "status", "/start_device": "gateway_start", "/stop_device": "gateway_stop"}[cmd]
        if not device_id or device_id not in DEVICE_TOKENS:
            await tg("sendMessage", {"chat_id": admin_id, "text": "معرّف الجهاز غير معروف."})
            return
        if action == "status":
            result = await command_device(device_id, action, admin_id)
            await tg("sendMessage", {"chat_id": admin_id, "text": result})
        else:
            verb = "تشغيل" if action == "gateway_start" else "إيقاف"
            nonce = secrets.token_urlsafe(6)
            confirmations[nonce] = (admin_id, device_id, action, time.time() + 60)
            await tg("sendMessage", {"chat_id": admin_id,
                "text": f"تأكيد {verb} خدمة البوابة على الجهاز {device_id}؟ صلاحية التأكيد 60 ثانية ويُستخدم مرة واحدة.",
                "reply_markup": {"inline_keyboard": [[
                    {"text": "تأكيد", "callback_data": f"confirm:{nonce}"},
                    {"text": "إلغاء", "callback_data": f"cancel:{nonce}"}
                ]]}})
    else:
        await tg("sendMessage", {"chat_id": admin_id, "text": "أمر غير معروف. الأوامر: /devices أو /status DEVICE أو /start_device DEVICE أو /stop_device DEVICE."})

async def telegram_poll() -> None:
    global offset
    while True:
        try:
            updates = await tg("getUpdates", {"offset": offset, "timeout": 30, "allowed_updates": ["message", "callback_query"]})
            for update in updates:
                offset = max(offset, int(update["update_id"]) + 1)
                await handle_update(update)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Telegram polling error")
            await asyncio.sleep(3)

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http, poll_task
    http = httpx.AsyncClient()
    if BOT_TOKEN and ADMIN_IDS:
        poll_task = asyncio.create_task(telegram_poll())
        log.info("Telegram polling started for %d admin IDs", len(ADMIN_IDS))
    else:
        log.warning("Telegram is not configured; device WebSockets can still connect")
    yield
    if poll_task:
        poll_task.cancel()
        try:
            await poll_task
        except asyncio.CancelledError:
            pass
    if http:
        await http.aclose()

app = FastAPI(title="Voicx SIP Local Control Relay", version="0.1.0", lifespan=lifespan)

@app.get("/health")
async def health():
    return {"ok": True, "connected_devices": len(devices), "telegram_configured": bool(BOT_TOKEN and ADMIN_IDS)}

@app.websocket("/ws/device/{device_id}")
async def device_socket(websocket: WebSocket, device_id: str, authorization: str | None = Header(default=None)):
    expected = DEVICE_TOKENS.get(device_id)
    supplied = authorization.removeprefix("Bearer ").strip() if authorization else ""
    if not expected or not secrets.compare_digest(supplied, expected):
        await websocket.close(code=1008, reason="unauthorized")
        return
    await websocket.accept()
    current = Device(device_id=device_id, socket=websocket)
    previous = devices.get(device_id)
    devices[device_id] = current
    if previous:
        try:
            await previous.socket.close(code=4001, reason="replaced by newer connection")
        except Exception:
            pass
    await notify_admins(f"اتصل الجهاز {device_id} بوسيط Voicx المحلي.")
    try:
        while True:
            raw = await websocket.receive_text()
            if len(raw) > 16_384:
                await websocket.close(code=1009, reason="message too large")
                break
            packet = json.loads(raw)
            if packet.get("type") == "heartbeat":
                current.last_seen = time.time()
                current.status = packet.get("status", {}) if isinstance(packet.get("status"), dict) else {}
            elif packet.get("type") == "result":
                current.last_seen = time.time()
                command_id = str(packet.get("id", ""))
                target = pending.pop(command_id, None)
                if target:
                    admin_id, target_device, _ = target
                    if target_device == device_id:
                        summary = json.dumps(packet.get("result", {}), ensure_ascii=False)[:3000]
                        await tg("sendMessage", {"chat_id": admin_id, "text": f"نتيجة {device_id}: {summary}"})
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("Device socket error: %s", device_id)
    finally:
        if devices.get(device_id) is current:
            devices.pop(device_id, None)
        await notify_admins(f"انقطع اتصال الجهاز {device_id} بوسيط Voicx المحلي.")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host=BIND, port=PORT, reload=False)
