"""Opt-in live Atlas integration tests.

These tests are skipped unless a dedicated MONGODB_TEST_URI and
MONGODB_TEST_DATABASE are supplied. They create and drop only a uniquely
named temporary collection; they never touch application collections.
"""
from __future__ import annotations

import os
import uuid

import pytest
from pymongo import MongoClient

from mongodb_calls import MongoCallRepository
import mongodb_control
import mongodb_store


@pytest.fixture
def atlas_call_repository():
    uri = os.getenv("MONGODB_TEST_URI", "").strip()
    database_name = os.getenv("MONGODB_TEST_DATABASE", "").strip()
    if not uri or not database_name:
        pytest.skip("dedicated Atlas integration credentials are not configured")

    client = MongoClient(
        uri,
        appname="hosted-call-api-atlas-integration-test",
        serverSelectionTimeoutMS=10_000,
        connectTimeoutMS=10_000,
        retryWrites=True,
    )
    collection_name = f"ci_calls_{uuid.uuid4().hex}"
    try:
        client.admin.command("ping")
        calls = client[database_name][collection_name]
        repository = MongoCallRepository(calls)
        repository.ensure_indexes()
        yield repository
    finally:
        client[database_name].drop_collection(collection_name)
        client.close()


def test_live_atlas_call_idempotency_claim_and_state_transition(atlas_call_repository):
    repo = atlas_call_repository
    first = repo.create_call(
        organization_id="atlas-ci-org",
        principal="atlas-ci-user",
        idempotency_key="atlas-ci-idempotency-key-0001",
        phone_number="+967777000001",
        now=1_900_000_000,
        queue_ttl=120,
    )
    replay = repo.create_call(
        organization_id="atlas-ci-org",
        principal="atlas-ci-user",
        idempotency_key="atlas-ci-idempotency-key-0001",
        phone_number="+967777000001",
        now=1_900_000_001,
        queue_ttl=120,
    )
    assert replay["id"] == first["id"]

    claimed = repo.claim_next(
        organization_id="atlas-ci-org",
        device_id="atlas-ci-device",
        now=1_900_000_002,
        queue_ttl=120,
        cellular_ring_timeout=45,
        sip_ring_timeout=45,
        max_call_seconds=1800,
    )
    assert claimed["job"]["id"] == first["id"]

    updated = repo.transition(
        organization_id="atlas-ci-org",
        call_id=first["id"],
        device_id="atlas-ci-device",
        state="ringing_cellular",
        now=1_900_000_003,
    )
    assert updated["state"] == "ringing_cellular"

    replayed_state = repo.transition(
        organization_id="atlas-ci-org",
        call_id=first["id"],
        device_id="atlas-ci-device",
        state="ringing_cellular",
        now=1_900_000_004,
    )
    assert replayed_state["state"] == "ringing_cellular"



@pytest.fixture
def atlas_control_database(monkeypatch):
    uri = os.getenv("MONGODB_TEST_URI", "").strip()
    database_name = os.getenv("MONGODB_TEST_DATABASE", "").strip()
    if not uri or not database_name:
        pytest.skip("dedicated Atlas integration credentials are not configured")

    monkeypatch.setenv("MONGODB_URI", uri)
    monkeypatch.setenv("MONGODB_DATABASE", database_name)
    mongodb_store.get_client.cache_clear()
    client = mongodb_store.get_client()
    database = mongodb_store.get_database()
    try:
        client.admin.command("ping")
        mongodb_store.ensure_indexes(database)
        yield database
    finally:
        client.close()
        mongodb_store.get_client.cache_clear()


