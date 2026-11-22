from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from pydantic import BaseModel, Field

DB = os.getenv("DATABASE_PATH", "./calls.sqlite3")
CALL_TOKEN = os.getenv("CALL_API_TOKEN", "")
DEVICE_TOKEN = os.getenv("DEVICE_API_TOKEN", "")
MAX_CALL = int(os.getenv("MAX_CALL_SECONDS", "1800"))
QUEUE_TTL = int(os.getenv("QUEUE_TTL_SECONDS", "120"))
RATE_LIMIT = int(os.getenv("RATE_LIMIT_PER_MINUTE", "5"))
PREFIXES = tuple(p.strip() for p in os.getenv("ALLOWED_PREFIXES", "").split(",") if p.strip())
STATES = {"queued", "ringing_cellular", "cellular_answered", "calling_sip", "bridged", "failed", "ended"}
TERMINAL = {"failed", "ended"}

app = FastAPI(title="Hosted Call API", version="1.0.0")


@contextmanager
def db():
    conn = sqlite3.connect(DB, timeout=10, isolation_level=None)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        yield conn
    finally:
        conn.close()


def init_db():
    with db() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS calls (
          id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE,
          phone_number TEXT NOT NULL, state TEXT NOT NULL,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL, claimed_by TEXT,
          reason_code TEXT, events TEXT NOT NULL DEFAULT '[]'
        );
        CREATE TABLE IF NOT EXISTS requests (
          id INTEGER PRIMARY KEY AUTOINCREMENT, principal TEXT NOT NULL,
          created_at INTEGER NOT NULL
        );
        """)


@app.on_event("startup")
def startup():
    init_db()


def token_ok(provided: str | None, expected: str) -> bool:
    return bool(provided and expected and hmac.compare_digest(
        hashlib.sha256(provided.encode()).digest(),
        hashlib.sha256(expected.encode()).digest()))


def caller(authorization: str | None = Header(default=None)):
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token_ok(token, CALL_TOKEN):
        raise HTTPException(status_code=401, detail="unauthorized")
    return "api"


def device(authorization: str | None = Header(default=None)):
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token_ok(token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="unauthorized")
    return "device-1"


def normalize_number(raw: str) -> str:
    value = re.sub(r"[\s().-]", "", raw)
    if value.startswith("00"):
        value = "+" + value[2:]
    if not re.fullmatch(r"\+[1-9][0-9]{7,14}", value):
        raise HTTPException(status_code=422, detail="invalid_phone_number")
    # Fail closed on common international emergency short codes and Yemen emergency
    # service prefixes; handset performs Android's authoritative emergency check too.
    digits = value[1:]
    if digits in {"112", "911", "999", "998", "997", "191", "194"}:
        raise HTTPException(status_code=422, detail="emergency_number")
    if PREFIXES and not any(value.startswith(p) for p in PREFIXES):
        raise HTTPException(status_code=403, detail="number_not_allowed")
    return value


class CreateCall(BaseModel):
    phoneNumber: str = Field(min_length=3, max_length=32)


class CallUpdate(BaseModel):
    state: Literal["ringing_cellular", "cellular_answered", "calling_sip", "bridged", "failed", "ended"]
    reasonCode: str | None = Field(default=None, max_length=64)


def safe_call(row):
    return {
        "id": row["id"], "state": row["state"],
        "createdAt": row["created_at"], "updatedAt": row["updated_at"],
        "expiresAt": row["expires_at"], "reasonCode": row["reason_code"],
    }


def transition(c, call_id: str, state: str, reason: str | None = None):
    row = c.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="call_not_found")
    if row["state"] in TERMINAL:
        return row
    allowed = {
        "queued": {"ringing_cellular", "failed"},
        "ringing_cellular": {"cellular_answered", "failed", "ended"},
        "cellular_answered": {"calling_sip", "failed", "ended"},
        "calling_sip": {"bridged", "failed", "ended"},
        "bridged": {"ended", "failed"},
    }
    if state not in allowed.get(row["state"], set()):
        raise HTTPException(status_code=409, detail="invalid_state_transition")
    now = int(time.time())
    events = json.loads(row["events"])
    events.append({"state": state, "at": now, "reasonCode": reason})
    c.execute("UPDATE calls SET state=?,updated_at=?,reason_code=?,events=? WHERE id=?",
              (state, now, reason, json.dumps(events), call_id))
    return c.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()


@app.post("/v1/calls", status_code=status.HTTP_202_ACCEPTED)
def create_call(body: CreateCall, principal=Depends(caller),
                idempotency_key: str = Header(alias="Idempotency-Key", min_length=16, max_length=128)):
    number = normalize_number(body.phoneNumber)
    now = int(time.time())
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        c.execute("DELETE FROM requests WHERE created_at < ?", (now - 60,))
        count = c.execute("SELECT COUNT(*) FROM requests WHERE principal=?", (principal,)).fetchone()[0]
        if count >= RATE_LIMIT:
            c.rollback()
            raise HTTPException(status_code=429, detail="rate_limit_exceeded")
        c.execute("INSERT INTO requests(principal,created_at) VALUES(?,?)", (principal, now))
        old = c.execute("SELECT * FROM calls WHERE idempotency_key=?", (idempotency_key,)).fetchone()
        if old:
            c.commit()
            return safe_call(old)
        active = c.execute("SELECT id FROM calls WHERE state NOT IN ('failed','ended') LIMIT 1").fetchone()
        if active:
            c.rollback()
            raise HTTPException(status_code=409, detail="device_busy")
        call_id = str(uuid.uuid4())
        events = json.dumps([{"state": "queued", "at": now}])
        c.execute("""INSERT INTO calls(id,idempotency_key,phone_number,state,created_at,updated_at,expires_at,events)
                     VALUES(?,?,?,?,?,?,?,?)""",
                  (call_id, idempotency_key, number, "queued", now, now, now + QUEUE_TTL, events))
        c.commit()
        return {"id": call_id, "state": "queued", "createdAt": now, "updatedAt": now, "expiresAt": now + QUEUE_TTL}


@app.get("/v1/calls/{call_id}")
def get_call(call_id: str, principal=Depends(caller)):
    with db() as c:
        row = c.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="call_not_found")
        return safe_call(row)


@app.get("/v1/device/jobs/next")
def next_job(device_id=Depends(device)):
    now = int(time.time())
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        c.execute("UPDATE calls SET state='failed',reason_code='queue_expired',updated_at=? WHERE state='queued' AND expires_at<?",
                  (now, now))
        row = c.execute("SELECT * FROM calls WHERE state='queued' AND expires_at>=? ORDER BY created_at LIMIT 1",
                        (now,)).fetchone()
        if not row:
            c.commit()
            return {"job": None}
        c.execute("UPDATE calls SET claimed_by=? WHERE id=? AND state='queued'", (device_id, row["id"]))
        c.commit()
        return {"job": {"id": row["id"], "phoneNumber": row["phone_number"], "expiresAt": row["expires_at"]}}


@app.post("/v1/device/calls/{call_id}/state")
def update_call(call_id: str, body: CallUpdate, device_id=Depends(device)):
    with db() as c:
        row = c.execute("SELECT claimed_by FROM calls WHERE id=?", (call_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="call_not_found")
        if row["claimed_by"] != device_id:
            raise HTTPException(status_code=403, detail="job_not_owned")
        updated = transition(c, call_id, body.state, body.reasonCode)
        return safe_call(updated)


@app.get("/health")
def health():
    return {"status": "ok"}
