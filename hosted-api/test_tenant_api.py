import uuid

from fastapi.testclient import TestClient
import main


def setup_client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB", str(tmp_path / "tenant-api.sqlite3"))
    monkeypatch.setattr(main, "CALL_TOKEN", "legacy-call-token")
    monkeypatch.setattr(main, "PREFIXES", ("+9677",))
    monkeypatch.setattr(main, "DEVICE_TOKEN", "legacy-device-token")
    monkeypatch.setattr(main, "LEGACY_CALL_API_ENABLED", True)
    monkeypatch.setattr(main, "LEGACY_DEVICE_API_ENABLED", True)
    monkeypatch.setattr(main, "BOOTSTRAP_TOKEN", "test-bootstrap-secret")
    monkeypatch.setattr(main, "PRESENCE_ONLINE_SECONDS", 60)
    monkeypatch.setattr(main, "PRESENCE_UNSTABLE_SECONDS", 120)
    main.init_db()
    return TestClient(main.app)


def auth(token, organization_id=None):
    headers = {"Authorization": f"Bearer {token}"}
    if organization_id:
        headers["X-Organization-ID"] = organization_id
    return headers


def create_org(client, name, slug, owner):
    response = client.post(
        "/v1/organizations/bootstrap",
        json={"name": name, "slug": slug, "ownerUserId": owner},
        headers=auth("test-bootstrap-secret"),
    )
    assert response.status_code == 201, response.text
    return response.json()


