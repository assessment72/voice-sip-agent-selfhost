import mongomock
import pytest

import mongodb_control


@pytest.fixture
def db(monkeypatch):
    database = mongomock.MongoClient().db
    monkeypatch.setattr(mongodb_control, "collection", lambda name: database[name])
    monkeypatch.setattr(mongodb_control, "with_transaction", lambda callback: callback(None))
    return database


def test_bootstrap_and_organization_authentication(db):
    org_id = mongodb_control.bootstrap_organization(
        slug="alpha", name="Alpha", owner_user_id="owner-1",
        token_hash="hash-owner", token_prefix="prefix",
    )
    auth = mongodb_control.authenticate_organization("hash-owner", org_id)
    assert auth == {"organization_id": org_id, "user_id": "owner-1", "role": "owner"}
    assert mongodb_control.authenticate_organization("hash-owner", "other-org") is None
    assert mongodb_control.authenticate_organization("wrong", org_id) is None


def test_enrollment_token_is_limited_and_device_auth_is_scoped(db):
    org_id = mongodb_control.bootstrap_organization(
        slug="alpha", name="Alpha", owner_user_id="owner-1",
        token_hash="owner-hash", token_prefix="owner",
    )
    mongodb_control.create_enrollment_token(
        organization_id=org_id, token_hash="enroll-hash", label="test",
        expires_at=9999999999, max_uses=1, created_by="owner-1",
    )
    device_id, enrolled_org = mongodb_control.enroll_device(
        enrollment_token_hash="enroll-hash", external_device_id="phone-1",
        display_name="Phone", branch_id=None, branch_name=None, app_id="app",
        app_version="1", android_version="16", manufacturer="HONOR", model="MTN-NX1",
        device_token_hash="device-hash", device_token_prefix="device",
    )
    assert enrolled_org == org_id
    assert mongodb_control.authenticate_device("device-hash") == {
        "device_id": device_id, "organization_id": org_id
    }
    with pytest.raises(mongodb_control.ControlNotFound):
        mongodb_control.enroll_device(
            enrollment_token_hash="enroll-hash", external_device_id="phone-2",
            display_name="Phone 2", branch_id=None, branch_name=None, app_id="app",
            app_version="1", android_version="16", manufacturer="HONOR", model="MTN-NX1",
            device_token_hash="device-hash-2", device_token_prefix="device",
        )


def test_heartbeat_and_device_events_are_tenant_scoped(db):
    org_id = mongodb_control.bootstrap_organization(
        slug="alpha", name="Alpha", owner_user_id="owner-1",
        token_hash="owner-hash", token_prefix="owner",
    )
    mongodb_control.create_enrollment_token(
        organization_id=org_id, token_hash="enroll-hash", label="test",
        expires_at=9999999999, max_uses=1, created_by="owner-1",
    )
    device_id, _ = mongodb_control.enroll_device(
        enrollment_token_hash="enroll-hash", external_device_id="phone-1",
        display_name="Phone", branch_id=None, branch_name=None, app_id="app",
        app_version="1", android_version="16", manufacturer="HONOR", model="MTN-NX1",
        device_token_hash="device-hash", device_token_prefix="device",
    )
    now = mongodb_control.heartbeat(
        device_id=device_id, organization_id=org_id,
        payload={"batteryLevel": 88, "networkType": "wifi", "internetAvailable": True,
                 "sipStatus": "registered", "simStatus": "ready", "agentStatus": "idle",
                 "activeCall": False},
    )
    assert now > 0
    mongodb_control.append_device_event(
        organization_id=org_id, device_id=device_id, event_type="network",
        severity="info", event_code="wifi_connected", occurred_at=now,
    )
    events = mongodb_control.list_device_events(
        organization_id=org_id, device_id=device_id, limit=10
    )
    assert len(events) == 1
    assert events[0]["event_code"] == "wifi_connected"
    with pytest.raises(mongodb_control.ControlNotFound):
        mongodb_control.list_device_events(
            organization_id="another-org", device_id=device_id, limit=10
        )


