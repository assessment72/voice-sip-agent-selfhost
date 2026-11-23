from __future__ import annotations

import pytest

import mongodb_store


class FakeCollection:
    def __init__(self):
        self.index_batches = []

    def create_indexes(self, indexes):
        self.index_batches.append(indexes)
        return [index.document["name"] for index in indexes]


class FakeDatabase:
    def __init__(self):
        self.collections = {}

    def __getitem__(self, name):
        return self.collections.setdefault(name, FakeCollection())

    def command(self, name):
        assert name == "ping"
        return {"ok": 1}


def test_ensure_indexes_creates_expected_collections_and_is_repeatable():
    db = FakeDatabase()

    mongodb_store.ensure_indexes(db)
    first_counts = {name: len(col.index_batches) for name, col in db.collections.items()}
    mongodb_store.ensure_indexes(db)

    assert len(db.collections) >= 13
    assert all(count == 1 for count in first_counts.values())
    assert all(len(col.index_batches) == 2 for col in db.collections.values())


def test_call_idempotency_index_is_unique():
    db = FakeDatabase()
    mongodb_store.ensure_indexes(db)

    indexes = db["calls"].index_batches[0]
    idem = next(index for index in indexes
                if index.document["name"] == "uq_call_principal_idempotency")
    assert idem.document["unique"] is True
    assert list(idem.document["key"].items()) == [("principal", 1), ("idempotency_key", 1)]


def test_branch_code_uniqueness_only_applies_to_string_codes():
    db = FakeDatabase()
    mongodb_store.ensure_indexes(db)

    indexes = db["branches"].index_batches[0]
    branch_code = next(index for index in indexes
                       if index.document["name"] == "uq_branch_org_code")
    assert branch_code.document["unique"] is True
    assert branch_code.document["partialFilterExpression"] == {
        "code": {"$type": "string"}
    }


def test_client_requires_uri(monkeypatch):
    mongodb_store.get_client.cache_clear()
    monkeypatch.delenv("MONGODB_URI", raising=False)
    with pytest.raises(mongodb_store.MongoConfigurationError):
        mongodb_store.get_client()
    mongodb_store.get_client.cache_clear()


def test_database_requires_name(monkeypatch):
    mongodb_store.get_client.cache_clear()
    monkeypatch.setenv("MONGODB_URI", "mongodb://localhost:27017")
    monkeypatch.delenv("MONGODB_DATABASE", raising=False)
    with pytest.raises(mongodb_store.MongoConfigurationError):
        mongodb_store.get_database()
    mongodb_store.get_client.cache_clear()


def test_health_snapshot_fails_closed_when_unconfigured(monkeypatch):
    mongodb_store.get_client.cache_clear()
    monkeypatch.delenv("MONGODB_URI", raising=False)
    monkeypatch.delenv("MONGODB_DATABASE", raising=False)
    assert mongodb_store.health_snapshot() == {
        "status": "unavailable",
        "backend": "mongodb",
    }
    mongodb_store.get_client.cache_clear()
