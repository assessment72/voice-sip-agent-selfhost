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
from pathlib import Path
from contextlib import contextmanager
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

DB = os.getenv("DATABASE_PATH", "./calls.sqlite3")
CALL_TOKEN = os.getenv("CALL_API_TOKEN", "")
DEVICE_TOKEN = os.getenv("DEVICE_API_TOKEN", "")
BOOTSTRAP_TOKEN = os.getenv("ORG_BOOTSTRAP_TOKEN", "")
PRESENCE_ONLINE_SECONDS = int(os.getenv("PRESENCE_ONLINE_SECONDS", "60"))
PRESENCE_UNSTABLE_SECONDS = int(os.getenv("PRESENCE_UNSTABLE_SECONDS", "120"))
MAX_CALL = int(os.getenv("MAX_CALL_SECONDS", "1800"))
QUEUE_TTL = int(os.getenv("QUEUE_TTL_SECONDS", "120"))
CELL_RING_TIMEOUT = int(os.getenv("CELLULAR_RING_TIMEOUT_SECONDS", "45"))
SIP_RING_TIMEOUT = int(os.getenv("SIP_RING_TIMEOUT_SECONDS", "45"))
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
          id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL, principal TEXT NOT NULL,
          phone_number TEXT NOT NULL, state TEXT NOT NULL,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL, claimed_by TEXT, claimed_at INTEGER,
          reason_code TEXT, events TEXT NOT NULL DEFAULT '[]',
          UNIQUE(principal, idempotency_key)
        );
        CREATE TABLE IF NOT EXISTS requests (
          id INTEGER PRIMARY KEY AUTOINCREMENT, principal TEXT NOT NULL,
          created_at INTEGER NOT NULL
        );
        """)
        columns = {row["name"] for row in c.execute("PRAGMA table_info(calls)")}
        if "claimed_at" not in columns:
            c.execute("ALTER TABLE calls ADD COLUMN claimed_at INTEGER")
        migration = Path(__file__).parent / "migrations" / "001_tenant_device_foundation.sql"
        if migration.exists():
            c.executescript(migration.read_text(encoding="utf-8"))


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
    elif re.fullmatch(r"7[0-9]{8}", value):
        value = "+967" + value
    if not re.fullmatch(r"\+[1-9][0-9]{7,14}", value):
        raise HTTPException(status_code=422, detail="invalid_phone_number")
    # Fail closed on common international emergency short codes and Yemen emergency
    # service prefixes; handset performs Android's authoritative emergency check too.
    digits = value[1:]
    if digits in {"112", "911", "999", "998", "997", "191", "194"}:
        raise HTTPException(status_code=422, detail="emergency_number")
    if not PREFIXES:
        raise HTTPException(status_code=503, detail="allowlist_not_configured")
    if not any(value.startswith(p) for p in PREFIXES):
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
    now_ms = time.time_ns() // 1_000_000
    events = json.loads(row["events"])
    events.append({"state": state, "at": now, "atMs": now_ms, "reasonCode": reason})
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
        old = c.execute("SELECT * FROM calls WHERE principal=? AND idempotency_key=?", (principal, idempotency_key)).fetchone()
        if old:
            if old["phone_number"] != number:
                c.rollback()
                raise HTTPException(status_code=409, detail="idempotency_key_reused")
            c.commit()
            return safe_call(old)
        c.execute("DELETE FROM requests WHERE created_at < ?", (now - 60,))
        count = c.execute("SELECT COUNT(*) FROM requests WHERE principal=?", (principal,)).fetchone()[0]
        if count >= RATE_LIMIT:
            c.rollback()
            raise HTTPException(status_code=429, detail="rate_limit_exceeded")
        c.execute("INSERT INTO requests(principal,created_at) VALUES(?,?)", (principal, now))
        active = c.execute("SELECT id FROM calls WHERE state NOT IN ('failed','ended') LIMIT 1").fetchone()
        if active:
            c.rollback()
            raise HTTPException(status_code=409, detail="device_busy")
        call_id = str(uuid.uuid4())
        events = json.dumps([{"state": "queued", "at": now, "atMs": time.time_ns() // 1_000_000}])
        c.execute("""INSERT INTO calls(id,idempotency_key,principal,phone_number,state,created_at,updated_at,expires_at,events)
                     VALUES(?,?,?,?,?,?,?,?,?)""",
                  (call_id, idempotency_key, principal, number, "queued", now, now, now + QUEUE_TTL, events))
        c.commit()
        return {"id": call_id, "state": "queued", "createdAt": now, "updatedAt": now, "expiresAt": now + QUEUE_TTL}


@app.get("/v1/calls/{call_id}")
def get_call(call_id: str, principal=Depends(caller)):
    with db() as c:
        row = c.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="call_not_found")
        return safe_call(row)



@app.get("/v1/metrics/latency")
def latency_metrics(principal=Depends(caller)):
    """Caller-authenticated aggregate latency metrics; never returns call IDs or numbers."""
    with db() as c:
        rows = c.execute("SELECT events FROM calls WHERE principal=?", (principal,)).fetchall()

    samples = {"queue_to_cellular_ringing": [], "queue_to_bridged": [], "cellular_answer_to_sip": []}
    for row in rows:
        try:
            events = json.loads(row["events"])
            times = {event.get("state"): event.get("atMs", int(event.get("at", 0)) * 1000)
                     for event in events if event.get("state")}
            queued = times.get("queued")
            ringing = times.get("ringing_cellular")
            bridged = times.get("bridged")
            answered = times.get("cellular_answered")
            sip = times.get("calling_sip")
            if queued is not None and ringing is not None and ringing >= queued:
                samples["queue_to_cellular_ringing"].append(ringing - queued)
            if queued is not None and bridged is not None and bridged >= queued:
                samples["queue_to_bridged"].append(bridged - queued)
            if answered is not None and sip is not None and sip >= answered:
                samples["cellular_answer_to_sip"].append(sip - answered)
        except (TypeError, ValueError, json.JSONDecodeError):
            continue

    def summary(values):
        ordered = sorted(values)
        if not ordered:
            return {"samples": 0, "averageMs": None, "p95Ms": None}
        rank = max(0, int((0.95 * len(ordered) + 0.999999)) - 1)
        return {
            "samples": len(ordered),
            "averageMs": round(sum(ordered) / len(ordered), 2),
            "p95Ms": ordered[min(rank, len(ordered) - 1)],
        }

    return {name: summary(values) for name, values in samples.items()}

@app.get("/v1/device/jobs/next")
def next_job(device_id=Depends(device), wait: int = Query(default=20, ge=0, le=25)):
    deadline = time.monotonic() + wait
    while True:
        result = _claim_next_job(device_id)
        if result["job"] is not None or time.monotonic() >= deadline:
            return result
        time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))


def _claim_next_job(device_id: str):
    now = int(time.time())
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        c.execute("UPDATE calls SET state='failed',reason_code='queue_expired',updated_at=? WHERE state='queued' AND expires_at<?", (now, now))
        c.execute("UPDATE calls SET state='failed',reason_code='cellular_answer_timeout',updated_at=? WHERE state='ringing_cellular' AND updated_at<?", (now, now - CELL_RING_TIMEOUT))
        c.execute("UPDATE calls SET state='failed',reason_code='sip_answer_timeout',updated_at=? WHERE state='calling_sip' AND updated_at<?", (now, now - SIP_RING_TIMEOUT))
        c.execute("UPDATE calls SET state='ended',reason_code='max_call_duration',updated_at=? WHERE state='bridged' AND updated_at<?", (now, now - MAX_CALL))
        c.execute("UPDATE calls SET claimed_by=NULL,claimed_at=NULL WHERE state='queued' AND claimed_at<?", (now - 30,))
        row = c.execute("SELECT * FROM calls WHERE state='queued' AND claimed_by IS NULL AND expires_at>=? ORDER BY created_at LIMIT 1",
                        (now,)).fetchone()
        if not row:
            c.commit()
            return {"job": None}
        c.execute("UPDATE calls SET claimed_by=?,claimed_at=? WHERE id=? AND state='queued' AND claimed_by IS NULL", (device_id, now, row["id"]))
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


# --- Multi-tenant organization and device control plane ---------------------

class OrganizationBootstrap(BaseModel):
    name: str = Field(min_length=2, max_length=120)
    slug: str = Field(min_length=2, max_length=60, pattern=r"^[a-z0-9][a-z0-9-]+$")
    ownerUserId: str = Field(min_length=1, max_length=128)


class EnrollmentTokenRequest(BaseModel):
    label: str = Field(min_length=1, max_length=100)
    expiresInSeconds: int = Field(default=3600, ge=60, le=604800)
    maxUses: int = Field(default=1, ge=1, le=100)


class DeviceEnrollment(BaseModel):
    enrollmentToken: str = Field(min_length=32, max_length=256)
    externalDeviceId: str = Field(min_length=1, max_length=128)
    displayName: str = Field(min_length=1, max_length=120)
    branchName: str | None = Field(default=None, max_length=120)
    appId: str | None = Field(default=None, max_length=160)
    appVersion: str | None = Field(default=None, max_length=40)
    androidVersion: str | None = Field(default=None, max_length=40)
    manufacturer: str | None = Field(default=None, max_length=80)
    model: str | None = Field(default=None, max_length=100)


class DeviceHeartbeat(BaseModel):
    batteryLevel: int | None = Field(default=None, ge=0, le=100)
    networkType: Literal["wifi", "cellular", "ethernet", "none", "unknown"] = "unknown"
    internetAvailable: bool = False
    sipStatus: Literal["registered", "unregistered", "registering", "error", "unknown"] = "unknown"
    simStatus: Literal["ready", "absent", "locked", "error", "unknown"] = "unknown"
    agentStatus: Literal["idle", "busy", "paused", "error", "unknown"] = "unknown"
    activeCall: bool = False


def _hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def organization_principal(authorization: str | None = Header(default=None),
                           x_organization_id: str = Header(alias="X-Organization-ID")):
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token:
        raise HTTPException(status_code=401, detail="unauthorized")
    with db() as c:
        row = c.execute(
            """SELECT m.organization_id,m.user_id,m.role
               FROM organization_api_credentials k
               JOIN organization_memberships m ON m.organization_id=k.organization_id
               WHERE k.token_hash=? AND k.revoked_at IS NULL
                 AND (k.expires_at IS NULL OR k.expires_at>?)
                 AND m.status='active' AND m.organization_id=?""",
            (_hash_secret(token), int(time.time()), x_organization_id),
        ).fetchone()
    if not row:
        raise HTTPException(status_code=403, detail="organization_access_denied")
    return {"organization_id": row["organization_id"], "user_id": row["user_id"], "role": row["role"]}


def require_org_admin(principal=Depends(organization_principal)):
    if principal["role"] not in {"owner", "admin"}:
        raise HTTPException(status_code=403, detail="insufficient_role")
    return principal


def authenticated_device(authorization: str | None = Header(default=None)):
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token:
        raise HTTPException(status_code=401, detail="unauthorized")
    with db() as c:
        row = c.execute(
            """SELECT d.id,d.organization_id,d.status
               FROM device_credentials k JOIN devices d ON d.id=k.device_id
               WHERE k.token_hash=? AND k.revoked_at IS NULL
                 AND (k.expires_at IS NULL OR k.expires_at>?)""",
            (_hash_secret(token), int(time.time())),
        ).fetchone()
    if not row or row["status"] != "active":
        raise HTTPException(status_code=401, detail="device_unauthorized")
    return {"device_id": row["id"], "organization_id": row["organization_id"]}


@app.post("/v1/organizations/bootstrap", status_code=201)
def bootstrap_organization(body: OrganizationBootstrap,
                           authorization: str | None = Header(default=None)):
    supplied = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token_ok(supplied, BOOTSTRAP_TOKEN):
        raise HTTPException(status_code=401, detail="bootstrap_unauthorized")
    now = int(time.time())
    org_id = str(uuid.uuid4())
    raw_token = secrets.token_urlsafe(40)
    credential_id = str(uuid.uuid4())
    with db() as c:
        try:
            c.execute("BEGIN IMMEDIATE")
            c.execute(
                "INSERT INTO organizations(id,slug,name,created_at,updated_at) VALUES(?,?,?,?,?)",
                (org_id, body.slug, body.name, now, now),
            )
            c.execute(
                """INSERT INTO organization_memberships
                   (organization_id,user_id,role,status,created_at,updated_at)
                   VALUES(?,?, 'owner','active',?,?)""",
                (org_id, body.ownerUserId, now, now),
            )
            c.execute(
                """INSERT INTO organization_api_credentials
                   (id,organization_id,label,token_hash,token_prefix,scopes,created_at)
                   VALUES(?,?,?,?,?,'["admin"]',?)""",
                (credential_id, org_id, "Initial owner credential", _hash_secret(raw_token), raw_token[:8], now),
            )
            c.commit()
        except sqlite3.IntegrityError:
            c.rollback()
            raise HTTPException(status_code=409, detail="organization_slug_unavailable")
    return {"organizationId": org_id, "slug": body.slug, "apiToken": raw_token,
            "tokenWarning": "Shown once. Store securely; only its hash is retained."}


@app.post("/v1/organizations/{organization_id}/enrollment-tokens", status_code=201)
def create_enrollment_token(organization_id: str, body: EnrollmentTokenRequest,
                            principal=Depends(require_org_admin)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    now = int(time.time())
    raw_token = secrets.token_urlsafe(40)
    token_id = str(uuid.uuid4())
    with db() as c:
        c.execute(
            """INSERT INTO device_enrollment_tokens
               (id,organization_id,token_hash,label,expires_at,max_uses,created_by,created_at)
               VALUES(?,?,?,?,?,?,?,?)""",
            (token_id, organization_id, _hash_secret(raw_token), body.label,
             now + body.expiresInSeconds, body.maxUses, principal["user_id"], now),
        )
    return {"tokenId": token_id, "enrollmentToken": raw_token,
            "expiresAt": now + body.expiresInSeconds, "maxUses": body.maxUses,
            "tokenWarning": "Shown once. Share only with intended devices."}


@app.post("/v1/device/enroll", status_code=201)
def enroll_device(body: DeviceEnrollment):
    now = int(time.time())
    raw_device_token = secrets.token_urlsafe(48)
    device_id = str(uuid.uuid4())
    credential_id = str(uuid.uuid4())
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        enrollment = c.execute(
            """SELECT * FROM device_enrollment_tokens
               WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?
                 AND used_count<max_uses""",
            (_hash_secret(body.enrollmentToken), now),
        ).fetchone()
        if not enrollment:
            c.rollback()
            raise HTTPException(status_code=401, detail="enrollment_token_invalid")
        try:
            c.execute(
                """INSERT INTO devices
                   (id,organization_id,external_device_id,display_name,branch_name,status,presence,
                    app_id,app_version,android_version,manufacturer,model,created_at,updated_at)
                   VALUES(?,?,?,?,?,'active','offline',?,?,?,?,?,?,?)""",
                (device_id, enrollment["organization_id"], body.externalDeviceId, body.displayName,
                 body.branchName, body.appId, body.appVersion, body.androidVersion,
                 body.manufacturer, body.model, now, now),
            )
        except sqlite3.IntegrityError:
            c.rollback()
            raise HTTPException(status_code=409, detail="device_identity_already_enrolled")
        c.execute(
            """INSERT INTO device_credentials
               (id,device_id,token_hash,token_prefix,created_at)
               VALUES(?,?,?,?,?)""",
            (credential_id, device_id, _hash_secret(raw_device_token), raw_device_token[:8], now),
        )
        c.execute("UPDATE device_enrollment_tokens SET used_count=used_count+1 WHERE id=?",
                  (enrollment["id"],))
        c.commit()
    return {"deviceId": device_id, "organizationId": enrollment["organization_id"],
            "deviceToken": raw_device_token,
            "tokenWarning": "Shown once. Store in Android Keystore-backed storage."}


@app.post("/v1/device/heartbeat")
def device_heartbeat(body: DeviceHeartbeat, device_ctx=Depends(authenticated_device)):
    now = int(time.time())
    device_id = device_ctx["device_id"]
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        c.execute(
            """UPDATE devices SET last_seen_at=?,presence='online',battery_level=?,
               network_type=?,internet_available=?,sip_status=?,sim_status=?,agent_status=?,
               active_call_id=NULL,updated_at=? WHERE id=? AND organization_id=? AND status='active'""",
            (now, body.batteryLevel, body.networkType, int(body.internetAvailable),
             body.sipStatus, body.simStatus, body.agentStatus, ("active" if body.activeCall else None), now,
             device_id, device_ctx["organization_id"]),
        )
        c.execute(
            """INSERT INTO device_heartbeats
               (device_id,received_at,battery_level,network_type,internet_available,
                sip_status,sim_status,agent_status,active_call_id)
               VALUES(?,?,?,?,?,?,?,?,NULL)""",
            (device_id, now, body.batteryLevel, body.networkType, int(body.internetAvailable),
             body.sipStatus, body.simStatus, body.agentStatus),
        )
        c.commit()
    return {"accepted": True, "serverTime": now, "presence": "online"}


@app.get("/v1/admin/devices")
def list_organization_devices(principal=Depends(organization_principal)):
    now = int(time.time())
    with db() as c:
        rows = c.execute(
            """SELECT id,external_device_id,display_name,branch_name,status,last_seen_at,
                      app_version,android_version,manufacturer,model,battery_level,network_type,
                      internet_available,sip_status,sim_status,agent_status,active_call_id
               FROM devices WHERE organization_id=? ORDER BY display_name""",
            (principal["organization_id"],),
        ).fetchall()
    result = []
    for row in rows:
        item = dict(row)
        last_seen = item["last_seen_at"]
        age = now - last_seen if last_seen is not None else None
        if item["status"] != "active" or age is None or age > PRESENCE_UNSTABLE_SECONDS:
            presence = "offline"
        elif age > PRESENCE_ONLINE_SECONDS:
            presence = "unstable"
        else:
            presence = "online"
        item["presence"] = presence
        item["lastSeenAgeSeconds"] = age
        result.append(item)
    return {"devices": result, "count": len(result)}


@app.get("/v1/admin/devices/{device_id}/activity")
def device_activity(device_id: str, limit: int = Query(default=50, ge=1, le=200),
                    principal=Depends(organization_principal)):
    with db() as c:
        device_row = c.execute(
            "SELECT id FROM devices WHERE id=? AND organization_id=?",
            (device_id, principal["organization_id"]),
        ).fetchone()
        if not device_row:
            raise HTTPException(status_code=404, detail="device_not_found")
        rows = c.execute(
            """SELECT event_type,severity,event_code,occurred_at,received_at
               FROM device_events WHERE device_id=? AND organization_id=?
               ORDER BY received_at DESC LIMIT ?""",
            (device_id, principal["organization_id"], limit),
        ).fetchall()
    return {"events": [dict(row) for row in rows]}


@app.post("/v1/device/events", status_code=202)
def report_device_event(body: dict, device_ctx=Depends(authenticated_device)):
    allowed = {"eventType", "severity", "eventCode", "occurredAt"}
    if set(body) - allowed or not {"eventType", "eventCode"}.issubset(body):
        raise HTTPException(status_code=422, detail="invalid_event_payload")
    event_type = body["eventType"]
    event_code = body["eventCode"]
    severity = body.get("severity", "info")
    if not isinstance(event_type, str) or len(event_type) > 64:
        raise HTTPException(status_code=422, detail="invalid_event_type")
    if not isinstance(event_code, str) or len(event_code) > 80:
        raise HTTPException(status_code=422, detail="invalid_event_code")
    if severity not in {"debug", "info", "warning", "error", "critical"}:
        raise HTTPException(status_code=422, detail="invalid_event_severity")
    now = int(time.time())
    with db() as c:
        c.execute(
            """INSERT INTO device_events
               (id,organization_id,device_id,event_type,severity,event_code,details_json,occurred_at,received_at)
               VALUES(?,?,?,?,?,?, '{}',?,?)""",
            (str(uuid.uuid4()), device_ctx["organization_id"], device_ctx["device_id"],
             event_type, severity, event_code, int(body.get("occurredAt", now)), now),
        )
    return {"accepted": True}


class OrganizationMemberRequest(BaseModel):
    userId: str = Field(min_length=1, max_length=128)
    role: Literal["admin", "operator", "viewer"]


class DeviceCommandRequest(BaseModel):
    deviceId: str = Field(min_length=1, max_length=64)
    commandType: Literal["refresh_status", "sync_config", "pause_calls", "resume_calls", "restart_agent"]
    expiresInSeconds: int = Field(default=300, ge=30, le=3600)


class DeviceCommandResult(BaseModel):
    status: Literal["acknowledged", "completed", "failed"]
    resultCode: str | None = Field(default=None, max_length=64)
    resultSummary: str | None = Field(default=None, max_length=200)


@app.post("/v1/organizations/{organization_id}/members", status_code=201)
def add_organization_member(organization_id: str, body: OrganizationMemberRequest,
                            principal=Depends(require_org_admin)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    if principal["role"] != "owner" and body.role == "admin":
        raise HTTPException(status_code=403, detail="owner_role_required")
    now = int(time.time())
    with db() as c:
        existing = c.execute(
            "SELECT role,status FROM organization_memberships WHERE organization_id=? AND user_id=?",
            (organization_id, body.userId),
        ).fetchone()
        if existing:
            raise HTTPException(status_code=409, detail="membership_already_exists")
        c.execute(
            """INSERT INTO organization_memberships
               (organization_id,user_id,role,status,created_at,updated_at)
               VALUES(?,?,?,'active',?,?)""",
            (organization_id, body.userId, body.role, now, now),
        )
    return {"organizationId": organization_id, "userId": body.userId, "role": body.role, "status": "active"}


@app.post("/v1/admin/devices/{device_id}/revoke")
def revoke_device(device_id: str, principal=Depends(require_org_admin)):
    now = int(time.time())
    with db() as c:
        row = c.execute(
            "SELECT id FROM devices WHERE id=? AND organization_id=?",
            (device_id, principal["organization_id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="device_not_found")
        c.execute("UPDATE devices SET status='revoked',presence='offline',updated_at=? WHERE id=?",
                  (now, device_id))
        c.execute("UPDATE device_credentials SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL",
                  (now, device_id))
        c.execute(
            """UPDATE device_commands SET status='cancelled'
               WHERE device_id=? AND status IN ('pending','delivered')""",
            (device_id,),
        )
    return {"deviceId": device_id, "status": "revoked"}


@app.post("/v1/admin/device-commands", status_code=201)
def create_device_command(body: DeviceCommandRequest, principal=Depends(require_org_admin)):
    now = int(time.time())
    command_id = str(uuid.uuid4())
    with db() as c:
        target = c.execute(
            "SELECT id,status FROM devices WHERE id=? AND organization_id=?",
            (body.deviceId, principal["organization_id"]),
        ).fetchone()
        if not target or target["status"] != "active":
            raise HTTPException(status_code=404, detail="active_device_not_found")
        c.execute(
            """INSERT INTO device_commands
               (id,organization_id,device_id,command_type,payload_json,status,created_by,
                created_at,expires_at)
               VALUES(?,?,?,?, '{}','pending',?,?,?)""",
            (command_id, principal["organization_id"], body.deviceId, body.commandType,
             principal["user_id"], now, now + body.expiresInSeconds),
        )
    return {"commandId": command_id, "status": "pending", "expiresAt": now + body.expiresInSeconds}


@app.get("/v1/device/commands/next")
def next_device_command(wait: int = Query(default=20, ge=0, le=25),
                        device_ctx=Depends(authenticated_device)):
    deadline = time.monotonic() + wait
    while True:
        now = int(time.time())
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            c.execute(
                "UPDATE device_commands SET status='expired' WHERE device_id=? AND status='pending' AND expires_at<=?",
                (device_ctx["device_id"], now),
            )
            row = c.execute(
                """SELECT id,command_type,payload_json,expires_at FROM device_commands
                   WHERE device_id=? AND organization_id=? AND status='pending' AND expires_at>?
                   ORDER BY created_at LIMIT 1""",
                (device_ctx["device_id"], device_ctx["organization_id"], now),
            ).fetchone()
            if row:
                c.execute(
                    "UPDATE device_commands SET status='delivered',delivered_at=? WHERE id=? AND status='pending'",
                    (now, row["id"]),
                )
                c.commit()
                return {"command": {"id": row["id"], "type": row["command_type"],
                                    "payload": json.loads(row["payload_json"]), "expiresAt": row["expires_at"]}}
            c.commit()
        if time.monotonic() >= deadline:
            return {"command": None}
        time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))


@app.post("/v1/device/commands/{command_id}/result")
def update_device_command(command_id: str, body: DeviceCommandResult,
                          device_ctx=Depends(authenticated_device)):
    now = int(time.time())
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute(
            """SELECT status FROM device_commands
               WHERE id=? AND device_id=? AND organization_id=?""",
            (command_id, device_ctx["device_id"], device_ctx["organization_id"]),
        ).fetchone()
        if not row:
            c.rollback()
            raise HTTPException(status_code=404, detail="command_not_found")
        if row["status"] not in {"delivered", "acknowledged"}:
            c.rollback()
            raise HTTPException(status_code=409, detail="command_not_active")
        if body.status == "acknowledged":
            c.execute(
                """UPDATE device_commands SET status='acknowledged',acknowledged_at=?
                   WHERE id=?""",
                (now, command_id),
            )
        else:
            c.execute(
                """UPDATE device_commands SET status=?,completed_at=?,result_code=?,result_summary=?
                   WHERE id=?""",
                (body.status, now, body.resultCode, body.resultSummary, command_id),
            )
        c.commit()
    return {"commandId": command_id, "status": body.status}


@app.get("/v1/admin/calls")
def list_organization_calls(limit: int = Query(default=50, ge=1, le=200),
                             principal=Depends(organization_principal)):
    with db() as c:
        rows = c.execute(
            """SELECT calls.id,calls.state,calls.created_at,calls.updated_at,calls.reason_code,
                      call_tenant_assignments.device_id
               FROM calls JOIN call_tenant_assignments ON call_tenant_assignments.call_id=calls.id
               WHERE call_tenant_assignments.organization_id=?
               ORDER BY calls.created_at DESC LIMIT ?""",
            (principal["organization_id"], limit),
        ).fetchall()
    return {"calls": [dict(row) for row in rows], "count": len(rows)}