def test_branches_and_append_only_audit_queries_are_organization_scoped(db):
    org_a = mongodb_control.bootstrap_organization(
        slug="alpha", name="Alpha", owner_user_id="owner-a",
        token_hash="owner-a-hash", token_prefix="owner-a",
    )
    org_b = mongodb_control.bootstrap_organization(
        slug="beta", name="Beta", owner_user_id="owner-b",
        token_hash="owner-b-hash", token_prefix="owner-b",
    )
    branch_id = mongodb_control.create_branch(
        organization_id=org_a, name="North", code="N", actor_user_id="owner-a",
    )
    assert mongodb_control.list_branches(org_a)[0]["_id"] == branch_id
    assert mongodb_control.list_branches(org_b) == []
    mongodb_control.audit(
        organization_id=org_a, actor_user_id="owner-a", action="branch.created",
        target_type="branch", target_id=branch_id, metadata={"code": "N"},
    )
    assert len(mongodb_control.list_audit(org_a, 10)) == 3
    org_b_audit = mongodb_control.list_audit(org_b, 10)
    assert len(org_b_audit) == 1
    assert org_b_audit[0]["action"] == "organization.created"



def test_member_lifecycle_and_ownership_transfer_are_atomic(db):
    org_id = mongodb_control.bootstrap_organization(
        slug="alpha", name="Alpha", owner_user_id="owner-a",
        token_hash="owner-a-hash", token_prefix="owner-a",
    )
    mongodb_control.add_member(
        organization_id=org_id, user_id="operator-1", role="operator",
        token_hash="operator-hash", token_prefix="operator",
        actor_user_id="owner-a",
    )
    assert mongodb_control.authenticate_organization("operator-hash", org_id)["role"] == "operator"
    mongodb_control.transfer_ownership(
        organization_id=org_id, previous_owner="owner-a", new_owner="operator-1",
        actor_user_id="owner-a",
    )
    assert mongodb_control.authenticate_organization("owner-a-hash", org_id)["role"] == "admin"
    assert mongodb_control.authenticate_organization("operator-hash", org_id)["role"] == "owner"
    mongodb_control.revoke_member(
        organization_id=org_id, user_id="owner-a", actor_user_id="operator-1",
        actor_role="owner",
    )
    assert mongodb_control.authenticate_organization("owner-a-hash", org_id) is None


def test_credential_rotation_and_device_command_lifecycle(db):
    org_id = mongodb_control.bootstrap_organization(
        slug="alpha", name="Alpha", owner_user_id="owner-a",
        token_hash="owner-a-hash", token_prefix="owner-a",
    )
    mongodb_control.add_member(
        organization_id=org_id, user_id="operator-1", role="operator",
        token_hash="operator-old", token_prefix="operator",
        actor_user_id="owner-a",
    )
    mongodb_control.rotate_member_credential(
        organization_id=org_id, user_id="operator-1", actor_user_id="owner-a",
        actor_role="owner", token_hash="operator-new", token_prefix="new-token",
    )
    assert mongodb_control.authenticate_organization("operator-old", org_id) is None
    assert mongodb_control.authenticate_organization("operator-new", org_id)["role"] == "operator"

    mongodb_control.create_enrollment_token(
        organization_id=org_id, token_hash="enroll", label="test",
        expires_at=9999999999, max_uses=1, created_by="owner-a",
    )
    device_id, _ = mongodb_control.enroll_device(
        enrollment_token_hash="enroll", external_device_id="phone",
        display_name="Phone", branch_id=None, branch_name=None, app_id="app",
        app_version="1", android_version="16", manufacturer="HONOR", model="MTN-NX1",
        device_token_hash="device", device_token_prefix="device",
    )
    command_id, expiry = mongodb_control.create_command(
        organization_id=org_id, device_id=device_id, command_type="refresh_status",
        expires_in_seconds=300, actor_user_id="owner-a",
    )
    command = mongodb_control.claim_command(device_id=device_id, organization_id=org_id)
    assert command["id"] == command_id
    assert command["expiresAt"] == expiry
    mongodb_control.update_command_result(
        command_id=command_id, device_id=device_id, organization_id=org_id,
        status="completed", result_code="ok", result_summary="done",
    )
    with pytest.raises(mongodb_control.ControlConflict):
        mongodb_control.update_command_result(
            command_id=command_id, device_id=device_id, organization_id=org_id,
            status="failed", result_code="again", result_summary="duplicate",
        )
