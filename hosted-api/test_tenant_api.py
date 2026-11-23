import uuid

from fastapi.testclient import TestClient
import main


def setup_client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB", str(tmp_path / "tenant-api.sqlite3"))
    monkeypatch.setattr(main, "CALL_TOKEN", "legacy-call-token")
    monkeypatch.setattr(main, "DEVICE_TOKEN", "legacy-device-token")
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
