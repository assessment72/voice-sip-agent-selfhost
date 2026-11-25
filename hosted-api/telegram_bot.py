from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import sqlite3
import time
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from fastapi import APIRouter, Header, HTTPException, Request as FastAPIRequest

router = APIRouter()
ACTION_COMMANDS = {"/whatsapp": "whatsapp", "/sms": "sms", "/call": "call", "/publish": "publish"}
PHONE_RE = re.compile(r"^\+[1-9][0-9]{7,14}$")


def _allowed_user(user_id: int) -> bool:
    allowed = {item.strip() for item in os.getenv("TELEGRAM_ALLOWED_USER_IDS", "").split(",") if item.strip()}
    return bool(allowed) and str(user_id) in allowed


def _secret_ok(supplied: str | None) -> bool:
    expected = os.getenv("TELEGRAM_WEBHOOK_SECRET", "").strip()
    return bool(expected and supplied and hmac.compare_digest(expected, supplied))


def _telegram_api(method: str, payload: dict) -> dict:
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        raise RuntimeError("telegram_bot_not_configured")
    request = Request(
        f"https://api.telegram.org/bot{token}/{method}",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=8) as response:
            result = json.loads(response.read(128_000).decode("utf-8"))
    except (HTTPError, URLError, TimeoutError, OSError, ValueError):
        raise RuntimeError("telegram_api_unavailable") from None
    if not isinstance(result, dict) or not result.get("ok"):
        raise RuntimeError("telegram_api_rejected_request")
    return result


def _send(chat_id: int, text: str, keyboard: list | None = None) -> None:
    payload = {"chat_id": chat_id, "text": text[:3900], "disable_web_page_preview": True}
    if keyboard is not None:
        payload["reply_markup"] = {"inline_keyboard": keyboard}
    _telegram_api("sendMessage", payload)


def _answer_callback(callback_id: str, text: str) -> None:
    _telegram_api("answerCallbackQuery", {"callback_query_id": callback_id, "text": text[:180]})


