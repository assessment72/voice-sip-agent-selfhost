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
from contextlib import asynccontextmanager, contextmanager
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

DB = os.getenv("DATABASE_PATH", "./calls.sqlite3")
CALL_TOKEN = os.getenv("CALL_API_TOKEN", "")
DEVICE_TOKEN = os.getenv("DEVICE_API_TOKEN", "")
LEGACY_CALL_API_ENABLED = os.getenv("ENABLE_LEGACY_CALL_API", "false").lower() in {"1", "true", "yes"}
LEGACY_DEVICE_API_ENABLED = os.getenv("ENABLE_LEGACY_DEVICE_API", "false").lower() in {"1", "true", "yes"}
BOOTSTRAP_TOKEN = os.getenv("ORG_BOOTSTRAP_TOKEN", "")
PRESENCE_ONLINE_SECONDS = int(os.getenv("PRESENCE_ONLINE_SECONDS", "60"))
PRESENCE_UNSTABLE_SECONDS = int(os.getenv("PRESENCE_UNSTABLE_SECONDS", "120"))
MAX_CALL = int(os.getenv("MAX_CALL_SECONDS", "1800"))
QUEUE_TTL = int(os.getenv("QUEUE_TTL_SECONDS", "120"))
CELL_RING_TIMEOUT = int(os.getenv("CELLULAR_RING_TIMEOUT_SECONDS", "45"))
SIP_RING_TIMEOUT = int(os.getenv("SIP_RING_TIMEOUT_SECONDS", "45"))
RATE_LIMIT = int(os.getenv("RATE_LIMIT_PER_MINUTE", "5"))
IP_RATE_LIMIT_PER_MINUTE = int(os.getenv("IP_RATE_LIMIT_PER_MINUTE", "120"))
GENERAL_RATE_LIMIT_PER_MINUTE = int(os.getenv("GENERAL_RATE_LIMIT_PER_MINUTE", "60"))
REQUIRE_DISTRIBUTED_RATE_LIMIT = os.getenv(
    "REQUIRE_DISTRIBUTED_RATE_LIMIT",
    "true" if os.getenv("VERCEL", "").lower() in {"1", "true", "yes"} else "false",
).lower() in {"1", "true", "yes"}
PREFIXES = tuple(p.strip() for p in os.getenv("ALLOWED_PREFIXES", "").split(",") if p.strip())
STATES = {"queued", "ringing_cellular", "cellular_answered", "calling_sip", "bridged", "failed", "ended"}
TERMINAL = {"failed", "ended"}



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
        CREATE TABLE IF NOT EXISTS request_limits (
          key_hash TEXT NOT NULL, window_start INTEGER NOT NULL,
          count INTEGER NOT NULL, PRIMARY KEY(key_hash, window_start)
        );
        CREATE TABLE IF NOT EXISTS admin_audit_events (
          id TEXT PRIMARY KEY, organization_id TEXT NOT NULL,
          actor_user_id TEXT NOT NULL, action TEXT NOT NULL,
          target_type TEXT NOT NULL, target_id TEXT NOT NULL,
          metadata_json TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_audit_org_created
          ON admin_audit_events(organization_id, created_at DESC);
        """)
        columns = {row["name"] for row in c.execute("PRAGMA table_info(calls)")}
        if "claimed_at" not in columns:
            c.execute("ALTER TABLE calls ADD COLUMN claimed_at INTEGER")
        migration = Path(__file__).parent / "migrations" / "001_tenant_device_foundation.sql"
        if migration.exists():
            c.executescript(migration.read_text(encoding="utf-8"))
        credential_columns = {row["name"] for row in c.execute("PRAGMA table_info(organization_api_credentials)")}
        if credential_columns and "user_id" not in credential_columns:
            c.execute("ALTER TABLE organization_api_credentials ADD COLUMN user_id TEXT")
        device_columns = {row["name"] for row in c.execute("PRAGMA table_info(devices)")}
        if device_columns and "branch_id" not in device_columns:
            c.execute("ALTER TABLE devices ADD COLUMN branch_id TEXT REFERENCES branches(id) ON DELETE SET NULL")


def startup():
    is_vercel = os.getenv("VERCEL", "").lower() in {"1", "true", "yes"}
    mongo_configured = bool(os.getenv("MONGODB_URI", "").strip())
    if is_vercel and not mongo_configured:
        raise RuntimeError("production_blocked_sqlite_not_durable_use_mongodb_repository")
    if is_vercel and os.getenv("ALLOW_UNSAFE_SQLITE_ON_VERCEL", "false").lower() in {"1", "true", "yes"}:
        raise RuntimeError("unsafe_sqlite_override_is_not_supported")
    if mongo_configured:
        # MongoDB is the only persistent backend on serverless. Never initialize
        # or write the ephemeral SQLite database on Vercel.
        from mongodb_store import ensure_indexes
        ensure_indexes()
        return
    init_db()


@asynccontextmanager
async def lifespan(app: FastAPI):
    startup()
    yield


app = FastAPI(title="Hosted Call API", version="1.0.0", lifespan=lifespan)


def _consume_local_rate_limit(key_hash: str, window_start: int, limit: int) -> tuple[bool, int]:
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        c.execute(
            """INSERT INTO request_limits(key_hash,window_start,count) VALUES(?,?,1)
               ON CONFLICT(key_hash,window_start) DO UPDATE SET count=count+1""",
            (key_hash, window_start),
        )
        count = c.execute(
            "SELECT count FROM request_limits WHERE key_hash=? AND window_start=?",
            (key_hash, window_start),
        ).fetchone()["count"]
        c.execute("DELETE FROM request_limits WHERE window_start<?", (window_start - 120,))
        c.commit()
    return count <= limit, count


def _rate_limit_check(key_hash: str, window_start: int, limit: int) -> tuple[bool, int]:
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_store import consume_rate_limit
        return consume_rate_limit(
            key_hash=key_hash, window_start=window_start, limit=limit,
            expires_at=window_start + 180,
        )
    if REQUIRE_DISTRIBUTED_RATE_LIMIT:
        raise RuntimeError("distributed_rate_limiter_unavailable")
    return _consume_local_rate_limit(key_hash, window_start, limit)


@app.middleware("http")
async def api_rate_limit(request: Request, call_next):
    if not request.url.path.startswith("/v1/"):
        return await call_next(request)
    now = int(time.time())
    window_start = now - (now % 60)
    path = request.url.path
    method = request.method.upper()
    is_call_create = method == "POST" and path.endswith("/calls")
    is_enrollment = method == "POST" and path in {
        "/v1/organizations/bootstrap", "/v1/device/enroll"
    }
    is_heartbeat = path in {"/v1/device/heartbeat", "/v1/device/events"}
    token = request.headers.get("authorization", "")
    token_value = token[7:] if token.startswith("Bearer ") else ""
    client_ip = request.client.host if request.client else "unknown"
    checks = [
        (_hash_secret("ip:" + client_ip), IP_RATE_LIMIT_PER_MINUTE),
    ]
    if token_value:
        checks.append((_hash_secret("credential:" + token_value), 5 if is_call_create or is_enrollment else GENERAL_RATE_LIMIT_PER_MINUTE))
    if is_call_create or is_enrollment:
        checks.append((_hash_secret("sensitive:" + client_ip + ":" + path), 5))
    elif is_heartbeat:
        checks.append((_hash_secret("device-event:" + (token_value or client_ip)), 120))
    else:
        checks.append((_hash_secret("route:" + (token_value or client_ip) + ":" + path), GENERAL_RATE_LIMIT_PER_MINUTE))
    try:
        for key_hash, limit in checks:
            allowed, _ = _rate_limit_check(key_hash, window_start, limit)
            if not allowed:
                return JSONResponse(
                    status_code=429,
                    content={"detail": "rate_limit_exceeded"},
                    headers={"Retry-After": str(60 - (now % 60))},
                )
    except Exception:
        # Do not silently downgrade to a per-instance limiter when Atlas is configured
        # or distributed limiting is required: fail closed for API traffic.
        return JSONResponse(status_code=503, content={"detail": "rate_limiter_unavailable"})
    return await call_next(request)


def token_ok(provided: str | None, expected: str) -> bool:
    return bool(provided and expected and hmac.compare_digest(
        hashlib.sha256(provided.encode()).digest(),
        hashlib.sha256(expected.encode()).digest()))


def caller(authorization: str | None = Header(default=None)):
    if os.getenv("MONGODB_URI", "").strip():
        raise HTTPException(status_code=404, detail="legacy_shared_token_api_disabled_on_mongodb")
    if not LEGACY_CALL_API_ENABLED:
        raise HTTPException(status_code=404, detail="legacy_call_api_disabled")
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token_ok(token, CALL_TOKEN):
        raise HTTPException(status_code=401, detail="unauthorized")
    return "api"


def device(authorization: str | None = Header(default=None)):
    if os.getenv("MONGODB_URI", "").strip():
        raise HTTPException(status_code=404, detail="legacy_shared_token_api_disabled_on_mongodb")
    if not LEGACY_DEVICE_API_ENABLED:
        raise HTTPException(status_code=404, detail="legacy_device_api_disabled")
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token_ok(token, DEVICE_TOKEN):
        raise HTTPException(status_code=401, detail="unauthorized")
    return "device-1"


def normalize_number(raw: str) -> str:
    value = re.sub(r"[\s().-]", "", raw)
    # Emergency short codes are not E.164 numbers. Reject them before parsing.
    emergency_digits = value.lstrip("+")
    if emergency_digits in {"112", "911", "999", "998", "997", "191", "194"}:
        raise HTTPException(status_code=422, detail="emergency_number")
    if value.startswith("00"):
        value = "+" + value[2:]
    elif re.fullmatch(r"7[0-9]{8}", value):
        value = "+967" + value
    if not re.fullmatch(r"\+[1-9][0-9]{7,14}", value):
        raise HTTPException(status_code=422, detail="invalid_phone_number")
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
    # Device outbox delivery is at-least-once. Treat replay of the current state as success
    # so a lost HTTP response or process death before local acknowledgement cannot wedge the queue.
    if row["state"] == state or row["state"] in TERMINAL:
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
        row = c.execute("SELECT * FROM calls WHERE id=? AND id NOT IN (SELECT call_id FROM call_tenant_assignments)", (call_id,)).fetchone()
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
        # The legacy device credential may only operate unassigned legacy calls.
        # Tenant calls are exclusively handled by authenticated_device.
        legacy_only = " AND id NOT IN (SELECT call_id FROM call_tenant_assignments)"
        c.execute("UPDATE calls SET state='failed',reason_code='queue_expired',updated_at=? WHERE state='queued' AND expires_at<?" + legacy_only, (now, now))
        c.execute("UPDATE calls SET state='failed',reason_code='cellular_answer_timeout',updated_at=? WHERE state='ringing_cellular' AND updated_at<?" + legacy_only, (now, now - CELL_RING_TIMEOUT))
        c.execute("UPDATE calls SET state='failed',reason_code='sip_answer_timeout',updated_at=? WHERE state='calling_sip' AND updated_at<?" + legacy_only, (now, now - SIP_RING_TIMEOUT))
        c.execute("UPDATE calls SET state='ended',reason_code='max_call_duration',updated_at=? WHERE state='bridged' AND updated_at<?" + legacy_only, (now, now - MAX_CALL))
        c.execute("UPDATE calls SET claimed_by=NULL,claimed_at=NULL WHERE state='queued' AND claimed_at<?" + legacy_only, (now - 30,))
        row = c.execute("SELECT * FROM calls WHERE state='queued' AND claimed_by IS NULL AND expires_at>=?" + legacy_only + " ORDER BY created_at LIMIT 1",
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
        row = c.execute("SELECT claimed_by FROM calls WHERE id=? AND id NOT IN (SELECT call_id FROM call_tenant_assignments)", (call_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="call_not_found")
        if row["claimed_by"] != device_id:
            raise HTTPException(status_code=403, detail="job_not_owned")
        updated = transition(c, call_id, body.state, body.reasonCode)
        result = safe_call(updated)
        c.commit()
        return result


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/health/database")
def database_health():
    """Probe the database actually used by FastAPI routes."""
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_store import health_snapshot
        snapshot = health_snapshot()
        if snapshot["status"] != "ok":
            raise HTTPException(status_code=503, detail=snapshot)
        return snapshot
    try:
        with db() as connection:
            connection.execute("SELECT 1").fetchone()
        return {"status": "ok", "backend": "sqlite"}
    except sqlite3.Error:
        raise HTTPException(
            status_code=503,
            detail={"status": "unavailable", "backend": "sqlite"},
        )


@app.get("/health/mongodb")
def mongodb_health():
    """Separate optional Atlas probe; Atlas is not yet the active call repository."""
    from mongodb_store import health_snapshot
    snapshot = health_snapshot()
    if snapshot["status"] != "ok":
        raise HTTPException(status_code=503, detail=snapshot)
    return snapshot


# --- Multi-tenant organization and device control plane ---------------------

class OrganizationBootstrap(BaseModel):
    name: str = Field(min_length=2, max_length=120)
    slug: str = Field(min_length=2, max_length=60, pattern=r"^[a-z0-9][a-z0-9-]+$")
    ownerUserId: str = Field(min_length=1, max_length=128)


class CreateBranch(BaseModel):
    name: str = Field(min_length=2, max_length=120)
    code: str | None = Field(default=None, min_length=1, max_length=32, pattern=r"^[A-Za-z0-9_-]+$")


class EnrollmentTokenRequest(BaseModel):
    label: str = Field(min_length=1, max_length=100)
    expiresInSeconds: int = Field(default=3600, ge=60, le=604800)
    maxUses: int = Field(default=1, ge=1, le=100)


class DeviceEnrollment(BaseModel):
    enrollmentToken: str = Field(min_length=32, max_length=256)
    externalDeviceId: str = Field(min_length=1, max_length=128)
    displayName: str = Field(min_length=1, max_length=120)
    branchName: str | None = Field(default=None, max_length=120)
    branchId: str | None = Field(default=None, max_length=64)
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


def write_audit_event(c: sqlite3.Connection, *, organization_id: str,
                      actor_user_id: str, action: str, target_type: str,
                      target_id: str, metadata: dict | None = None,
                      created_at: int | None = None) -> None:
    """Append a non-secret administrative audit event in the caller's transaction."""
    c.execute(
        """INSERT INTO admin_audit_events
           (id,organization_id,actor_user_id,action,target_type,target_id,metadata_json,created_at)
           VALUES(?,?,?,?,?,?,?,?)""",
        (str(uuid.uuid4()), organization_id, actor_user_id, action, target_type,
         target_id, json.dumps(metadata or {}, separators=(",", ":"), sort_keys=True),
         int(created_at if created_at is not None else time.time())),
    )


def organization_principal(authorization: str | None = Header(default=None),
                           x_organization_id: str = Header(alias="X-Organization-ID")):
    token = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not token:
        raise HTTPException(status_code=401, detail="unauthorized")
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import authenticate_organization
        row = authenticate_organization(_hash_secret(token), x_organization_id)
        if not row:
            raise HTTPException(status_code=403, detail="organization_access_denied")
        return row
    with db() as c:
        row = c.execute(
            """SELECT m.organization_id,m.user_id,m.role
               FROM organization_api_credentials k
               JOIN organization_memberships m ON m.organization_id=k.organization_id AND m.user_id=k.user_id
               JOIN organizations o ON o.id=m.organization_id
               WHERE k.token_hash=? AND k.revoked_at IS NULL
                 AND (k.expires_at IS NULL OR k.expires_at>?)
                 AND m.status='active' AND o.status='active' AND m.organization_id=?""",
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import authenticate_device
        row = authenticate_device(_hash_secret(token))
        if not row:
            raise HTTPException(status_code=401, detail="device_unauthorized")
        return row
    with db() as c:
        row = c.execute(
            """SELECT d.id,d.organization_id,d.status
               FROM device_credentials k JOIN devices d ON d.id=k.device_id
               JOIN organizations o ON o.id=d.organization_id
               WHERE k.token_hash=? AND k.revoked_at IS NULL
                 AND (k.expires_at IS NULL OR k.expires_at>?)
                 AND o.status='active'""",
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, bootstrap_organization as mongo_bootstrap
        try:
            org_id = mongo_bootstrap(slug=body.slug, name=body.name, owner_user_id=body.ownerUserId,
                            token_hash=_hash_secret(raw_token), token_prefix=raw_token[:8])
        except ControlConflict:
            raise HTTPException(status_code=409, detail="organization_slug_unavailable")
        return {"organizationId": org_id, "slug": body.slug, "apiToken": raw_token,
                "tokenWarning": "Shown once. Store securely; only its hash is retained."}
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
                   (id,organization_id,label,user_id,token_hash,token_prefix,scopes,created_at)
                   VALUES(?,?,?,?,?,?,'["admin"]',?)""",
                (credential_id, org_id, "Initial owner credential", body.ownerUserId, _hash_secret(raw_token), raw_token[:8], now),
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import create_enrollment_token as mongo_create_enrollment
        token_id = mongo_create_enrollment(
            organization_id=organization_id, token_hash=_hash_secret(raw_token), label=body.label,
            expires_at=now + body.expiresInSeconds, max_uses=body.maxUses,
            created_by=principal["user_id"],
        )
        return {"tokenId": token_id, "enrollmentToken": raw_token,
                "expiresAt": now + body.expiresInSeconds, "maxUses": body.maxUses,
                "tokenWarning": "Shown once. Share only with intended devices."}
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, ControlNotFound, enroll_device as mongo_enroll
        try:
            enrolled_id, organization_id = mongo_enroll(
                enrollment_token_hash=_hash_secret(body.enrollmentToken),
                external_device_id=body.externalDeviceId, display_name=body.displayName,
                branch_id=body.branchId, branch_name=body.branchName,
                app_id=body.appId, app_version=body.appVersion, android_version=body.androidVersion,
                manufacturer=body.manufacturer, model=body.model,
                device_token_hash=_hash_secret(raw_device_token), device_token_prefix=raw_device_token[:8],
            )
        except ControlNotFound as exc:
            detail = str(exc)
            raise HTTPException(status_code=404 if detail == "branch_not_found" else 401, detail=detail)
        except ControlConflict as exc:
            raise HTTPException(status_code=409, detail=str(exc))
        return {"deviceId": enrolled_id, "organizationId": organization_id,
                "deviceToken": raw_device_token,
                "tokenWarning": "Shown once. Store in Android Keystore-backed storage."}
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
        branch_name = body.branchName
        if body.branchId:
            branch = c.execute(
                "SELECT name FROM branches WHERE id=? AND organization_id=? AND status='active'",
                (body.branchId, enrollment["organization_id"]),
            ).fetchone()
            if not branch:
                c.rollback()
                raise HTTPException(status_code=404, detail="branch_not_found")
            branch_name = branch["name"]
        try:
            c.execute(
                """INSERT INTO devices
                   (id,organization_id,external_device_id,display_name,branch_name,branch_id,status,presence,
                    app_id,app_version,android_version,manufacturer,model,created_at,updated_at)
                   VALUES(?,?,?,?,?,?, 'active','offline',?,?,?,?,?,?,?)""",
                (device_id, enrollment["organization_id"], body.externalDeviceId, body.displayName,
                 branch_name, body.branchId, body.appId, body.appVersion, body.androidVersion,
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlNotFound, heartbeat as mongo_heartbeat
        try:
            server_time = mongo_heartbeat(device_id=device_id,
                                          organization_id=device_ctx["organization_id"],
                                          payload=body.model_dump(by_alias=True))
        except ControlNotFound:
            raise HTTPException(status_code=404, detail="device_not_found")
        return {"accepted": True, "serverTime": server_time, "presence": "online"}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        c.execute(
            """UPDATE devices SET last_seen_at=?,presence='online',battery_level=?,
               network_type=?,internet_available=?,sip_status=?,sim_status=?,agent_status=?,
               active_call_id=?,updated_at=? WHERE id=? AND organization_id=? AND status='active'""",
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import list_devices as mongo_list_devices
        rows = mongo_list_devices(principal["organization_id"])
        result = []
        for row in rows:
            item = dict(row)
            item["id"] = item.pop("_id")
            last_seen = item.get("last_seen_at")
            age = now - last_seen if last_seen is not None else None
            item["presence"] = ("offline" if item["status"] != "active" or age is None
                                or age > PRESENCE_UNSTABLE_SECONDS else
                                "unstable" if age > PRESENCE_ONLINE_SECONDS else "online")
            item["lastSeenAgeSeconds"] = age
            result.append(item)
        return {"devices": result, "count": len(result)}
    with db() as c:
        rows = c.execute(
            """SELECT id,external_device_id,display_name,branch_name,branch_id,status,last_seen_at,
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlNotFound, list_device_events
        try:
            rows = list_device_events(organization_id=principal["organization_id"],
                                      device_id=device_id, limit=limit)
        except ControlNotFound:
            raise HTTPException(status_code=404, detail="device_not_found")
        return {"events": rows}
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import append_device_event
        append_device_event(organization_id=device_ctx["organization_id"],
                            device_id=device_ctx["device_id"], event_type=event_type,
                            severity=severity, event_code=event_code,
                            occurred_at=int(body.get("occurredAt", now)))
        return {"accepted": True}
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
    raw_token = secrets.token_urlsafe(40)
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, add_member as mongo_add_member
        try:
            mongo_add_member(organization_id=organization_id, user_id=body.userId, role=body.role,
                             token_hash=_hash_secret(raw_token), token_prefix=raw_token[:8],
                             actor_user_id=principal["user_id"])
        except ControlConflict:
            raise HTTPException(status_code=409, detail="membership_already_exists")
        return {"organizationId": organization_id, "userId": body.userId, "role": body.role,
                "status": "active", "apiToken": raw_token,
                "tokenWarning": "Shown once. Store securely; only its hash is retained."}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        existing = c.execute(
            "SELECT role,status FROM organization_memberships WHERE organization_id=? AND user_id=?",
            (organization_id, body.userId),
        ).fetchone()
        if existing:
            c.rollback()
            raise HTTPException(status_code=409, detail="membership_already_exists")
        c.execute(
            """INSERT INTO organization_memberships
               (organization_id,user_id,role,status,created_at,updated_at)
               VALUES(?,?,?,'active',?,?)""",
            (organization_id, body.userId, body.role, now, now),
        )
        c.execute(
            """INSERT INTO organization_api_credentials
               (id,organization_id,label,user_id,token_hash,token_prefix,scopes,created_at)
               VALUES(?,?,?,?,?,?, '[]',?)""",
            (str(uuid.uuid4()), organization_id, f"{body.role} member credential",
             body.userId, _hash_secret(raw_token), raw_token[:8], now),
        )
        write_audit_event(c, organization_id=organization_id, actor_user_id=principal["user_id"],
                          action="member.created", target_type="membership", target_id=body.userId,
                          metadata={"role": body.role}, created_at=now)
        c.commit()
    return {"organizationId": organization_id, "userId": body.userId, "role": body.role,
            "status": "active", "apiToken": raw_token,
            "tokenWarning": "Shown once. Store securely; only its hash is retained."}


@app.post("/v1/organizations/{organization_id}/members/{user_id}/transfer-ownership")
def transfer_organization_ownership(organization_id: str, user_id: str,
                                    principal=Depends(require_org_admin)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    if principal["role"] != "owner":
        raise HTTPException(status_code=403, detail="owner_role_required")
    if user_id == principal["user_id"]:
        raise HTTPException(status_code=409, detail="already_owner")
    now = int(time.time())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, ControlNotFound, transfer_ownership as mongo_transfer
        try:
            mongo_transfer(organization_id=organization_id, previous_owner=principal["user_id"],
                           new_owner=user_id, actor_user_id=principal["user_id"])
        except ControlNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc))
        except ControlConflict as exc:
            raise HTTPException(status_code=409, detail=str(exc))
        return {"organizationId": organization_id, "previousOwnerUserId": principal["user_id"],
                "newOwnerUserId": user_id, "status": "transferred"}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        target = c.execute(
            """SELECT role,status FROM organization_memberships
               WHERE organization_id=? AND user_id=?""",
            (organization_id, user_id),
        ).fetchone()
        if not target or target["status"] != "active":
            c.rollback()
            raise HTTPException(status_code=404, detail="active_member_not_found")
        if target["role"] == "owner":
            c.rollback()
            raise HTTPException(status_code=409, detail="target_already_owner")
        c.execute(
            """UPDATE organization_memberships SET role='admin',updated_at=?
               WHERE organization_id=? AND user_id=? AND role='owner' AND status='active'""",
            (now, organization_id, principal["user_id"]),
        )
        if c.execute("SELECT changes()").fetchone()[0] != 1:
            c.rollback()
            raise HTTPException(status_code=409, detail="owner_membership_changed")
        c.execute(
            """UPDATE organization_memberships SET role='owner',updated_at=?
               WHERE organization_id=? AND user_id=?""",
            (now, organization_id, user_id),
        )
        write_audit_event(
            c, organization_id=organization_id, actor_user_id=principal["user_id"],
            action="organization.ownership_transferred", target_type="membership",
            target_id=user_id, metadata={"previous_owner_user_id": principal["user_id"]},
            created_at=now,
        )
        c.commit()
    return {"organizationId": organization_id, "previousOwnerUserId": principal["user_id"],
            "newOwnerUserId": user_id, "status": "transferred"}


@app.post("/v1/organizations/{organization_id}/members/{user_id}/revoke")
def revoke_organization_member(organization_id: str, user_id: str,
                               principal=Depends(require_org_admin)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    now = int(time.time())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, ControlNotFound, revoke_member as mongo_revoke_member
        try:
            mongo_revoke_member(organization_id=organization_id, user_id=user_id,
                                actor_user_id=principal["user_id"], actor_role=principal["role"])
        except ControlNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc))
        except PermissionError as exc:
            raise HTTPException(status_code=403, detail=str(exc))
        except ControlConflict as exc:
            raise HTTPException(status_code=409, detail=str(exc))
        return {"organizationId": organization_id, "userId": user_id, "status": "revoked"}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        member = c.execute(
            "SELECT role,status FROM organization_memberships WHERE organization_id=? AND user_id=?",
            (organization_id, user_id),
        ).fetchone()
        if not member:
            c.rollback()
            raise HTTPException(status_code=404, detail="member_not_found")
        if member["role"] == "owner":
            c.rollback()
            raise HTTPException(status_code=409, detail="owner_transfer_required")
        if principal["role"] != "owner" and member["role"] == "admin":
            c.rollback()
            raise HTTPException(status_code=403, detail="owner_role_required")
        c.execute(
            "UPDATE organization_memberships SET status='suspended',updated_at=? WHERE organization_id=? AND user_id=?",
            (now, organization_id, user_id),
        )
        c.execute(
            "UPDATE organization_api_credentials SET revoked_at=? WHERE organization_id=? AND user_id=? AND revoked_at IS NULL",
            (now, organization_id, user_id),
        )
        write_audit_event(c, organization_id=organization_id, actor_user_id=principal["user_id"],
                          action="member.revoked", target_type="membership", target_id=user_id,
                          metadata={"role": member["role"]}, created_at=now)
        c.commit()
    return {"organizationId": organization_id, "userId": user_id, "status": "revoked"}


@app.post("/v1/organizations/{organization_id}/members/{user_id}/rotate-credential")
def rotate_member_credential(organization_id: str, user_id: str,
                             principal=Depends(organization_principal)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    if principal["user_id"] != user_id and principal["role"] not in {"owner", "admin"}:
        raise HTTPException(status_code=403, detail="insufficient_role")
    raw_token = secrets.token_urlsafe(40)
    now = int(time.time())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlNotFound, rotate_member_credential as mongo_rotate
        try:
            mongo_rotate(organization_id=organization_id, user_id=user_id,
                         actor_user_id=principal["user_id"], actor_role=principal["role"],
                         token_hash=_hash_secret(raw_token), token_prefix=raw_token[:8])
        except ControlNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc))
        except PermissionError as exc:
            raise HTTPException(status_code=403, detail=str(exc))
        return {"organizationId": organization_id, "userId": user_id,
                "apiToken": raw_token, "tokenWarning": "Shown once. Previous credentials were revoked."}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        member = c.execute(
            "SELECT role,status FROM organization_memberships WHERE organization_id=? AND user_id=?",
            (organization_id, user_id),
        ).fetchone()
        if not member or member["status"] != "active":
            c.rollback()
            raise HTTPException(status_code=404, detail="active_member_not_found")
        if principal["role"] != "owner" and member["role"] in {"owner", "admin"}:
            c.rollback()
            raise HTTPException(status_code=403, detail="owner_role_required")
        c.execute(
            "UPDATE organization_api_credentials SET revoked_at=? WHERE organization_id=? AND user_id=? AND revoked_at IS NULL",
            (now, organization_id, user_id),
        )
        c.execute(
            """INSERT INTO organization_api_credentials
               (id,organization_id,label,user_id,token_hash,token_prefix,scopes,created_at)
               VALUES(?,?,?,?,?,?, '[]',?)""",
            (str(uuid.uuid4()), organization_id, "Rotated member credential",
             user_id, _hash_secret(raw_token), raw_token[:8], now),
        )
        write_audit_event(c, organization_id=organization_id, actor_user_id=principal["user_id"],
                          action="member.credential_rotated", target_type="credential_set", target_id=user_id,
                          created_at=now)
        c.commit()
    return {"organizationId": organization_id, "userId": user_id,
            "apiToken": raw_token, "tokenWarning": "Shown once. Previous credentials were revoked."}


