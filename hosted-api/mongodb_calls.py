"""Atomic MongoDB repository for the single-device hosted call lifecycle.

This repository is intentionally not selected by the FastAPI routes yet. It
provides independently testable primitives for the staged SQLite migration.
"""
from __future__ import annotations

import time
import uuid
from typing import Any

from pymongo import ASCENDING, IndexModel, ReturnDocument
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
    """Call lifecycle operations using atomic MongoDB document updates."""

    def __init__(self, calls: Collection):
        self.calls = calls

    def ensure_indexes(self) -> None:
        self.calls.create_indexes([
            # Same principal + key can only create one durable job.
            IndexModel(
                [("principal", ASCENDING), ("idempotency_key", ASCENDING)],
                unique=True, name="uq_call_principal_idempotency",
            ),
            # This API currently supports one active cellular/SIP job at a time.
            # Terminal transitions remove active_slot, allowing the next job.
            __import__("pymongo").IndexModel(
                [("active_slot", ASCENDING)], unique=True,
                partialFilterExpression={"active_slot": {"$exists": True}},
                name="uq_single_active_call_slot",
            ),
            __import__("pymongo").IndexModel(
                [("state", ASCENDING), ("created_at", ASCENDING)],
                name="ix_call_queue",
            ),
            __import__("pymongo").IndexModel(
                [("claimed_by", ASCENDING), ("state", ASCENDING)],
                name="ix_call_claim_owner",
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
        self, *, principal: str, idempotency_key: str, phone_number: str,
        now: int, queue_ttl: int,
    ) -> dict[str, Any]:
        existing = self.calls.find_one({
            "principal": principal, "idempotency_key": idempotency_key,
        })
        if existing:
            if existing["phone_number"] != phone_number:
                raise IdempotencyConflict("idempotency_key_reused")
            return self._safe(existing)

        call_id = str(uuid.uuid4())
        doc = {
            "_id": call_id,
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
            "events": [{"state": "queued", "at": now, "atMs": int(time.time_ns() // 1_000_000)}],
        }
        try:
            self.calls.insert_one(doc)
            return self._safe(doc)
        except DuplicateKeyError:
            # A competing request may have won the idempotency race, or another
            # call may have occupied the single active slot.
            existing = self.calls.find_one({
                "principal": principal, "idempotency_key": idempotency_key,
            })
            if existing:
                if existing["phone_number"] != phone_number:
                    raise IdempotencyConflict("idempotency_key_reused")
                return self._safe(existing)
            raise DeviceBusy("device_busy")

    def claim_next(
        self, *, device_id: str, now: int, queue_ttl: int,
        cellular_ring_timeout: int, sip_ring_timeout: int,
        max_call_seconds: int, claim_lease_seconds: int = 30,
    ) -> dict[str, Any]:
        # Expiration transitions and release of the single active slot happen
        # together on each document; the unique slot index prevents overlap.
        expiry_rules = [
            ("queued", now - queue_ttl, "queue_expired", "expires_at"),
            ("ringing_cellular", now - cellular_ring_timeout,
             "cellular_answer_timeout", "updated_at"),
            ("calling_sip", now - sip_ring_timeout, "sip_answer_timeout", "updated_at"),
            ("bridged", now - max_call_seconds, "max_call_duration", "updated_at"),
        ]
        for state, cutoff, reason, field in expiry_rules:
            terminal = "ended" if state == "bridged" else "failed"
            self.calls.update_many(
                {"state": state, field: {"$lt": now if field == "expires_at" else cutoff}},
                {"$set": {"state": terminal, "reason_code": reason, "updated_at": now},
                 "$unset": {"active_slot": ""}},
            )

        claimed = self.calls.find_one_and_update(
            {
                "state": "queued",
                "expires_at": {"$gte": now},
                "$or": [
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
        self, *, call_id: str, device_id: str, state: str,
        reason_code: str | None = None, now: int | None = None,
    ) -> dict[str, Any]:
        now = int(time.time()) if now is None else now
        current = self.calls.find_one({"_id": call_id})
        if not current:
            raise CallNotFound("call_not_found")
        if current.get("claimed_by") != device_id:
            raise JobNotOwned("job_not_owned")
        if current["state"] == state or current["state"] in TERMINAL:
            return self._safe(current)
        if state not in ALLOWED.get(current["state"], set()):
            raise InvalidTransition("invalid_state_transition")

        event = {"state": state, "at": now, "atMs": int(time.time_ns() // 1_000_000),
                 "reasonCode": reason_code}
        update: dict[str, Any] = {
            "$set": {"state": state, "updated_at": now, "reason_code": reason_code},
            "$push": {"events": event},
        }
        if state in TERMINAL:
            update["$unset"] = {"active_slot": ""}
        result = self.calls.update_one(
            {"_id": call_id, "state": current["state"], "claimed_by": device_id},
            update,
        )
        if result.modified_count != 1:
            latest = self.calls.find_one({"_id": call_id})
            if latest and (latest["state"] == state or latest["state"] in TERMINAL):
                return self._safe(latest)
            raise InvalidTransition("invalid_state_transition")
        return self._safe(self.calls.find_one({"_id": call_id}))
