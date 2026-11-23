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
        organization_id=org_a, name="North", code="N",
    )
    assert mongodb_control.list_branches(org_a)[0]["_id"] == branch_id
    assert mongodb_control.list_branches(org_b) == []
    mongodb_control.audit(
        organization_id=org_a, actor_user_id="owner-a", action="branch.created",
        target_type="branch", target_id=branch_id, metadata={"code": "N"},
    )
    assert len(mongodb_control.list_audit(org_a, 10)) == 1
    assert mongodb_control.list_audit(org_b, 10) == []