def _storage():
    if os.getenv("MONGODB_URI", "").strip() or (
        os.getenv("VERCEL_ENV", "").lower() != "production"
        and os.getenv("MONGODB_TEST_URI", "").strip()
    ):
        from mongodb_store import collection
        return "mongo", collection("telegram_pending_actions"), collection("telegram_updates")
    path = os.getenv("DATABASE_PATH", "./calls.sqlite3")
    connection = sqlite3.connect(path, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("""CREATE TABLE IF NOT EXISTS telegram_pending_actions (
        id TEXT PRIMARY KEY, telegram_user_id TEXT NOT NULL, chat_id TEXT NOT NULL,
        action_type TEXT NOT NULL, target_masked TEXT, payload_hash TEXT NOT NULL,
        status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
    )""")
    connection.execute("""CREATE TABLE IF NOT EXISTS telegram_updates (
        update_id INTEGER PRIMARY KEY, received_at INTEGER NOT NULL
    )""")
    connection.commit()
    return "sqlite", connection, None


def _claim_update(update_id: int) -> bool:
    kind, store, updates = _storage()
    now = int(time.time())
    if kind == "mongo":
        try:
            updates.insert_one({"_id": str(update_id), "received_at": now})
            return True
        except Exception:
            return False
    try:
        store.execute("INSERT INTO telegram_updates(update_id,received_at) VALUES(?,?)", (update_id, now))
        store.commit()
        return True
    except sqlite3.IntegrityError:
        return False
    finally:
        store.close()


def _create_pending(user_id: int, chat_id: int, action_type: str, target: str, fingerprint: str) -> str:
    action_id = uuid.uuid4().hex
    now = int(time.time())
    kind, store, _ = _storage()
    doc = {
        "_id": action_id, "telegram_user_id": str(user_id), "chat_id": str(chat_id),
        "action_type": action_type, "target_masked": target,
        "payload_hash": fingerprint, "status": "pending_confirmation",
        "created_at": now, "updated_at": now,
    }
    if kind == "mongo":
        store.create_index("created_at", expireAfterSeconds=7 * 24 * 3600)
        store.insert_one(doc)
    else:
        try:
            store.execute(
                """INSERT INTO telegram_pending_actions
                (id,telegram_user_id,chat_id,action_type,target_masked,payload_hash,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                (action_id, str(user_id), str(chat_id), action_type, target, fingerprint,
                 "pending_confirmation", now, now),
            )
            store.commit()
        finally:
            store.close()
    return action_id


def _resolve_pending(action_id: str, user_id: int, approved: bool) -> bool:
    now = int(time.time())
    kind, store, _ = _storage()
    new_status = "approved_not_executed" if approved else "cancelled"
    if kind == "mongo":
        result = store.update_one(
            {"_id": action_id, "telegram_user_id": str(user_id), "status": "pending_confirmation"},
            {"$set": {"status": new_status, "updated_at": now}},
        )
        return result.modified_count == 1
    try:
        cursor = store.execute(
            """UPDATE telegram_pending_actions SET status=?,updated_at=?
               WHERE id=? AND telegram_user_id=? AND status='pending_confirmation'""",
            (new_status, now, action_id, str(user_id)),
        )
        store.commit()
        return cursor.rowcount == 1
    finally:
        store.close()


def _menu(chat_id: int) -> None:
    keyboard = [[
        {"text": "الحالة", "callback_data": "menu:status"},
        {"text": "المكالمات", "callback_data": "menu:calls"},
    ], [
        {"text": "WhatsApp", "callback_data": "menu:whatsapp"},
        {"text": "SMS", "callback_data": "menu:sms"},
    ], [
        {"text": "النشر", "callback_data": "menu:publish"},
        {"text": "المساعدة", "callback_data": "menu:help"},
    ]]
    _send(chat_id, "مركز تحكم Voicx\nاختر القسم. أي إرسال أو مكالمة أو نشر يتطلب تأكيداً منفصلاً.", keyboard)


def _handle_message(message: dict) -> None:
    chat = message.get("chat") or {}
    sender = message.get("from") or {}
    chat_id, user_id = chat.get("id"), sender.get("id")
    if chat.get("type") != "private" or not isinstance(chat_id, int) or not isinstance(user_id, int):
        return
    if not _allowed_user(user_id):
        return
    text = str(message.get("text") or "").strip()
    if not text:
        return
    command = text.split(maxsplit=1)[0].split("@", 1)[0].lower()
    if command in {"/start", "/menu"}:
        _menu(chat_id)
        return
    if command in {"/help", "/status", "/sip", "/calls"}:
        if command == "/help":
            _send(chat_id, "الأوامر: /status /sip /calls /whatsapp +9677XXXXXXX | نص الرسالة /sms +9677XXXXXXX | نص الرسالة /call +9677XXXXXXX /publish نص\nلا ينفذ البوت أي إجراء حساس دون تأكيد.")
        else:
            _send(chat_id, "هذه واجهة التحكم الخلفية قيد الربط. لم يتم تنفيذ أي اتصال أو إرسال. استخدم لوحة الجهاز للتحقق من حالة SIP والخط.")
        return
    if command not in ACTION_COMMANDS:
        _send(chat_id, "أمر غير معروف. أرسل /help لعرض الأوامر.")
        return
    parts = text.split(maxsplit=1)
    if len(parts) != 2 or not parts[1].strip():
        _send(chat_id, f"صيغة الأمر: {command} <الهدف أو الرقم> | <المحتوى>")
        return
    detail = parts[1].strip()
    target = "غير محدد"
    content_for_preview = detail
    if command in {"/whatsapp", "/sms"}:
        fields = [value.strip() for value in detail.split("|", 1)]
        if len(fields) != 2 or not PHONE_RE.fullmatch(fields[0]) or not fields[1]:
            _send(chat_id, f"الصيغة: {command} +9677XXXXXXX | نص الرسالة")
            return
        target = fields[0][:4] + "****" + fields[0][-3:]
        content_for_preview = fields[1]
    elif command == "/call":
        phone = detail.split("|", 1)[0].strip()
        if not PHONE_RE.fullmatch(phone):
            _send(chat_id, "أدخل رقماً دولياً صحيحاً بصيغة +967...")
            return
        target = phone[:4] + "****" + phone[-3:]
        content_for_preview = "طلب مكالمة صادرة"
    else:
        content_for_preview = detail[:500]
    fingerprint = hashlib.sha256((command + "\0" + detail).encode("utf-8")).hexdigest()
    action_id = _create_pending(user_id, chat_id, ACTION_COMMANDS[command], target, fingerprint)
    preview = content_for_preview[:900]
    keyboard = [[
        {"text": "تأكيد الطلب", "callback_data": f"approve:{action_id}"},
        {"text": "إلغاء", "callback_data": f"cancel:{action_id}"},
    ]]
    _send(chat_id, f"طلب بانتظار تأكيدك\nالنوع: {ACTION_COMMANDS[command]}\nالوجهة: {target}\nالتفاصيل: {preview}\n\nلن يُرسل أو يُتصل أو يُنشر شيء في هذه المرحلة؛ التأكيد يسجل موافقتك فقط، والتنفيذ الخلفي ما زال معطلاً.", keyboard)


def _handle_callback(callback: dict) -> None:
    sender = callback.get("from") or {}
    user_id = sender.get("id")
    message = callback.get("message") or {}
    chat = message.get("chat") or {}
    chat_id = chat.get("id")
    data = str(callback.get("data") or "")
    callback_id = str(callback.get("id") or "")
    if not isinstance(user_id, int) or not isinstance(chat_id, int) or chat.get("type") != "private":
        return
    if not _allowed_user(user_id):
        return
    if data.startswith("menu:"):
        section = data.split(":", 1)[1]
        messages = {
            "status": "الحالة التشغيلية التفصيلية ستُعرض بعد ربط واجهة الأجهزة.",
            "calls": "قسم المكالمات للقراءة فقط حالياً. إنشاء مكالمة يتطلب طلباً وتأكيداً منفصلين.",
            "whatsapp": "لطلب رسالة: /whatsapp +9677XXXXXXX | نص الرسالة",
            "sms": "لطلب SMS: /sms +9677XXXXXXX | نص الرسالة",
            "publish": "لطلب نشر: /publish نص المحتوى",
            "help": "استخدم /help لعرض الأوامر.",
        }
        _answer_callback(callback_id, "تم")
        _send(chat_id, messages.get(section, "قسم غير معروف"))
        return
    match = re.fullmatch(r"(approve|cancel):([0-9a-f]{32})", data)
    if not match:
        _answer_callback(callback_id, "طلب غير صالح")
        return
    approved = match.group(1) == "approve"
    changed = _resolve_pending(match.group(2), user_id, approved)
    if not changed:
        _answer_callback(callback_id, "انتهت صلاحية الطلب أو عولج مسبقاً")
        return
    _answer_callback(callback_id, "تم تسجيل التأكيد" if approved else "تم الإلغاء")
    _send(chat_id, "تم تسجيل موافقتك، لكن التنفيذ الخارجي معطّل حالياً ولن تُرسل رسالة أو تُجرى مكالمة أو يُنشر محتوى." if approved
          else "تم إلغاء الطلب.")


@router.post("/v1/telegram/webhook")
async def telegram_webhook(
    request: FastAPIRequest,
    x_telegram_bot_api_secret_token: str | None = Header(default=None, alias="X-Telegram-Bot-Api-Secret-Token"),
):
    if not _secret_ok(x_telegram_bot_api_secret_token):
        raise HTTPException(status_code=401, detail="invalid_telegram_webhook_secret")
    try:
        update = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="invalid_telegram_update")
    if not isinstance(update, dict) or not isinstance(update.get("update_id"), int):
        raise HTTPException(status_code=400, detail="invalid_telegram_update")
    if not _claim_update(update["update_id"]):
        return {"ok": True, "duplicate": True}
    try:
        if isinstance(update.get("message"), dict):
            _handle_message(update["message"])
        elif isinstance(update.get("callback_query"), dict):
            _handle_callback(update["callback_query"])
    except RuntimeError as exc:
        if str(exc) == "telegram_bot_not_configured":
            raise HTTPException(status_code=503, detail="telegram_bot_not_configured")
        raise HTTPException(status_code=502, detail="telegram_delivery_failed")
    return {"ok": True, "duplicate": False}