def test_live_atlas_control_plane_transactions_and_device_lifecycle(atlas_control_database):
    database = atlas_control_database
    org_id = None
    device_id = None
    slug = f"ci-org-{uuid.uuid4().hex}"
    owner_id = f"owner-{uuid.uuid4().hex}"
    operator_id = f"operator-{uuid.uuid4().hex}"
    operator2_id = f"operator2-{uuid.uuid4().hex}"
    rate_key = f"ci-rate-{uuid.uuid4().hex}"

    try:
        rate_window = int(__import__("time").time())
        assert mongodb_store.consume_rate_limit(
            key_hash=rate_key, window_start=rate_window, limit=1,
            expires_at=rate_window + 180,
        ) == (True, 1)
        assert mongodb_store.consume_rate_limit(
            key_hash=rate_key, window_start=rate_window, limit=1,
            expires_at=rate_window + 180,
        ) == (False, 2)
        org_id = mongodb_control.bootstrap_organization(
            slug=slug, name="Atlas CI organization", owner_user_id=owner_id,
            token_hash=f"owner-hash-{uuid.uuid4().hex}", token_prefix="ci-owner",
        )
        owner_credential = database.api_credentials.find_one({
            "organization_id": org_id, "user_id": owner_id
        })
        assert owner_credential is not None
        assert mongodb_control.authenticate_organization(owner_credential["token_hash"], org_id)["role"] == "owner"

        branch_id = mongodb_control.create_branch(
            organization_id=org_id, name="CI branch", code=f"B-{uuid.uuid4().hex[:10]}"
        )
        enrollment_hash = f"enroll-{uuid.uuid4().hex}"
        mongodb_control.create_enrollment_token(
            organization_id=org_id, token_hash=enrollment_hash, label="CI enrollment",
            expires_at=int(__import__("time").time()) + 600, max_uses=1, created_by=owner_id,
        )
        device_hash = f"device-{uuid.uuid4().hex}"
        device_id, enrolled_org = mongodb_control.enroll_device(
            enrollment_token_hash=enrollment_hash, external_device_id=f"device-{uuid.uuid4().hex}",
            display_name="Atlas CI handset", branch_id=branch_id, branch_name=None,
            app_id="ci.app", app_version="test", android_version="16",
            manufacturer="test", model="test-device",
            device_token_hash=device_hash, device_token_prefix="ci-device",
        )
        assert enrolled_org == org_id
        assert mongodb_control.authenticate_device(device_hash)["device_id"] == device_id

        mongodb_control.heartbeat(
            device_id=device_id, organization_id=org_id,
            payload={"batteryLevel": 90, "networkType": "wifi", "internetAvailable": True,
                     "sipStatus": "registered", "simStatus": "ready",
                     "agentStatus": "idle", "activeCall": False},
        )
        mongodb_control.append_device_event(
            organization_id=org_id, device_id=device_id, event_type="ci",
            severity="info", event_code="atlas_transaction_probe",
            occurred_at=int(__import__("time").time()),
        )
        assert mongodb_control.list_devices(org_id)
        assert mongodb_control.list_device_events(
            organization_id=org_id, device_id=device_id, limit=10
        )

        mongodb_control.add_member(
            organization_id=org_id, user_id=operator_id, role="operator",
            token_hash=f"operator-hash-{uuid.uuid4().hex}", token_prefix="ci-operator",
            actor_user_id=owner_id,
        )
        mongodb_control.add_member(
            organization_id=org_id, user_id=operator2_id, role="operator",
            token_hash=f"operator2-hash-{uuid.uuid4().hex}", token_prefix="ci-operator2",
            actor_user_id=owner_id,
        )
        mongodb_control.rotate_member_credential(
            organization_id=org_id, user_id=operator2_id, actor_user_id=owner_id,
            actor_role="owner", token_hash=f"rotated-{uuid.uuid4().hex}",
            token_prefix="ci-rotated",
        )
        mongodb_control.transfer_ownership(
            organization_id=org_id, previous_owner=owner_id, new_owner=operator_id,
            actor_user_id=owner_id,
        )
        mongodb_control.revoke_member(
            organization_id=org_id, user_id=owner_id, actor_user_id=operator_id,
            actor_role="owner",
        )

        command_id, _ = mongodb_control.create_command(
            organization_id=org_id, device_id=device_id, command_type="refresh_status",
            expires_in_seconds=300, actor_user_id=operator_id,
        )
        command = mongodb_control.claim_command(device_id=device_id, organization_id=org_id)
        assert command and command["id"] == command_id
        mongodb_control.update_command_result(
            command_id=command_id, device_id=device_id, organization_id=org_id,
            status="completed", result_code="ok", result_summary="atlas ci passed",
        )
        assert mongodb_control.list_audit(org_id, 50)
        assert mongodb_control.list_activity(org_id, 50)
        assert mongodb_control.latency_metrics(organization_id=org_id)

        mongodb_control.revoke_device(
            organization_id=org_id, device_id=device_id, actor_user_id=operator_id,
        )
        assert mongodb_control.authenticate_device(device_hash) is None
    finally:
        database.rate_limit_windows.delete_many({"principal": rate_key})
        if org_id:
            device_ids = [row["_id"] for row in database.devices.find(
                {"organization_id": org_id}, {"_id": 1}
            )]
            database.device_credentials.delete_many({"device_id": {"$in": device_ids}})
            database.heartbeats.delete_many({"device_id": {"$in": device_ids}})
            database.device_configs.delete_many({"device_id": {"$in": device_ids}})
            database.device_events.delete_many({"organization_id": org_id})
            database.device_commands.delete_many({"organization_id": org_id})
            database.devices.delete_many({"organization_id": org_id})
            database.enrollment_tokens.delete_many({"organization_id": org_id})
            database.branches.delete_many({"organization_id": org_id})
            database.api_credentials.delete_many({"organization_id": org_id})
            database.memberships.delete_many({"organization_id": org_id})
            database.admin_audit_events.delete_many({"organization_id": org_id})
            database.organizations.delete_one({"_id": org_id})
