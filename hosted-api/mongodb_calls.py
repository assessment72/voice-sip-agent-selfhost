"""Organization-scoped atomic MongoDB repository for hosted call lifecycle.

This repository is intentionally not selected by FastAPI routes yet. It
provides independently testable primitives for the staged SQLite migration.
"""
from __future__ import annotations

import time
import uuid
from typing import Any

from pymongo import ASCENDING, DESCENDING, IndexModel, ReturnDocument
from pymongo.collection import Collection
from pymongo.errors import DuplicateKeyError


TERMINAL = {"failed", "ended"}
ALLOWED = {
    "queued": {"ringing_cellular", "failed"},
    "ringing_cellular": {"cellular_answered", "failed", "ended"},
    "cellular_answered": {"calling_sip", "failed", "ended"},
    "calling_sip": {"bridged", "failed", "ended"},
    "bridged": {"ended", "failed"},
}


class IdempotencyConflict(ValueError):
    pass


class DeviceBusy(RuntimeError):
    pass


class CallNotFound(LookupError):
    pass


class JobNotOwned(PermissionError):
    pass


class InvalidTransition(ValueError):
    pass


class MongoCallRepository:
    """Call lifecycle operations with mandatory organization/device scope."""

    def __init__(self, calls: Collection):
        self.calls = calls

    def ensure_indexes(self) -> None:
        self.calls.create_indexes([
            IndexModel(
                [("organization_id", ASCENDING), ("principal", ASCENDING),
                 ("idempotency_key", ASCENDING)],
                unique=True, name="uq_org_principal_idempotency",
            ),
            # One active call per organization. Terminal transitions remove
            # this field, allowing the next call for that organization.
            IndexModel(
                [("organization_id", ASCENDING), ("active_slot", ASCENDING)],
                unique=True,
                partialFilterExpression={"active_slot": {"$exists": True}},
                name="uq_org_single_active_call_slot",
            ),
            IndexModel(
                [("organization_id", ASCENDING), ("state", ASCENDING),
                 ("created_at", ASCENDING)],
                name="ix_org_call_queue",
            ),
            IndexModel(
                [("organization_id", ASCENDING), ("claimed_by", ASCENDING),
                 ("state", ASCENDING)],
                name="ix_org_call_claim_owner",
            ),
            IndexModel(
                [("organization_id", ASCENDING), ("_id", ASCENDING),
                 ("claimed_by", ASCENDING)],
                name="ix_org_call_device_owner",
            ),
            IndexModel(
                [("organization_id", ASCENDING), ("created_at", DESCENDING)],
                name="ix_call_org_created",
            ),
        ])

    @staticmethod
    def _safe(doc: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": doc["_id"],
            "state": doc["state"],
            "createdAt": doc["created_at"],
            "updatedAt": doc["updated_at"],
            "expiresAt": doc["expires_at"],
            "reasonCode": doc.get("reason_code"),
        }

    def create_call(
        self, *, organization_id: str, principal: str, idempotency_key: str,
        phone_number: str, now: int, queue_ttl: int,
    ) -> dict[str, Any]:
        existing = self.calls.find_one({
            "organization_id": organization_id,
            "principal": principal,
            "idempotency_key": idempotency_key,
        })
        if existing:
            if existing["phone_number"] != phone_number:
                raise IdempotencyConflict("idempotency_key_reused")
            return self._safe(existing)

        call_id = str(uuid.uuid4())
        doc = {
            "_id": call_id,
            "organization_id": organization_id,
            "principal": principal,
            "idempotency_key": idempotency_key,
            "phone_number": phone_number,
            "state": "queued",
            "created_at": now,
            "updated_at": now,
            "expires_at": now + queue_ttl,
            "active_slot": "primary",
            "claimed_by": None,
            "claimed_at": None,
            "reason_code": None,
            "events": [{"state": "queued", "at": now,
                        "atMs": int(time.time_ns() // 1_000_000)}],
        }
        try:
            self.calls.insert_one(doc)
            return self._safe(doc)
        except DuplicateKeyError:
            existing = self.calls.find_one({
                "organization_id": organization_id,
                "principal": principal,
                "idempotency_key": idempotency_key,
            })
            if existing:
                if existing["phone_number"] != phone_number:
                    raise IdempotencyConflict("idempotency_key_reused")
                return self._safe(existing)
            raise DeviceBusy("device_busy")

    def claim_next(
        self, *, organization_id: str, device_id: str, now: int,
        queue_ttl: int, cellular_ring_timeout: int, sip_ring_timeout: int,
        max_call_seconds: int, claim_lease_seconds: int = 30,
    ) -> dict[str, Any]:
        expiry_rules = [
            ("queued", now - queue_ttl, "queue_expired", "expires_at"),
            ("ringing_cellular", now - cellular_ring_timeout,
             "cellular_answer_timeout", "updated_at"),
            ("calling_sip", now - sip_ring_timeout, "sip_answer_timeout", "updated_at"),
            ("bridged", now - max_call_seconds, "max_call_duration", "updated_at"),
        ]
        for state, cutoff, reason, field in expiry_rules:
            self.calls.update_many(
                {"organization_id": organization_id, "state": state,
                 field: {"$lt": now if field == "expires_at" else cutoff}},
                {"$set": {"state": "ended" if state == "bridged" else "failed",
                          "reason_code": reason, "updated_at": now},
                 "$unset": {"active_slot": ""}},
            )

        claimed = self.calls.find_one_and_update(
            {
                "organization_id": organization_id,
                "state": "queued",
                "expires_at": {"$gte": now},
                "$or": [
                    {"claimed_by": device_id},
                    {"claimed_by": None},
                    {"claimed_by": {"$exists": False}},
                    {"claimed_at": {"$lt": now - claim_lease_seconds}},
                ],
            },
            {"$set": {"claimed_by": device_id, "claimed_at": now}},
            sort=[("created_at", ASCENDING)],
            return_document=ReturnDocument.AFTER,
        )
        return self._job_result(claimed)

    @staticmethod
    def _job_result(doc: dict[str, Any] | None) -> dict[str, Any]:
        if not doc:
            return {"job": None}
        return {"job": {
            "id": doc["_id"],
            "phoneNumber": doc["phone_number"],
            "expiresAt": doc["expires_at"],
        }}

    def transition(
        self, *, organization_id: str, call_id: str, device_id: str,
        state: str, reason_code: str | None = None, now: int | None = None,
    ) -> dict[str, Any]:
        now = int(time.time()) if now is None else now
        scope = {"organization_id": organization_id, "_id": call_id}
        current = self.calls.find_one(scope)
        if not current:
            raise CallNotFound("call_not_found")
        if current.get("claimed_by") != device_id:
            raise JobNotOwned("job_not_owned")
        if current["state"] == state or current["state"] in TERMINAL:
            return self._safe(current)
        if state not in ALLOWED.get(current["state"], set()):
            raise InvalidTransition("invalid_state_transition")

        event = {"state": state, "at": now,
                 "atMs": int(time.time_ns() // 1_000_000),
                 "reasonCode": reason_code}
        update: dict[str, Any] = {
            "$set": {"state": state, "updated_at": now,
                     "reason_code": reason_code},
            "$push": {"events": event},
        }
        if state in TERMINAL:
            update["$unset"] = {"active_slot": ""}
        result = self.calls.update_one(
            {**scope, "state": current["state"], "claimed_by": device_id},
            update,
        )
        if result.modified_count != 1:
            latest = self.calls.find_one(scope)
            if latest and (latest["state"] == state or latest["state"] in TERMINAL):
                return self._safe(latest)
            raise InvalidTransition("invalid_state_transition")
        return self._safe(self.calls.find_one(scope))