@app.post("/v1/admin/devices/{device_id}/revoke")
def revoke_device(device_id: str, principal=Depends(require_org_admin)):
    now = int(time.time())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlNotFound, revoke_device as mongo_revoke_device
        try:
            mongo_revoke_device(organization_id=principal["organization_id"], device_id=device_id,
                                actor_user_id=principal["user_id"])
        except ControlNotFound:
            raise HTTPException(status_code=404, detail="device_not_found")
        return {"deviceId": device_id, "status": "revoked"}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
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
        write_audit_event(c, organization_id=principal["organization_id"],
                          actor_user_id=principal["user_id"], action="device.revoked",
                          target_type="device", target_id=device_id, created_at=now)
        c.commit()
    return {"deviceId": device_id, "status": "revoked"}


@app.post("/v1/admin/device-commands", status_code=201)
def create_device_command(body: DeviceCommandRequest, principal=Depends(require_org_admin)):
    now = int(time.time())
    command_id = str(uuid.uuid4())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlNotFound, create_command as mongo_create_command
        try:
            command_id, expires_at = mongo_create_command(
                organization_id=principal["organization_id"], device_id=body.deviceId,
                command_type=body.commandType, expires_in_seconds=body.expiresInSeconds,
                actor_user_id=principal["user_id"])
        except ControlNotFound:
            raise HTTPException(status_code=404, detail="active_device_not_found")
        return {"commandId": command_id, "status": "pending", "expiresAt": expires_at}
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
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
        write_audit_event(c, organization_id=principal["organization_id"],
                          actor_user_id=principal["user_id"], action="device.command_created",
                          target_type="device_command", target_id=command_id,
                          metadata={"command_type": body.commandType, "device_id": body.deviceId},
                          created_at=now)
        c.commit()
    return {"commandId": command_id, "status": "pending", "expiresAt": now + body.expiresInSeconds}


