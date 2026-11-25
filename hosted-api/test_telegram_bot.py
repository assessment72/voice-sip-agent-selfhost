import os
import sqlite3
import time

import telegram_bot


def test_allowlist_requires_exact_numeric_user_id(monkeypatch):
    monkeypatch.setenv("TELEGRAM_ALLOWED_USER_IDS", "12345,67890")
    assert telegram_bot._allowed_user(12345)
    assert not telegram_bot._allowed_user(1234)


def test_webhook_secret_is_compared_and_required(monkeypatch):
    monkeypatch.setenv("TELEGRAM_WEBHOOK_SECRET", "test-secret-value")
    assert telegram_bot._secret_ok("test-secret-value")
    assert not telegram_bot._secret_ok("wrong")
    monkeypatch.delenv("TELEGRAM_WEBHOOK_SECRET")
    assert not telegram_bot._secret_ok("test-secret-value")


def test_confirmation_is_bound_to_requesting_user_and_one_time(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "telegram.sqlite3"))
    action_id = telegram_bot._create_pending(
        user_id=123, chat_id=456, action_type="sms",
        target="+9677****123", fingerprint="hash-only",
    )
    assert not telegram_bot._resolve_pending(action_id, 999, 456, True)
    assert not telegram_bot._resolve_pending(action_id, 123, 999, True)
    assert telegram_bot._resolve_pending(action_id, 123, 456, True)
    assert not telegram_bot._resolve_pending(action_id, 123, 456, True)
    with sqlite3.connect(tmp_path / "telegram.sqlite3") as connection:
        row = connection.execute(
            "SELECT status,payload_hash FROM telegram_pending_actions WHERE id=?",
            (action_id,),
        ).fetchone()
    assert row == ("approved_not_executed", "hash-only")


def test_sensitive_command_creates_pending_request_without_storing_message_body(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "telegram.sqlite3"))
    monkeypatch.setenv("TELEGRAM_ALLOWED_USER_IDS", "123")
    monkeypatch.setenv("SMS_ALLOWED_PREFIXES", "+9677")
    sent = []
    monkeypatch.setattr(telegram_bot, "_send", lambda chat_id, text, keyboard=None:
                        sent.append((chat_id, text, keyboard)))
    telegram_bot._handle_message({
        "chat": {"id": 456, "type": "private"},
        "from": {"id": 123},
        "text": "/sms +967712345678 | Test private marketing message",
    })
    assert len(sent) == 1
    assert "بانتظار تأكيدك" in sent[0][1]
    assert sent[0][2][0][0]["callback_data"].startswith("approve:")
    with sqlite3.connect(tmp_path / "telegram.sqlite3") as connection:
        row = connection.execute(
            "SELECT action_type,target_masked,payload_hash FROM telegram_pending_actions"
        ).fetchone()
    assert row[0] == "sms"
    assert row[1] == "+967****678"
    assert "Test private marketing message" not in str(row)


def test_approval_callback_only_records_approval_never_executes_provider(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "telegram.sqlite3"))
    monkeypatch.setenv("TELEGRAM_ALLOWED_USER_IDS", "123")
    action_id = telegram_bot._create_pending(
        user_id=123, chat_id=456, action_type="call",
        target="+9677****123", fingerprint="fingerprint",
    )
    outbound = []
    monkeypatch.setattr(telegram_bot, "_telegram_api",
                        lambda method, payload: outbound.append((method, payload)) or {"ok": True})
    telegram_bot._handle_callback({
        "id": "callback-1",
        "from": {"id": 123},
        "message": {"chat": {"id": 456, "type": "private"}},
        "data": f"approve:{action_id}",
    })
    assert any(method == "answerCallbackQuery" for method, _ in outbound)
    assert any(method == "sendMessage" for method, _ in outbound)
    with sqlite3.connect(tmp_path / "telegram.sqlite3") as connection:
        status = connection.execute(
            "SELECT status FROM telegram_pending_actions WHERE id=?", (action_id,)
        ).fetchone()[0]
    assert status == "approved_not_executed"


def test_confirmation_expires_after_ten_minutes(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "telegram.sqlite3"))
    action_id = telegram_bot._create_pending(
        user_id=123, chat_id=456, action_type="publish",
        target="content", fingerprint="fingerprint",
    )
    with sqlite3.connect(tmp_path / "telegram.sqlite3") as connection:
        connection.execute(
            "UPDATE telegram_pending_actions SET created_at=? WHERE id=?",
            (int(time.time()) - 601, action_id),
        )
        connection.commit()
    assert not telegram_bot._resolve_pending(action_id, 123, 456, True)