def test_bootstrap_requires_secret_and_returns_one_time_org_credential(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    payload = {"name": "Alpha", "slug": "alpha-org", "ownerUserId": "owner-a"}
    assert client.post("/v1/organizations/bootstrap", json=payload).status_code == 401
    created = client.post(
        "/v1/organizations/bootstrap", json=payload,
        headers=auth("test-bootstrap-secret"),
    )
    assert created.status_code == 201
    assert len(created.json()["apiToken"]) > 32
    with main.db() as connection:
        stored = connection.execute(
            "SELECT token_hash FROM organization_api_credentials"
        ).fetchone()["token_hash"]
    assert stored != created.json()["apiToken"]
    assert stored == main._hash_secret(created.json()["apiToken"])


def test_device_enrollment_and_heartbeat_updates_presence(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    admin = auth(org["apiToken"], org["organizationId"])
    token_response = client.post(
        f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
        json={"label": "Front desk", "expiresInSeconds": 3600, "maxUses": 1},
        headers=admin,
    )
    assert token_response.status_code == 201
    enrollment_token = token_response.json()["enrollmentToken"]
    enrolled = client.post(
        "/v1/device/enroll",
        json={
            "enrollmentToken": enrollment_token,
            "externalDeviceId": "android-001",
            "displayName": "Front desk phone",
            "branchName": "Main",
            "appVersion": "1.2.0",
            "androidVersion": "16",
            "manufacturer": "HONOR",
            "model": "MTN-NX1",
        },
    )
    assert enrolled.status_code == 201, enrolled.text
    device_token = enrolled.json()["deviceToken"]
    heartbeat = client.post(
        "/v1/device/heartbeat",
        json={
            "batteryLevel": 82, "networkType": "wifi", "internetAvailable": True,
            "sipStatus": "registered", "simStatus": "ready", "agentStatus": "idle",
            "activeCall": False,
        },
        headers=auth(device_token),
    )
    assert heartbeat.status_code == 200
    devices = client.get("/v1/admin/devices", headers=admin)
    assert devices.status_code == 200
    device = devices.json()["devices"][0]
    assert device["presence"] == "online"
    assert device["sip_status"] == "registered"
    assert device["battery_level"] == 82
    assert device["model"] == "MTN-NX1"


def test_organization_isolation_and_enrollment_token_single_use(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    alpha = create_org(client, "Alpha", "alpha", "owner-a")
    beta = create_org(client, "Beta", "beta", "owner-b")
    alpha_admin = auth(alpha["apiToken"], alpha["organizationId"])
    beta_admin = auth(beta["apiToken"], beta["organizationId"])
    issued = client.post(
        f"/v1/organizations/{alpha['organizationId']}/enrollment-tokens",
        json={"label": "Only one device", "maxUses": 1},
        headers=alpha_admin,
    )
    assert issued.status_code == 201
    body = {
        "enrollmentToken": issued.json()["enrollmentToken"],
        "externalDeviceId": "shared-id",
        "displayName": "Alpha device",
    }
    first = client.post("/v1/device/enroll", json=body)
    assert first.status_code == 201
    second = client.post(
        "/v1/device/enroll",
        json={**body, "externalDeviceId": "second-id"},
    )
    assert second.status_code == 401
    # A valid Beta credential cannot read Alpha's devices.
    assert client.get("/v1/admin/devices", headers=beta_admin).json()["count"] == 0
    assert client.get(
        "/v1/admin/devices",
        headers=auth(beta["apiToken"], alpha["organizationId"]),
    ).status_code == 403


def test_non_admin_cannot_issue_enrollment_token(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    now = 1
    with main.db() as connection:
        connection.execute(
            """INSERT INTO organization_memberships
               (organization_id,user_id,role,status,created_at,updated_at)
               VALUES(?,?,'viewer','active',?,?)""",
            (org["organizationId"], "viewer-a", now, now),
        )
        raw = "viewer-credential-secret"
        connection.execute(
            """INSERT INTO organization_api_credentials
               (id,organization_id,label,user_id,token_hash,token_prefix,scopes,created_at)
               VALUES(?,?,?,?,?,?,'[]',?)""",
            (str(uuid.uuid4()), org["organizationId"], "Viewer", "viewer-a", main._hash_secret(raw), raw[:8], now),
        )
    result = client.post(
        f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
        json={"label": "Should fail"},
        headers=auth(raw, org["organizationId"]),
    )
    assert result.status_code == 403


def test_device_event_is_visible_only_inside_owning_organization(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    alpha = create_org(client, "Alpha", "alpha", "owner-a")
    beta = create_org(client, "Beta", "beta", "owner-b")
    token = client.post(
        f"/v1/organizations/{alpha['organizationId']}/enrollment-tokens",
        json={"label": "device"},
        headers=auth(alpha["apiToken"], alpha["organizationId"]),
    ).json()["enrollmentToken"]
    device = client.post(
        "/v1/device/enroll",
        json={"enrollmentToken": token, "externalDeviceId": "d1", "displayName": "D1"},
    ).json()
    reported = client.post(
        "/v1/device/events",
        json={"eventType": "network", "eventCode": "network_restored", "severity": "info"},
        headers=auth(device["deviceToken"]),
    )
    assert reported.status_code == 202
    activity = client.get(
        f"/v1/admin/devices/{device['deviceId']}/activity",
        headers=auth(alpha["apiToken"], alpha["organizationId"]),
    )
    assert activity.status_code == 200
    org_feed = client.get("/v1/admin/activity", headers=auth(alpha["apiToken"], alpha["organizationId"]))
    assert org_feed.status_code == 200
    assert org_feed.json()["count"] == 1
    hidden = client.get(
        f"/v1/admin/devices/{device['deviceId']}/activity",
        headers=auth(beta["apiToken"], beta["organizationId"]),
    )
    assert hidden.status_code == 404


def test_tenant_call_is_scoped_and_claimed_only_by_owning_org_device(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    alpha = create_org(client, "Alpha", "alpha", "owner-a")
    beta = create_org(client, "Beta", "beta", "owner-b")

    def enroll(org, external_id):
        headers = auth(org["apiToken"], org["organizationId"])
        token = client.post(
            f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
            json={"label": external_id}, headers=headers,
        ).json()["enrollmentToken"]
        return client.post(
            "/v1/device/enroll",
            json={"enrollmentToken": token, "externalDeviceId": external_id, "displayName": external_id},
        ).json()

    alpha_device = enroll(alpha, "alpha-phone")
    beta_device = enroll(beta, "beta-phone")
    created = client.post(
        f"/v1/organizations/{alpha['organizationId']}/calls",
        json={"phoneNumber": "777405578"},
        headers=auth(alpha["apiToken"], alpha["organizationId"]) | {"Idempotency-Key": str(uuid.uuid4())},
    )
    assert created.status_code == 202, created.text
    # The compatibility device token must never claim a tenant-owned call.
    assert client.get(
        "/v1/device/jobs/next?wait=0",
        headers=auth("legacy-device-token"),
    ).json()["job"] is None
    assert client.get(
        "/v1/device/call-jobs/next?wait=0",
        headers=auth(beta_device["deviceToken"]),
    ).json()["job"] is None
    job = client.get(
        "/v1/device/call-jobs/next?wait=0",
        headers=auth(alpha_device["deviceToken"]),
    ).json()["job"]
    assert job["id"] == created.json()["id"]
    assert client.get(
        f"/v1/admin/calls/{job['id']}",
        headers=auth(beta["apiToken"], beta["organizationId"]),
    ).status_code == 404
    state = client.post(
        f"/v1/device/tenant-calls/{job['id']}/state",
        json={"state": "ringing_cellular"},
        headers=auth(alpha_device["deviceToken"]),
    )
    assert state.status_code == 200
    assert state.json()["state"] == "ringing_cellular"
    for next_state in ("cellular_answered", "calling_sip", "bridged"):
        state = client.post(
            f"/v1/device/tenant-calls/{job['id']}/state",
            json={"state": next_state},
            headers=auth(alpha_device["deviceToken"]),
        )
        assert state.status_code == 200
    metrics = client.get(
        "/v1/admin/metrics/latency",
        headers=auth(alpha["apiToken"], alpha["organizationId"]),
    )
    assert metrics.status_code == 200
    assert metrics.json()["queue_to_bridged"]["samples"] == 1
    assert client.get(
        "/v1/admin/metrics/latency",
        headers=auth(beta["apiToken"], beta["organizationId"]),
    ).json()["queue_to_bridged"]["samples"] == 0


def test_viewer_cannot_create_billable_organization_call(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    now = 1
    raw = "viewer-call-token"
    with main.db() as connection:
        connection.execute(
            """INSERT INTO organization_memberships
               (organization_id,user_id,role,status,created_at,updated_at)
               VALUES(?,?,'viewer','active',?,?)""",
            (org["organizationId"], "viewer-a", now, now),
        )
        connection.execute(
            """INSERT INTO organization_api_credentials
               (id,organization_id,label,user_id,token_hash,token_prefix,scopes,created_at)
               VALUES(?,?,?,?,?,?,'[]',?)""",
            (str(uuid.uuid4()), org["organizationId"], "Viewer", "viewer-a",
             main._hash_secret(raw), raw[:8], now),
        )
    response = client.post(
        f"/v1/organizations/{org['organizationId']}/calls",
        json={"phoneNumber": "777405578"},
        headers=auth(raw, org["organizationId"]) | {"Idempotency-Key": str(uuid.uuid4())},
    )
    assert response.status_code == 403
    assert response.json()["detail"] == "insufficient_role"


def test_suspended_organization_credentials_are_rejected(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    with main.db() as connection:
        connection.execute("UPDATE organizations SET status='suspended' WHERE id=?",
                           (org["organizationId"],))
    response = client.get("/v1/admin/devices",
                          headers=auth(org["apiToken"], org["organizationId"]))
    assert response.status_code == 403
    assert response.json()["detail"] == "organization_access_denied"


def test_suspended_organization_devices_cannot_poll_or_send_heartbeats(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    admin = auth(org["apiToken"], org["organizationId"])
    enrollment = client.post(
        f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
        json={"label": "device"}, headers=admin,
    ).json()
    device = client.post(
        "/v1/device/enroll",
        json={"enrollmentToken": enrollment["enrollmentToken"],
              "externalDeviceId": "suspended-phone", "displayName": "Suspended phone"},
    ).json()
    with main.db() as connection:
        connection.execute("UPDATE organizations SET status='suspended' WHERE id=?",
                           (org["organizationId"],))
    device_headers = auth(device["deviceToken"])
    assert client.get("/v1/device/call-jobs/next?wait=0",
                      headers=device_headers).status_code == 401
    assert client.post(
        "/v1/device/heartbeat",
        json={"networkType": "wifi", "internetAvailable": True},
        headers=device_headers,
    ).status_code == 401


def test_owner_can_transfer_ownership_without_invalidating_new_owner_token(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    owner_headers = auth(org["apiToken"], org["organizationId"])
    added = client.post(
        f"/v1/organizations/{org['organizationId']}/members",
        json={"userId": "operator-1", "role": "operator"},
        headers=owner_headers,
    )
    assert added.status_code == 201
    transferred = client.post(
        f"/v1/organizations/{org['organizationId']}/members/operator-1/transfer-ownership",
        headers=owner_headers,
    )
    assert transferred.status_code == 200
    assert transferred.json()["newOwnerUserId"] == "operator-1"

    new_owner_headers = auth(added.json()["apiToken"], org["organizationId"])
    assert client.get("/v1/admin/devices", headers=new_owner_headers).status_code == 200
    # The previous owner remains an administrator, but cannot revoke the new owner.
    assert client.post(
        f"/v1/organizations/{org['organizationId']}/members/operator-1/revoke",
        headers=owner_headers,
    ).status_code == 403
    # The new owner can revoke the previous owner, now an admin member.
    assert client.post(
        f"/v1/organizations/{org['organizationId']}/members/owner-a/revoke",
        headers=new_owner_headers,
    ).status_code == 200
    audit = client.get("/v1/admin/audit", headers=new_owner_headers)
    assert "organization.ownership_transferred" in [
        event["action"] for event in audit.json()["events"]
    ]


def test_member_revocation_and_credential_rotation_revoke_old_tokens(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    owner_headers = auth(org["apiToken"], org["organizationId"])
    member = client.post(
        f"/v1/organizations/{org['organizationId']}/members",
        json={"userId": "operator-1", "role": "operator"},
        headers=owner_headers,
    )
    assert member.status_code == 201
    old_token = member.json()["apiToken"]
    rotated = client.post(
        f"/v1/organizations/{org['organizationId']}/members/operator-1/rotate-credential",
        headers=owner_headers,
    )
    assert rotated.status_code == 200
    new_token = rotated.json()["apiToken"]
    assert client.get(
        "/v1/admin/devices",
        headers=auth(old_token, org["organizationId"]),
    ).status_code == 403
    assert client.get(
        "/v1/admin/devices",
        headers=auth(new_token, org["organizationId"]),
    ).status_code == 200
    revoked = client.post(
        f"/v1/organizations/{org['organizationId']}/members/operator-1/revoke",
        headers=owner_headers,
    )
    assert revoked.status_code == 200
    assert revoked.json()["status"] == "revoked"
    assert client.get(
        "/v1/admin/devices",
        headers=auth(new_token, org["organizationId"]),
    ).status_code == 403
    audit = client.get("/v1/admin/audit", headers=owner_headers)
    assert audit.status_code == 200
    actions = [event["action"] for event in audit.json()["events"]]
    assert "member.created" in actions
    assert "member.credential_rotated" in actions
    assert "member.revoked" in actions


def test_owner_membership_cannot_be_revoked_without_transfer(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    response = client.post(
        f"/v1/organizations/{org['organizationId']}/members/owner-a/revoke",
        headers=auth(org["apiToken"], org["organizationId"]),
    )
    assert response.status_code == 409
    assert response.json()["detail"] == "owner_transfer_required"


def test_device_command_delivery_and_completion_are_device_scoped(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    admin = auth(org["apiToken"], org["organizationId"])
    enrollment = client.post(
        f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
        json={"label": "device"}, headers=admin,
    ).json()
    device = client.post(
        "/v1/device/enroll",
        json={"enrollmentToken": enrollment["enrollmentToken"],
              "externalDeviceId": "cmd-device", "displayName": "Command device"},
    ).json()
    command = client.post(
        "/v1/admin/device-commands",
        json={"deviceId": device["deviceId"], "commandType": "refresh_status"},
        headers=admin,
    )
    assert command.status_code == 201
    delivered = client.get(
        "/v1/device/commands/next?wait=0",
        headers=auth(device["deviceToken"]),
    )
    assert delivered.json()["command"]["id"] == command.json()["commandId"]
    result = client.post(
        f"/v1/device/commands/{command.json()['commandId']}/result",
        json={"status": "completed", "resultCode": "ok", "resultSummary": "Status refreshed"},
        headers=auth(device["deviceToken"]),
    )
    assert result.status_code == 200
    assert result.json()["status"] == "completed"


def test_member_creation_issues_identity_bound_credential(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    created = client.post(
        f"/v1/organizations/{org['organizationId']}/members",
        json={"userId": "operator-1", "role": "operator"},
        headers=auth(org["apiToken"], org["organizationId"]),
    )
    assert created.status_code == 201
    assert created.json()["apiToken"]
    # The new member token is tied to the operator membership, not the owner.
    denied = client.post(
        f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
        json={"label": "operator cannot enroll"},
        headers=auth(created.json()["apiToken"], org["organizationId"]),
    )
    assert denied.status_code == 403


def test_admin_dashboard_is_served_without_exposing_credentials(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    response = client.get("/admin")
    assert response.status_code == 200
    assert "إدارة الأجهزة والمكالمات" in response.text
    assert "localStorage" not in response.text


def test_branch_is_owned_by_organization_and_device_can_be_assigned(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    alpha = create_org(client, "Alpha", "alpha", "owner-a")
    beta = create_org(client, "Beta", "beta", "owner-b")
    alpha_headers = auth(alpha["apiToken"], alpha["organizationId"])
    branch = client.post(
        f"/v1/organizations/{alpha['organizationId']}/branches",
        json={"name": "Main Branch", "code": "MAIN"},
        headers=alpha_headers,
    )
    assert branch.status_code == 201
    assert client.get(
        "/v1/admin/branches",
        headers=auth(beta["apiToken"], beta["organizationId"]),
    ).json()["count"] == 0
    enrollment = client.post(
        f"/v1/organizations/{alpha['organizationId']}/enrollment-tokens",
        json={"label": "main branch phone"},
        headers=alpha_headers,
    ).json()
    device = client.post(
        "/v1/device/enroll",
        json={
            "enrollmentToken": enrollment["enrollmentToken"],
            "externalDeviceId": "branch-phone",
            "displayName": "Main phone",
            "branchId": branch.json()["branchId"],
        },
    )
    assert device.status_code == 201, device.text
    listed = client.get("/v1/admin/devices", headers=alpha_headers).json()["devices"]
    assert listed[0]["branch_name"] == "Main Branch"
    assert listed[0]["branch_id"] == branch.json()["branchId"]


def test_multiple_devices_can_claim_parallel_calls_but_one_call_per_device(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    org = create_org(client, "Alpha", "alpha", "owner-a")
    admin = auth(org["apiToken"], org["organizationId"])

    def enroll(device_name):
        enrollment = client.post(
            f"/v1/organizations/{org['organizationId']}/enrollment-tokens",
            json={"label": device_name}, headers=admin,
        ).json()
        return client.post(
            "/v1/device/enroll",
            json={"enrollmentToken": enrollment["enrollmentToken"],
                  "externalDeviceId": device_name, "displayName": device_name},
        ).json()

    first_device, second_device = enroll("phone-1"), enroll("phone-2")
    calls = []
    for _ in range(2):
        response = client.post(
            f"/v1/organizations/{org['organizationId']}/calls",
            json={"phoneNumber": "777405578"},
            headers=admin | {"Idempotency-Key": str(uuid.uuid4())},
        )
        assert response.status_code == 202
        calls.append(response.json()["id"])

    first_job = client.get(
        "/v1/device/call-jobs/next?wait=0", headers=auth(first_device["deviceToken"])
    ).json()["job"]
    assert first_job["id"] == calls[0]
    assert client.get(
        "/v1/device/call-jobs/next?wait=0", headers=auth(first_device["deviceToken"])
    ).json()["job"] is None

    second_job = client.get(
        "/v1/device/call-jobs/next?wait=0", headers=auth(second_device["deviceToken"])
    ).json()["job"]
    assert second_job["id"] == calls[1]