@app.get("/v1/device/commands/next")
def next_device_command(wait: int = Query(default=20, ge=0, le=25),
                        device_ctx=Depends(authenticated_device)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import claim_command as mongo_claim_command
        deadline = time.monotonic() + wait
        while True:
            command = mongo_claim_command(device_id=device_ctx["device_id"],
                                          organization_id=device_ctx["organization_id"])
            if command is not None or time.monotonic() >= deadline:
                return {"command": command}
            time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, ControlNotFound, update_command_result as mongo_update_command
        try:
            mongo_update_command(command_id=command_id, device_id=device_ctx["device_id"],
                                 organization_id=device_ctx["organization_id"], status=body.status,
                                 result_code=body.resultCode, result_summary=body.resultSummary)
        except ControlNotFound:
            raise HTTPException(status_code=404, detail="command_not_found")
        except ControlConflict:
            raise HTTPException(status_code=409, detail="command_not_active")
        return {"commandId": command_id, "status": body.status}
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
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_store import collection
        rows = list(collection("calls").find(
            {"organization_id": principal["organization_id"]},
            {"_id": 1, "state": 1, "created_at": 1, "updated_at": 1,
             "reason_code": 1, "claimed_by": 1},
        ).sort("created_at", -1).limit(limit))
        calls = [{"id": row["_id"], "state": row["state"], "created_at": row["created_at"],
                  "updated_at": row["updated_at"], "reason_code": row.get("reason_code"),
                  "device_id": row.get("claimed_by")} for row in rows]
        return {"calls": calls, "count": len(calls)}
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


@app.post("/v1/organizations/{organization_id}/calls", status_code=202)
def create_organization_call(organization_id: str, body: CreateCall,
                             idempotency_key: str = Header(alias="Idempotency-Key", min_length=16, max_length=128),
                             principal=Depends(organization_principal)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    if principal["role"] not in {"owner", "admin", "operator"}:
        raise HTTPException(status_code=403, detail="insufficient_role")
    number = normalize_number(body.phoneNumber)
    tenant_principal = f"org:{organization_id}"
    now = int(time.time())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_calls import DeviceBusy, IdempotencyConflict, MongoCallRepository
        repository = MongoCallRepository(__import__("mongodb_store").collection("calls"))
        try:
            result = repository.create_call(
                organization_id=organization_id, principal=tenant_principal,
                idempotency_key=idempotency_key, phone_number=number,
                now=now, queue_ttl=QUEUE_TTL,
            )
        except IdempotencyConflict:
            raise HTTPException(status_code=409, detail="idempotency_key_reused")
        except DeviceBusy:
            raise HTTPException(status_code=409, detail="device_busy")
        return result
    with db() as c:
        c.execute("BEGIN IMMEDIATE")
        old = c.execute(
            "SELECT * FROM calls WHERE principal=? AND idempotency_key=?",
            (tenant_principal, idempotency_key),
        ).fetchone()
        if old:
            if old["phone_number"] != number:
                c.rollback()
                raise HTTPException(status_code=409, detail="idempotency_key_reused")
            c.commit()
            return safe_call(old)
        c.execute("DELETE FROM requests WHERE created_at<?", (now - 60,))
        count = c.execute(
            "SELECT COUNT(*) FROM requests WHERE principal=?", (tenant_principal,)
        ).fetchone()[0]
        if count >= RATE_LIMIT:
            c.rollback()
            raise HTTPException(status_code=429, detail="rate_limit_exceeded")
        c.execute("INSERT INTO requests(principal,created_at) VALUES(?,?)", (tenant_principal, now))
        call_id = str(uuid.uuid4())
        expires = now + QUEUE_TTL
        events = json.dumps([{"state": "queued", "at": now, "atMs": time.time_ns() // 1_000_000}])
        c.execute(
            """INSERT INTO calls(id,idempotency_key,principal,phone_number,state,created_at,
               updated_at,expires_at,events) VALUES(?,?,?,?,?,?,?,?,?)""",
            (call_id, idempotency_key, tenant_principal, number, "queued", now, now, expires, events),
        )
        c.execute(
            """INSERT INTO call_tenant_assignments(call_id,organization_id,created_at)
               VALUES(?,?,?)""",
            (call_id, organization_id, now),
        )
        c.commit()
    return {"id": call_id, "state": "queued", "createdAt": now, "updatedAt": now, "expiresAt": expires}


@app.get("/v1/device/call-jobs/next")
def next_organization_call_job(wait: int = Query(default=20, ge=0, le=25),
                               device_ctx=Depends(authenticated_device)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_calls import MongoCallRepository
        repository = MongoCallRepository(__import__("mongodb_store").collection("calls"))
        deadline = time.monotonic() + wait
        while True:
            result = repository.claim_next(
                organization_id=device_ctx["organization_id"], device_id=device_ctx["device_id"],
                now=int(time.time()), queue_ttl=QUEUE_TTL,
                cellular_ring_timeout=CELL_RING_TIMEOUT, sip_ring_timeout=SIP_RING_TIMEOUT,
                max_call_seconds=MAX_CALL,
            )
            if result["job"] is not None or time.monotonic() >= deadline:
                return result
            time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))
    deadline = time.monotonic() + wait
    while True:
        now = int(time.time())
        with db() as c:
            c.execute("BEGIN IMMEDIATE")
            c.execute(
                """UPDATE calls SET state='failed',reason_code='queue_expired',updated_at=?
                   WHERE id IN (SELECT call_id FROM call_tenant_assignments WHERE organization_id=?)
                     AND state='queued' AND expires_at<?""",
                (now, device_ctx["organization_id"], now),
            )
            c.execute(
                """UPDATE calls SET claimed_by=NULL,claimed_at=NULL
                   WHERE state='queued' AND claimed_at<? AND id IN
                     (SELECT call_id FROM call_tenant_assignments WHERE organization_id=?)""",
                (now - 30, device_ctx["organization_id"]),
            )
            active_for_device = c.execute(
                """SELECT id FROM calls WHERE claimed_by=?
                   AND state NOT IN ('failed','ended') LIMIT 1""",
                (device_ctx["device_id"],),
            ).fetchone()
            row = None if active_for_device else c.execute(
                """SELECT calls.* FROM calls
                   JOIN call_tenant_assignments a ON a.call_id=calls.id
                   WHERE a.organization_id=? AND calls.state='queued'
                     AND calls.claimed_by IS NULL AND calls.expires_at>=?
                   ORDER BY calls.created_at LIMIT 1""",
                (device_ctx["organization_id"], now),
            ).fetchone()
            if row:
                c.execute(
                    """UPDATE calls SET claimed_by=?,claimed_at=? WHERE id=?
                       AND state='queued' AND claimed_by IS NULL""",
                    (device_ctx["device_id"], now, row["id"]),
                )
                c.execute(
                    "UPDATE call_tenant_assignments SET device_id=? WHERE call_id=?",
                    (device_ctx["device_id"], row["id"]),
                )
                c.commit()
                return {"job": {"id": row["id"], "phoneNumber": row["phone_number"],
                                "expiresAt": row["expires_at"]}}
            c.commit()
        if time.monotonic() >= deadline:
            return {"job": None}
        time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))


@app.post("/v1/device/tenant-calls/{call_id}/state")
def update_organization_call_state(call_id: str, body: CallUpdate,
                                   device_ctx=Depends(authenticated_device)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_calls import CallNotFound, InvalidTransition, JobNotOwned, MongoCallRepository
        repository = MongoCallRepository(__import__("mongodb_store").collection("calls"))
        try:
            return repository.transition(
                organization_id=device_ctx["organization_id"], call_id=call_id,
                device_id=device_ctx["device_id"], state=body.state, reason_code=body.reasonCode,
            )
        except CallNotFound:
            raise HTTPException(status_code=404, detail="call_not_found")
        except JobNotOwned:
            raise HTTPException(status_code=403, detail="job_not_owned")
        except InvalidTransition:
            raise HTTPException(status_code=409, detail="invalid_state_transition")
    with db() as c:
        # Keep ownership validation and state transition in one write transaction.
        # Otherwise a concurrent lease reclaim could race between the SELECT and UPDATE.
        c.execute("BEGIN IMMEDIATE")
        row = c.execute(
            """SELECT calls.claimed_by FROM calls
               JOIN call_tenant_assignments a ON a.call_id=calls.id
               WHERE calls.id=? AND a.organization_id=? AND a.device_id=?""",
            (call_id, device_ctx["organization_id"], device_ctx["device_id"]),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="call_not_found")
        if row["claimed_by"] != device_ctx["device_id"]:
            raise HTTPException(status_code=403, detail="job_not_owned")
        updated = transition(c, call_id, body.state, body.reasonCode)
        result = safe_call(updated)
        c.commit()
        return result


@app.get("/v1/admin/calls/{call_id}")
def get_organization_call(call_id: str, principal=Depends(organization_principal)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_store import collection
        row = collection("calls").find_one(
            {"_id": call_id, "organization_id": principal["organization_id"]},
            {"_id": 1, "state": 1, "created_at": 1, "updated_at": 1,
             "reason_code": 1, "claimed_by": 1},
        )
        if not row:
            raise HTTPException(status_code=404, detail="call_not_found")
        return {"id": row["_id"], "state": row["state"], "created_at": row["created_at"],
                "updated_at": row["updated_at"], "reason_code": row.get("reason_code"),
                "device_id": row.get("claimed_by")}
    with db() as c:
        row = c.execute(
            """SELECT calls.id,calls.state,calls.created_at,calls.updated_at,calls.reason_code,
                      a.device_id
               FROM calls JOIN call_tenant_assignments a ON a.call_id=calls.id
               WHERE calls.id=? AND a.organization_id=?""",
            (call_id, principal["organization_id"]),
        ).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="call_not_found")
    return dict(row)


@app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_dashboard():
    page = Path(__file__).parent / "admin" / "index.html"
    if not page.exists():
        raise HTTPException(status_code=404, detail="admin_dashboard_not_found")
    return HTMLResponse(page.read_text(encoding="utf-8"))


@app.post("/v1/organizations/{organization_id}/branches", status_code=201)
def create_organization_branch(organization_id: str, body: CreateBranch,
                               principal=Depends(require_org_admin)):
    if principal["organization_id"] != organization_id:
        raise HTTPException(status_code=404, detail="organization_not_found")
    now = int(time.time())
    branch_id = str(uuid.uuid4())
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import ControlConflict, create_branch as mongo_create_branch
        try:
            branch_id = mongo_create_branch(organization_id=organization_id,
                                            name=body.name, code=body.code)
        except ControlConflict:
            raise HTTPException(status_code=409, detail="branch_code_already_exists")
        return {"branchId": branch_id, "name": body.name, "code": body.code, "status": "active"}
    try:
        with db() as c:
            c.execute(
                """INSERT INTO branches(id,organization_id,name,code,created_at,updated_at)
                   VALUES(?,?,?,?,?,?)""",
                (branch_id, organization_id, body.name, body.code, now, now),
            )
    except sqlite3.IntegrityError:
        raise HTTPException(status_code=409, detail="branch_code_already_exists")
    return {"branchId": branch_id, "name": body.name, "code": body.code, "status": "active"}


@app.get("/v1/admin/branches")
def list_organization_branches(principal=Depends(organization_principal)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import list_branches as mongo_list_branches
        rows = mongo_list_branches(principal["organization_id"])
        branches = [{key: value for key, value in row.items() if key != "_id"} | {"id": row["_id"]} for row in rows]
        return {"branches": branches, "count": len(branches)}
    with db() as c:
        rows = c.execute(
            """SELECT id,name,code,status,created_at
               FROM branches WHERE organization_id=? ORDER BY name""",
            (principal["organization_id"],),
        ).fetchall()
    return {"branches": [dict(row) for row in rows], "count": len(rows)}


@app.get("/v1/admin/metrics/latency")
def organization_latency_metrics(principal=Depends(organization_principal)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import latency_metrics
        return latency_metrics(organization_id=principal["organization_id"])
    with db() as c:
        rows = c.execute(
            """SELECT calls.events FROM calls
               JOIN call_tenant_assignments a ON a.call_id=calls.id
               WHERE a.organization_id=?""",
            (principal["organization_id"],),
        ).fetchall()
    samples = {"queue_to_cellular_ringing": [], "queue_to_bridged": [],
               "cellular_answer_to_sip": []}
    for row in rows:
        try:
            events = json.loads(row["events"])
            times = {event.get("state"): event.get("atMs", int(event.get("at", 0)) * 1000)
                     for event in events if event.get("state")}
            queued, ringing = times.get("queued"), times.get("ringing_cellular")
            bridged, answered, sip = (times.get("bridged"), times.get("cellular_answered"),
                                       times.get("calling_sip"))
            if queued is not None and ringing is not None and ringing >= queued:
                samples["queue_to_cellular_ringing"].append(ringing - queued)
            if queued is not None and bridged is not None and bridged >= queued:
                samples["queue_to_bridged"].append(bridged - queued)
            if answered is not None and sip is not None and sip >= answered:
                samples["cellular_answer_to_sip"].append(sip - answered)
        except (TypeError, ValueError, json.JSONDecodeError):
            continue

    def summarize(values):
        ordered = sorted(values)
        if not ordered:
            return {"samples": 0, "averageMs": None, "p95Ms": None}
        rank = max(0, int(0.95 * len(ordered) + 0.999999) - 1)
        return {"samples": len(ordered), "averageMs": round(sum(ordered) / len(ordered), 2),
                "p95Ms": ordered[min(rank, len(ordered) - 1)]}
    return {name: summarize(values) for name, values in samples.items()}


@app.get("/v1/admin/audit")
def organization_audit_log(limit: int = Query(default=50, ge=1, le=200),
                            principal=Depends(require_org_admin)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import list_audit
        rows = list_audit(principal["organization_id"], limit)
        events = [{key: value for key, value in row.items() if key != "_id"} | {"id": row["_id"]} for row in rows]
        return {"events": events, "count": len(events)}
    with db() as c:
        rows = c.execute(
            """SELECT id,actor_user_id,action,target_type,target_id,metadata_json,created_at
               FROM admin_audit_events WHERE organization_id=?
               ORDER BY created_at DESC LIMIT ?""",
            (principal["organization_id"], limit),
        ).fetchall()
    return {
        "events": [
            {**dict(row), "metadata": json.loads(row["metadata_json"])}
            for row in rows
        ],
        "count": len(rows),
    }


@app.get("/v1/admin/activity")
def organization_activity(limit: int = Query(default=50, ge=1, le=200),
                          principal=Depends(organization_principal)):
    if os.getenv("MONGODB_URI", "").strip():
        from mongodb_control import list_activity
        rows = list_activity(principal["organization_id"], limit)
        return {"events": rows, "count": len(rows)}
    with db() as c:
        rows = c.execute(
            """SELECT e.event_type,e.severity,e.event_code,e.occurred_at,e.received_at,
                      d.id AS device_id,d.display_name
               FROM device_events e JOIN devices d ON d.id=e.device_id
               WHERE e.organization_id=? AND d.organization_id=?
               ORDER BY e.received_at DESC LIMIT ?""",
            (principal["organization_id"], principal["organization_id"], limit),
        ).fetchall()
    return {"events": [dict(row) for row in rows], "count": len(rows)}
