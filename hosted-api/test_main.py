import uuid

from fastapi.testclient import TestClient
import main


def setup_client(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB", str(tmp_path / "calls.sqlite3"))
    monkeypatch.setattr(main, "CALL_TOKEN", "test-caller-token")
    monkeypatch.setattr(main, "DEVICE_TOKEN", "test-device-token")
    monkeypatch.setattr(main, "LEGACY_CALL_API_ENABLED", True)
    monkeypatch.setattr(main, "LEGACY_DEVICE_API_ENABLED", True)
    monkeypatch.setattr(main, "PREFIXES", ("+9677",))
    monkeypatch.setattr(main, "RATE_LIMIT", 20)
    main.init_db()
    return TestClient(main.app)


def headers(token, **extra):
    return {"Authorization": f"Bearer {token}", **extra}


def test_legacy_shared_token_routes_are_disabled_by_default(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    monkeypatch.setattr(main, "LEGACY_CALL_API_ENABLED", False)
    monkeypatch.setattr(main, "LEGACY_DEVICE_API_ENABLED", False)
    call = client.post(
        "/v1/calls", json={"phoneNumber": "777405578"},
        headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}),
    )
    jobs = client.get("/v1/device/jobs/next?wait=0",
                      headers=headers("test-device-token"))
    assert call.status_code == 404
    assert call.json()["detail"] == "legacy_call_api_disabled"
    assert jobs.status_code == 404
    assert jobs.json()["detail"] == "legacy_device_api_disabled"


def test_authentication_and_validation(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    assert client.post("/v1/calls", json={"phoneNumber": "+967712345678"},
                       headers={"Idempotency-Key": str(uuid.uuid4())}).status_code == 401
    response = client.post("/v1/calls", json={"phoneNumber": "+967112"},
                           headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}))
    assert response.status_code == 422  # malformed E.164 number rejected


def test_emergency_short_codes_are_rejected_before_e164_validation(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    for number in ("112", "911", "+911", "999"):
        response = client.post(
            "/v1/calls", json={"phoneNumber": number},
            headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}),
        )
        assert response.status_code == 422
        assert response.json()["detail"] == "emergency_number"


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
        # Replaying the same transition must succeed for durable handset outbox delivery.
        replay = client.post(f"/v1/device/calls/{job['id']}/state",
                             json={"state": state}, headers=device_headers)
        assert replay.status_code == 200
        assert replay.json()["state"] == state

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

