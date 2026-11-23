"""MongoDB control-plane repositories for organizations, credentials and devices."""
from __future__ import annotations

import json
import secrets
import time
import uuid
from typing import Any

from pymongo import ASCENDING, DESCENDING, ReturnDocument
from pymongo.errors import DuplicateKeyError, PyMongoError

from mongodb_store import collection, with_transaction


class ControlConflict(RuntimeError):
    pass


class ControlNotFound(LookupError):
    pass


def _id() -> str:
    return str(uuid.uuid4())


def _now() -> int:
    return int(time.time())


def authenticate_organization(token_hash: str, organization_id: str) -> dict[str, Any] | None:
    credential = collection("api_credentials").find_one(
        {"token_hash": token_hash, "revoked_at": None}
    )
    if not credential or credential.get("expires_at") is not None and credential["expires_at"] <= _now():
        return None
    if credential.get("organization_id") != organization_id:
        return None
    member = collection("memberships").find_one({
        "organization_id": organization_id, "user_id": credential["user_id"], "status": "active"
    })
    org = collection("organizations").find_one({"_id": organization_id, "status": "active"})
    if not member or not org:
        return None
    collection("api_credentials").update_one(
        {"_id": credential["_id"]}, {"$set": {"last_used_at": _now()}}
    )
    return {"organization_id": organization_id, "user_id": member["user_id"], "role": member["role"]}


def authenticate_device(token_hash: str) -> dict[str, Any] | None:
    credential = collection("device_credentials").find_one(
        {"token_hash": token_hash, "revoked_at": None}
    )
    if not credential or credential.get("expires_at") is not None and credential["expires_at"] <= _now():
        return None
    device = collection("devices").find_one({"_id": credential["device_id"], "status": "active"})
    if not device:
        return None
    org = collection("organizations").find_one({"_id": device["organization_id"], "status": "active"})
    if not org:
        return None
    collection("device_credentials").update_one(
        {"_id": credential["_id"]}, {"$set": {"last_used_at": _now()}}
    )
    return {"device_id": device["_id"], "organization_id": device["organization_id"]}


def audit(*, organization_id: str, actor_user_id: str, action: str,
          target_type: str, target_id: str, metadata: dict | None = None,
          created_at: int | None = None) -> None:
    collection("admin_audit_events").insert_one({
        "_id": _id(), "organization_id": organization_id, "actor_user_id": actor_user_id,
        "action": action, "target_type": target_type, "target_id": target_id,
        "metadata": metadata or {}, "created_at": created_at or _now(),
    })


def bootstrap_organization(*, slug: str, name: str, owner_user_id: str,
                           token_hash: str, token_prefix: str) -> str:
    now, org_id = _now(), _id()
    def write(session):
        collection("organizations").insert_one({
            "_id": org_id, "slug": slug, "name": name, "status": "active",
            "created_at": now, "updated_at": now,
        }, session=session)
        collection("memberships").insert_one({
            "_id": _id(), "organization_id": org_id, "user_id": owner_user_id,
            "role": "owner", "status": "active", "created_at": now, "updated_at": now,
        }, session=session)
        collection("api_credentials").insert_one({
            "_id": _id(), "organization_id": org_id, "label": "Initial owner credential",
            "user_id": owner_user_id, "token_hash": token_hash, "token_prefix": token_prefix,
            "scopes": ["admin"], "last_used_at": None, "expires_at": None,
            "revoked_at": None, "created_at": now,
        }, session=session)
    try:
        with_transaction(write)
    except DuplicateKeyError as exc:
        raise ControlConflict("organization_slug_unavailable") from exc
    return org_id


def create_enrollment_token(*, organization_id: str, token_hash: str, label: str,
                            expires_at: int, max_uses: int, created_by: str) -> str:
    token_id, now = _id(), _now()
    collection("enrollment_tokens").insert_one({
        "_id": token_id, "organization_id": organization_id, "token_hash": token_hash,
        "label": label, "expires_at": expires_at, "max_uses": max_uses, "used_count": 0,
        "revoked_at": None, "created_by": created_by, "created_at": now,
    })
    return token_id


def enroll_device(*, enrollment_token_hash: str, external_device_id: str, display_name: str,
                  branch_id: str | None, branch_name: str | None, app_id: str,
                  app_version: str, android_version: str, manufacturer: str, model: str,
                  device_token_hash: str, device_token_prefix: str) -> tuple[str, str]:
    now, device_id, credential_id = _now(), _id(), _id()
    enrollment = collection("enrollment_tokens").find_one_and_update(
        {"token_hash": enrollment_token_hash, "revoked_at": None,
         "expires_at": {"$gt": now}, "$expr": {"$lt": ["$used_count", "$max_uses"]}},
        {"$inc": {"used_count": 1}}, return_document=ReturnDocument.BEFORE,
    )
    if not enrollment:
        raise ControlNotFound("enrollment_token_invalid")
    if branch_id:
        branch = collection("branches").find_one({
            "_id": branch_id, "organization_id": enrollment["organization_id"], "status": "active"
        })
        if not branch:
            collection("enrollment_tokens").update_one(
                {"_id": enrollment["_id"]}, {"$inc": {"used_count": -1}}
            )
            raise ControlNotFound("branch_not_found")
        branch_name = branch["name"]
    device = {
        "_id": device_id, "organization_id": enrollment["organization_id"],
        "external_device_id": external_device_id, "display_name": display_name,
        "branch_name": branch_name, "branch_id": branch_id, "status": "active",
        "presence": "offline", "app_id": app_id, "app_version": app_version,
        "android_version": android_version, "manufacturer": manufacturer, "model": model,
        "last_seen_at": None, "last_ip": None, "battery_level": None, "network_type": None,
        "internet_available": None, "sip_status": "unknown", "sim_status": "unknown",
        "agent_status": "unknown", "active_call_id": None, "config_version": 1,
        "created_at": now, "updated_at": now,
    }
    try:
        collection("devices").insert_one(device)
        collection("device_credentials").insert_one({
            "_id": credential_id, "device_id": device_id, "token_hash": device_token_hash,
            "token_prefix": device_token_prefix, "last_used_at": None, "expires_at": None,
            "revoked_at": None, "created_at": now,
        })
    except DuplicateKeyError as exc:
        collection("devices").delete_one({"_id": device_id})
        collection("enrollment_tokens").update_one(
            {"_id": enrollment["_id"]}, {"$inc": {"used_count": -1}}
        )
        raise ControlConflict("device_identity_already_enrolled") from exc
    return device_id, enrollment["organization_id"]


