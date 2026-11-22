import uuid

from fastapi.testclient import TestClient
import main


def setup_client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB", str(tmp_path / "calls.sqlite3"))
    monkeypatch.setattr(main, "CALL_TOKEN", "test-caller-token")
    monkeypatch.setattr(main, "DEVICE_TOKEN", "test-device-token")
    monkeypatch.setattr(main, "PREFIXES", ("+9677",))
    monkeypatch.setattr(main, "RATE_LIMIT", 20)
    main.init_db()
    return TestClient(main.app)


def headers(token, **extra):
    return {"Authorization": f"Bearer {token}", **extra}


def test_authentication_and_validation(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    assert client.post("/v1/calls", json={"phoneNumber": "+967712345678"},
                       headers={"Idempotency-Key": str(uuid.uuid4())}).status_code == 401
    response = client.post("/v1/calls", json={"phoneNumber": "+967112"},
                           headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}))
    assert response.status_code == 422  # malformed E.164 number rejected


def test_idempotency_and_single_device_job(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    key = str(uuid.uuid4())
    h = headers("test-caller-token", **{"Idempotency-Key": key})
    payload = {"phoneNumber": "+967712345678"}
    first = client.post("/v1/calls", json=payload, headers=h)
    again = client.post("/v1/calls", json=payload, headers=h)
    assert first.status_code == 202
    assert first.json()["id"] == again.json()["id"]

    device_headers = headers("test-device-token")
    job = client.get("/v1/device/jobs/next", headers=device_headers).json()["job"]
    assert job["id"] == first.json()["id"]
    assert client.get("/v1/device/jobs/next", headers=device_headers).json()["job"] is None

    for state in ("ringing_cellular", "cellular_answered", "calling_sip", "bridged", "ended"):
        result = client.post(f"/v1/device/calls/{job['id']}/state",
                             json={"state": state}, headers=device_headers)
        assert result.status_code == 200
        assert result.json()["state"] == state

    assert client.get(f"/v1/calls/{job['id']}", headers=device_headers).status_code == 401
    assert client.get(f"/v1/calls/{job['id']}", headers=headers("test-caller-token")).json()["state"] == "ended"


def test_yemeni_national_mobile_is_normalized_and_idempotency_key_cannot_change_payload(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    key = str(uuid.uuid4())
    h = headers("test-caller-token", **{"Idempotency-Key": key})
    first = client.post("/v1/calls", json={"phoneNumber": "777405578"}, headers=h)
    assert first.status_code == 202
    same = client.post("/v1/calls", json={"phoneNumber": "+967777405578"}, headers=h)
    assert same.status_code == 202
    assert same.json()["id"] == first.json()["id"]
    changed = client.post("/v1/calls", json={"phoneNumber": "+967777405579"}, headers=h)
    assert changed.status_code == 409
    assert changed.json()["detail"] == "idempotency_key_reused"


def test_allowlist_must_be_configured(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    monkeypatch.setattr(main, "PREFIXES", ())
    response = client.post("/v1/calls", json={"phoneNumber": "777405578"},
                           headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}))
    assert response.status_code == 503

def test_device_cannot_update_unclaimed_call(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    key = str(uuid.uuid4())
    created = client.post("/v1/calls", json={"phoneNumber": "+967712345678"},
                          headers=headers("test-caller-token", **{"Idempotency-Key": key})).json()
    result = client.post(f"/v1/device/calls/{created['id']}/state",
                         json={"state": "ringing_cellular"}, headers=headers("test-device-token"))
    assert result.status_code == 403