def test_idempotent_replay_does_not_consume_rate_limit(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    monkeypatch.setattr(main, "RATE_LIMIT", 1)
    key = str(uuid.uuid4())
    h = headers("test-caller-token", **{"Idempotency-Key": key})
    payload = {"phoneNumber": "777405578"}
    first = client.post("/v1/calls", json=payload, headers=h)
    replay = client.post("/v1/calls", json=payload, headers=h)
    assert first.status_code == 202
    assert replay.status_code == 202
    assert replay.json()["id"] == first.json()["id"]


def test_unfinished_queued_job_claim_expires_and_can_be_retried(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    key = str(uuid.uuid4())
    created = client.post("/v1/calls", json={"phoneNumber": "777405578"},
                          headers=headers("test-caller-token", **{"Idempotency-Key": key})).json()
    device_headers = headers("test-device-token")
    first = client.get("/v1/device/jobs/next", headers=device_headers).json()["job"]
    assert first["id"] == created["id"]
    import time
    start = time.time()
    monkeypatch.setattr(main.time, "time", lambda: start + 31)
    retried = client.get("/v1/device/jobs/next", headers=device_headers).json()["job"]
    assert retried["id"] == created["id"]

def test_long_poll_returns_immediately_when_no_jobs_after_wait_window(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    import time
    started = time.monotonic()
    response = client.get("/v1/device/jobs/next?wait=1", headers=headers("test-device-token"))
    elapsed = time.monotonic() - started
    assert response.status_code == 200
    assert response.json()["job"] is None
    assert elapsed >= 0.9
    assert elapsed < 3


def test_long_poll_wakes_for_newly_queued_job(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    import threading
    import time
    result = {}
    device_headers = headers("test-device-token")

    def wait_for_job():
        result["response"] = client.get("/v1/device/jobs/next?wait=5", headers=device_headers)

    thread = threading.Thread(target=wait_for_job)
    thread.start()
    time.sleep(0.35)
    created = client.post("/v1/calls", json={"phoneNumber": "777405578"},
                          headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}))
    thread.join(timeout=3)
    assert created.status_code == 202
    assert not thread.is_alive()
    assert result["response"].status_code == 200
    assert result["response"].json()["job"]["id"] == created.json()["id"]


def test_device_cannot_update_unclaimed_call(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    key = str(uuid.uuid4())
    created = client.post("/v1/calls", json={"phoneNumber": "+967712345678"},
                          headers=headers("test-caller-token", **{"Idempotency-Key": key})).json()
    result = client.post(f"/v1/device/calls/{created['id']}/state",
                         json={"state": "ringing_cellular"}, headers=headers("test-device-token"))
    assert result.status_code == 403


def test_latency_metrics_are_aggregated_without_call_identifiers_or_numbers(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    key = str(uuid.uuid4())
    created = client.post(
        "/v1/calls", json={"phoneNumber": "777405578"},
        headers=headers("test-caller-token", **{"Idempotency-Key": key}),
    ).json()
    call_id = created["id"]
    device_headers = headers("test-device-token")
    assert client.get("/v1/device/jobs/next?wait=0", headers=device_headers).json()["job"]["id"] == call_id

    import time
    base = time.time_ns() // 1_000_000
    with main.db() as conn:
        events = [
            {"state": "queued", "at": base // 1000, "atMs": base},
            {"state": "ringing_cellular", "at": (base + 120) // 1000, "atMs": base + 120},
            {"state": "cellular_answered", "at": (base + 500) // 1000, "atMs": base + 500},
            {"state": "calling_sip", "at": (base + 650) // 1000, "atMs": base + 650},
            {"state": "bridged", "at": (base + 900) // 1000, "atMs": base + 900},
        ]
        conn.execute("UPDATE calls SET events=? WHERE id=?", (main.json.dumps(events), call_id))

    response = client.get("/v1/metrics/latency", headers=headers("test-caller-token"))
    assert response.status_code == 200
    body = response.json()
    assert body["queue_to_cellular_ringing"] == {"samples": 1, "averageMs": 120.0, "p95Ms": 120}
    assert body["queue_to_bridged"] == {"samples": 1, "averageMs": 900.0, "p95Ms": 900}
    assert body["cellular_answer_to_sip"] == {"samples": 1, "averageMs": 150.0, "p95Ms": 150}
    assert call_id not in response.text
    assert "777405578" not in response.text


def test_parallel_pollers_cannot_claim_the_same_job(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    created = client.post(
        "/v1/calls", json={"phoneNumber": "777405578"},
        headers=headers("test-caller-token", **{"Idempotency-Key": str(uuid.uuid4())}),
    ).json()
    import threading
    results = []
    lock = threading.Lock()

    def poll():
        response = client.get("/v1/device/jobs/next?wait=0", headers=headers("test-device-token"))
        with lock:
            results.append(response.json()["job"])

    threads = [threading.Thread(target=poll) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=3)
    assert all(not thread.is_alive() for thread in threads)
    claimed = [job for job in results if job is not None]
    assert len(claimed) == 1
    assert claimed[0]["id"] == created["id"]


def test_rate_limit_enforces_sensitive_bootstrap_requests(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    monkeypatch.setattr(main, "BOOTSTRAP_TOKEN", "bootstrap-test-token")
    responses = []
    for index in range(6):
        responses.append(client.post(
            "/v1/organizations/bootstrap",
            json={"name": f"Org {index}", "slug": f"org-{index}", "ownerUserId": f"owner-{index}"},
            headers=headers("bootstrap-test-token"),
        ))
    assert [response.status_code for response in responses[:5]] == [201] * 5
    assert responses[5].status_code == 429
    with main.db() as connection:
        stored = [row["key_hash"] for row in connection.execute("SELECT key_hash FROM request_limits")]
    assert "bootstrap-test-token" not in stored


def test_distributed_rate_limit_requirement_fails_closed_without_mongodb(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    monkeypatch.delenv("MONGODB_URI", raising=False)
    monkeypatch.setattr(main, "REQUIRE_DISTRIBUTED_RATE_LIMIT", True)
    response = client.post("/v1/device/enroll", json={})
    assert response.status_code == 503
    assert response.json()["detail"] == "rate_limiter_unavailable"


def test_database_health_probes_the_active_sqlite_backend(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    response = client.get("/health/database")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "backend": "sqlite"}


def test_mongodb_health_is_separate_from_the_active_call_database(tmp_path, monkeypatch):
    client = setup_client(tmp_path, monkeypatch)
    import mongodb_store
    monkeypatch.setattr(mongodb_store, "health_snapshot",
                        lambda: {"status": "ok", "backend": "mongodb"})
    response = client.get("/health/mongodb")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "backend": "mongodb"}
