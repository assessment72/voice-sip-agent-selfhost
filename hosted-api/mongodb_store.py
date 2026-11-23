"""MongoDB persistence foundation for the Hosted Call API.

This module deliberately does not replace the SQLite repository by itself.
Route migration must use these collections and atomic operations, then remove
SQLite startup/migrations in a separately tested change.
"""
from __future__ import annotations

import os
from functools import lru_cache
from typing import Any, Iterator

from pymongo import ASCENDING, DESCENDING, IndexModel, MongoClient, ReturnDocument
from pymongo.collection import Collection
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError, PyMongoError


class MongoConfigurationError(RuntimeError):
    pass


@lru_cache(maxsize=1)
def get_client() -> MongoClient:
    """Reuse one small pool per warm Vercel function instance."""
    uri = os.getenv("MONGODB_URI", "").strip()
    if not uri:
        raise MongoConfigurationError("MONGODB_URI is required when MONGODB is enabled")
    # Serverless instances are short-lived and independently pooled. Keep the
    # pool conservative until measured concurrency justifies increasing it.
    return MongoClient(
        uri,
        appname="hosted-call-api",
        maxPoolSize=5,
        minPoolSize=0,
        maxIdleTimeMS=20_000,
        connectTimeoutMS=10_000,
        serverSelectionTimeoutMS=10_000,
        waitQueueTimeoutMS=5_000,
        retryWrites=True,
    )


def get_database() -> Database:
    name = os.getenv("MONGODB_DATABASE", "").strip()
    if not name:
        raise MongoConfigurationError("MONGODB_DATABASE is required when MONGODB is enabled")
    return get_client()[name]


def collection(name: str) -> Collection:
    return get_database()[name]


def ensure_indexes(database: Database | None = None) -> None:
    """Idempotent index creation; safe to call on each cold start."""
    db = database or get_database()
    definitions: dict[str, list[IndexModel]] = {
        "calls": [
            IndexModel([("organization_id", ASCENDING), ("principal", ASCENDING),
                        ("idempotency_key", ASCENDING)],
                       unique=True, name="uq_org_principal_idempotency"),
            IndexModel([("organization_id", ASCENDING), ("active_slot", ASCENDING)],
                       unique=True,
                       partialFilterExpression={"active_slot": {"$exists": True}},
                       name="uq_org_single_active_call_slot"),
            IndexModel([("organization_id", ASCENDING), ("state", ASCENDING),
                        ("created_at", ASCENDING)],
                       name="ix_org_call_queue"),
            IndexModel([("organization_id", ASCENDING), ("claimed_by", ASCENDING),
                        ("state", ASCENDING)],
                       name="ix_org_call_claim_owner"),
            IndexModel([("organization_id", ASCENDING), ("_id", ASCENDING),
                        ("claimed_by", ASCENDING)],
                       name="ix_org_call_device_owner"),
            IndexModel([("organization_id", ASCENDING), ("created_at", DESCENDING)],
                       name="ix_call_org_created"),
        ],
        "rate_limit_windows": [
            IndexModel([("principal", ASCENDING), ("window_start", ASCENDING)],
                       unique=True, name="uq_rate_principal_window"),
            IndexModel([("expires_at", ASCENDING)], expireAfterSeconds=0,
                       name="ttl_rate_window"),
        ],
        "organizations": [
            IndexModel([("slug", ASCENDING)], unique=True, name="uq_org_slug"),
        ],
        "memberships": [
            IndexModel([("organization_id", ASCENDING), ("user_id", ASCENDING)],
                       unique=True, name="uq_membership_org_user"),
            IndexModel([("user_id", ASCENDING), ("status", ASCENDING)],
                       name="ix_membership_user_status"),
        ],
        "api_credentials": [
            IndexModel([("token_hash", ASCENDING)], unique=True, name="uq_api_token_hash"),
            IndexModel([("organization_id", ASCENDING), ("revoked_at", ASCENDING)],
                       name="ix_api_credential_org"),
        ],
        "branches": [
            IndexModel([("organization_id", ASCENDING), ("code", ASCENDING)],
                       unique=True, partialFilterExpression={"code": {"$type": "string"}},
                       name="uq_branch_org_code"),
            IndexModel([("organization_id", ASCENDING), ("status", ASCENDING)],
                       name="ix_branch_org_status"),
        ],
        "devices": [
            IndexModel([("organization_id", ASCENDING), ("external_device_id", ASCENDING)],
                       unique=True, name="uq_device_org_external"),
            IndexModel([("organization_id", ASCENDING), ("presence", ASCENDING), ("status", ASCENDING)],
                       name="ix_device_org_presence"),
        ],
        "enrollment_tokens": [
            IndexModel([("token_hash", ASCENDING)], unique=True, name="uq_enrollment_hash"),
            IndexModel([("organization_id", ASCENDING), ("expires_at", ASCENDING)],
                       name="ix_enrollment_org_expiry"),
        ],
        "device_credentials": [
            IndexModel([("token_hash", ASCENDING)], unique=True, name="uq_device_token_hash"),
            IndexModel([("device_id", ASCENDING), ("revoked_at", ASCENDING)],
                       name="ix_device_credential_device"),
        ],
        "heartbeats": [
            IndexModel([("device_id", ASCENDING), ("received_at", DESCENDING)],
                       name="ix_heartbeat_device_time"),
        ],
        "device_events": [
            IndexModel([("organization_id", ASCENDING), ("received_at", DESCENDING)],
                       name="ix_event_org_time"),
            IndexModel([("device_id", ASCENDING), ("received_at", DESCENDING)],
                       name="ix_event_device_time"),
        ],
        "device_configs": [
            IndexModel([("device_id", ASCENDING)], unique=True, name="uq_device_config"),
        ],
        "device_commands": [
            IndexModel([("device_id", ASCENDING), ("status", ASCENDING), ("created_at", ASCENDING)],
                       name="ix_command_delivery_queue"),
            IndexModel([("expires_at", ASCENDING)], expireAfterSeconds=0,
                       name="ttl_command_expiry"),
        ],
    }
    for name, indexes in definitions.items():
        db[name].create_indexes(indexes)


def consume_rate_limit(*, key_hash: str, window_start: int, limit: int,
                        expires_at: int) -> tuple[bool, int]:
    """Atomically consume one shared fixed-window token across serverless instances."""
    bucket = collection("rate_limit_windows")
    identity = {"principal": key_hash, "window_start": window_start}
    try:
        row = bucket.find_one_and_update(
            identity,
            {"$inc": {"count": 1}, "$setOnInsert": {"expires_at": expires_at}},
            upsert=True,
            return_document=ReturnDocument.AFTER,
        )
    except DuplicateKeyError:
        # Concurrent first writes may race on the unique (principal, window) index.
        row = bucket.find_one_and_update(
            identity, {"$inc": {"count": 1}},
            upsert=False, return_document=ReturnDocument.AFTER,
        )
    count = int((row or {}).get("count", 0))
    return count <= limit, count


def ping() -> bool:
    get_database().command("ping")
    return True


def with_transaction(callback):
    """Run a multi-document operation atomically on a transaction-capable Atlas topology."""
    client = get_client()
    with client.start_session() as session:
        return session.with_transaction(lambda s: callback(s))


def health_snapshot() -> dict[str, Any]:
    try:
        get_database().command("ping")
        return {"status": "ok", "backend": "mongodb"}
    except (MongoConfigurationError, PyMongoError):
        return {"status": "unavailable", "backend": "mongodb"}
