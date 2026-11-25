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


def create(repo, key="request-0000000001", number="+967777405578",
           now=1000, organization_id="org-a"):
    return repo.create_call(organization_id=organization_id, principal="org-user",
                            idempotency_key=key, phone_number=number,
                            now=now, queue_ttl=120)


def claim(repo, device="device-a", now=1001, organization_id="org-a"):
    return repo.claim_next(organization_id=organization_id, device_id=device,
                           now=now, queue_ttl=120, cellular_ring_timeout=45,
                           sip_ring_timeout=45, max_call_seconds=1800)


def transition(repo, call_id, state, device="device-a", now=1002,
               organization_id="org-a"):
    return repo.transition(organization_id=organization_id, call_id=call_id,
                           device_id=device, state=state, now=now)


def test_create_is_idempotent_and_rejects_changed_payload(repo):
    first = create(repo)
    replay = create(repo)
    assert replay["id"] == first["id"]
    with pytest.raises(IdempotencyConflict):
        create(repo, number="+967777405579")


def test_idempotency_and_active_slot_are_isolated_by_organization(repo):
    org_a = create(repo)
    org_b = create(repo, organization_id="org-b")
    assert org_a["id"] != org_b["id"]
    assert create(repo, organization_id="org-b")["id"] == org_b["id"]


def test_new_call_replaces_active_call_per_organization(repo):
    first = create(repo)
    second = create(repo, key="request-0000000002")
    assert second["id"] != first["id"]
    assert repo.calls.find_one({"_id": first["id"]})["reason_code"] == "superseded"
    assert create(repo, key="request-0000000001", organization_id="org-b")["state"] == "queued"


def test_claim_is_exclusive_and_lease_can_be_reclaimed(repo):
    created = create(repo)
    assert claim(repo)["job"]["id"] == created["id"]
    assert claim(repo, device="device-b", now=1002)["job"] is None
    assert claim(repo, device="device-b", now=1032)["job"]["id"] == created["id"]


def test_claim_cannot_cross_organization_boundary(repo):
    call = create(repo, organization_id="org-a")
    assert claim(repo, organization_id="org-b")["job"] is None
    assert claim(repo, organization_id="org-a")["job"]["id"] == call["id"]


def test_transition_requires_organization_and_device_owner(repo):
    created = create(repo)
    claim(repo)
    with pytest.raises(CallNotFound):
        transition(repo, created["id"], "ringing_cellular", organization_id="org-b")
    with pytest.raises(JobNotOwned):
        transition(repo, created["id"], "ringing_cellular", device="device-b")
    with pytest.raises(InvalidTransition):
        transition(repo, created["id"], "bridged")
    assert transition(repo, created["id"], "ringing_cellular")["state"] == "ringing_cellular"
    assert transition(repo, created["id"], "ringing_cellular", now=1003)["state"] == "ringing_cellular"


def test_terminal_transition_releases_organization_active_slot(repo):
    created = create(repo)
    claim(repo)
    transition(repo, created["id"], "failed")
    next_call = create(repo, key="request-0000000002", now=1003)
    assert next_call["state"] == "queued"


def test_expired_queue_is_terminal_and_releases_slot(repo):
    create(repo)
    assert claim(repo, now=1121)["job"] is None
    assert create(repo, key="request-0000000002", now=1122)["state"] == "queued"


def test_unknown_call_is_not_found(repo):
    with pytest.raises(CallNotFound):
        transition(repo, "missing", "ringing_cellular", now=1000)



def test_claim_replay_by_same_device_returns_the_same_job(repo):
    created = create(repo)
    first = claim(repo, device="device-a", now=1001)
    replay = claim(repo, device="device-a", now=1002)
    assert first["job"]["id"] == created["id"]
    assert replay["job"]["id"] == created["id"]