def heartbeat(*, device_id: str, organization_id: str, payload: dict[str, Any]) -> int:
    now = _now()
    update = {
        "last_seen_at": now, "presence": "online", "battery_level": payload.get("batteryLevel"),
        "network_type": payload.get("networkType"),
        "internet_available": bool(payload.get("internetAvailable")),
        "sip_status": payload.get("sipStatus", "unknown"),
        "sim_status": payload.get("simStatus", "unknown"),
        "agent_status": payload.get("agentStatus", "unknown"),
        "active_call_id": "active" if payload.get("activeCall") else None, "updated_at": now,
    }
    result = collection("devices").update_one(
        {"_id": device_id, "organization_id": organization_id, "status": "active"},
        {"$set": update},
    )
    if result.matched_count != 1:
        raise ControlNotFound("device_not_found")
    collection("heartbeats").insert_one({
        "_id": _id(), "device_id": device_id, "received_at": now, **update,
    })
    return now


def list_devices(organization_id: str) -> list[dict[str, Any]]:
    return list(collection("devices").find(
        {"organization_id": organization_id},
        {"_id": 1, "external_device_id": 1, "display_name": 1, "branch_name": 1,
         "branch_id": 1, "status": 1, "last_seen_at": 1, "app_version": 1,
         "android_version": 1, "manufacturer": 1, "model": 1, "battery_level": 1,
         "network_type": 1, "internet_available": 1, "sip_status": 1, "sim_status": 1,
         "agent_status": 1, "active_call_id": 1}
    ).sort("display_name", ASCENDING))


def create_branch(*, organization_id: str, name: str, code: str | None) -> str:
    now, branch_id = _now(), _id()
    try:
        collection("branches").insert_one({
            "_id": branch_id, "organization_id": organization_id, "name": name,
            "code": code, "status": "active", "created_at": now, "updated_at": now,
        })
    except DuplicateKeyError as exc:
        raise ControlConflict("branch_code_already_exists") from exc
    return branch_id


def list_branches(organization_id: str) -> list[dict[str, Any]]:
    return list(collection("branches").find(
        {"organization_id": organization_id},
        {"_id": 1, "name": 1, "code": 1, "status": 1, "created_at": 1}
    ).sort("name", ASCENDING))


def append_device_event(*, organization_id: str, device_id: str, event_type: str,
                         severity: str, event_code: str, occurred_at: int) -> str:
    event_id, now = _id(), _now()
    collection("device_events").insert_one({
        "_id": event_id, "organization_id": organization_id, "device_id": device_id,
        "event_type": event_type, "severity": severity, "event_code": event_code,
        "details": {}, "occurred_at": occurred_at, "received_at": now,
    })
    return event_id


def list_device_events(*, organization_id: str, device_id: str, limit: int) -> list[dict[str, Any]]:
    if not collection("devices").find_one({"_id": device_id, "organization_id": organization_id}):
        raise ControlNotFound("device_not_found")
    return list(collection("device_events").find(
        {"device_id": device_id, "organization_id": organization_id},
        {"event_type": 1, "severity": 1, "event_code": 1, "occurred_at": 1, "received_at": 1, "_id": 0}
    ).sort("received_at", DESCENDING).limit(limit))


def list_audit(organization_id: str, limit: int) -> list[dict[str, Any]]:
    return list(collection("admin_audit_events").find(
        {"organization_id": organization_id},
        {"_id": 1, "actor_user_id": 1, "action": 1, "target_type": 1,
         "target_id": 1, "metadata": 1, "created_at": 1}
    ).sort("created_at", DESCENDING).limit(limit))


def list_activity(organization_id: str, limit: int) -> list[dict[str, Any]]:
    events = list(collection("device_events").find(
        {"organization_id": organization_id},
        {"event_type": 1, "severity": 1, "event_code": 1, "occurred_at": 1,
         "received_at": 1, "device_id": 1, "_id": 0}
    ).sort("received_at", DESCENDING).limit(limit))
    for event in events:
        device = collection("devices").find_one(
            {"_id": event.pop("device_id"), "organization_id": organization_id},
            {"display_name": 1}
        )
        event["device_id"] = device["_id"] if device else None
        event["display_name"] = device.get("display_name") if device else None
    return events
