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
