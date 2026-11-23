"""Optional real-MongoDB concurrency checks.

Set MONGODB_TEST_URI to a dedicated Atlas test connection string and
MONGODB_TEST_DATABASE to a non-production database name. Each run creates
and drops only a uniquely suffixed database derived from that explicit name.
"""
from __future__ import annotations

import os
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from pymongo import MongoClient

from mongodb_calls import MongoCallRepository


URI = os.getenv("MONGODB_TEST_URI")
DATABASE = os.getenv("MONGODB_TEST_DATABASE")

pytestmark = pytest.mark.skipif(
    not URI or not DATABASE,
    reason="MONGODB_TEST_URI and MONGODB_TEST_DATABASE are required",
)


@pytest.fixture
def atlas_repo():
    if not DATABASE or DATABASE.lower() in {"admin", "local", "config"}:
        pytest.skip("MONGODB_TEST_DATABASE must name a dedicated test database")
    database_name = f"{DATABASE}_test_{uuid.uuid4().hex}"
    client = MongoClient(URI, serverSelectionTimeoutMS=5000,
                         connectTimeoutMS=5000, retryWrites=True)
    database = client[database_name]
    try:
        client.admin.command("ping")
        repo = MongoCallRepository(database["calls"])
        repo.ensure_indexes()
        yield repo
    finally:
        client.drop_database(database_name)
        client.close()


def test_concurrent_idempotency_and_claim_are_atomic_on_mongodb(atlas_repo):
    def create(_):
        return atlas_repo.create_call(
            organization_id="org-atlas-test", principal="member-atlas-test",
            idempotency_key="same-request-key", phone_number="+15555550123",
            now=1_900_000_000, queue_ttl=120,
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        created = list(pool.map(create, range(8)))
    assert len({item["id"] for item in created}) == 1

    def claim(device_number):
        return atlas_repo.claim_next(
            organization_id="org-atlas-test", device_id=f"device-{device_number}",
            now=1_900_000_001, queue_ttl=120, cellular_ring_timeout=45,
            sip_ring_timeout=45, max_call_seconds=1800,
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        claims = list(pool.map(claim, range(8)))
    winners = [result["job"]["id"] for result in claims if result["job"]]
    assert winners == [created[0]["id"]]
