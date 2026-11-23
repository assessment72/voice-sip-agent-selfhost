import mongomock
import pytest

from mongodb_calls import (
    CallNotFound, DeviceBusy, IdempotencyConflict, InvalidTransition,
    JobNotOwned, MongoCallRepository,
)


@pytest.fixture
def repo():
    calls = mongomock.MongoClient().db.calls
    result = MongoCallRepository(calls)
    result.ensure_indexes()
    return result


def create(repo, key="request-0000000001", number="+967777405578", now=1000):
    return repo.create_call(principal="org-user", idempotency_key=key,
                            phone_number=number, now=now, queue_ttl=120)


def test_create_is_idempotent_and_rejects_changed_payload(repo):
    first = create(repo)
    replay = create(repo)
    assert replay["id"] == first["id"]
    with pytest.raises(IdempotencyConflict):
        create(repo, number="+967777405579")


def test_only_one_active_call_can_exist(repo):
    create(repo)
    with pytest.raises(DeviceBusy):
        create(repo, key="request-0000000002")


def test_claim_is_exclusive_and_lease_can_be_reclaimed(repo):
    created = create(repo)
    claimed = repo.claim_next(device_id="device-a", now=1001, queue_ttl=120,
                              cellular_ring_timeout=45, sip_ring_timeout=45,
                              max_call_seconds=1800)
    assert claimed["job"]["id"] == created["id"]
    assert repo.claim_next(device_id="device-b", now=1002, queue_ttl=120,
                          cellular_ring_timeout=45, sip_ring_timeout=45,
                          max_call_seconds=1800)["job"] is None
    reclaimed = repo.claim_next(device_id="device-b", now=1032, queue_ttl=120,
                                cellular_ring_timeout=45, sip_ring_timeout=45,
                                max_call_seconds=1800)
    assert reclaimed["job"]["id"] == created["id"]


def test_transition_requires_owner_and_enforces_sequence(repo):
    created = create(repo)
    repo.claim_next(device_id="device-a", now=1001, queue_ttl=120,
                    cellular_ring_timeout=45, sip_ring_timeout=45,
                    max_call_seconds=1800)
    with pytest.raises(JobNotOwned):
        repo.transition(call_id=created["id"], device_id="device-b",
                        state="ringing_cellular", now=1002)
    with pytest.raises(InvalidTransition):
        repo.transition(call_id=created["id"], device_id="device-a",
                        state="bridged", now=1002)
    ringing = repo.transition(call_id=created["id"], device_id="device-a",
                              state="ringing_cellular", now=1002)
    assert ringing["state"] == "ringing_cellular"
    assert repo.transition(call_id=created["id"], device_id="device-a",
                           state="ringing_cellular", now=1003)["state"] == "ringing_cellular"


def test_terminal_transition_releases_single_active_slot(repo):
    created = create(repo)
    repo.claim_next(device_id="device-a", now=1001, queue_ttl=120,
                    cellular_ring_timeout=45, sip_ring_timeout=45,
                    max_call_seconds=1800)
    repo.transition(call_id=created["id"], device_id="device-a",
                    state="failed", reason_code="test_failure", now=1002)
    next_call = create(repo, key="request-0000000002", now=1003)
    assert next_call["state"] == "queued"


def test_expired_queue_is_terminal_and_releases_slot(repo):
    create(repo)
    result = repo.claim_next(device_id="device-a", now=1121, queue_ttl=120,
                             cellular_ring_timeout=45, sip_ring_timeout=45,
                             max_call_seconds=1800)
    assert result["job"] is None
    next_call = create(repo, key="request-0000000002", now=1122)
    assert next_call["state"] == "queued"


def test_unknown_call_is_not_found(repo):
    with pytest.raises(CallNotFound):
        repo.transition(call_id="missing", device_id="device-a",
                        state="ringing_cellular", now=1000)
