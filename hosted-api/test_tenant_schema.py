import sqlite3
from pathlib import Path

import pytest


MIGRATION = Path(__file__).parent / "migrations" / "001_tenant_device_foundation.sql"


def migrated_db():
    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys = ON")
    # The current hosted API owns this table; migration must remain additive.
    connection.execute("CREATE TABLE calls (id TEXT PRIMARY KEY)")
    connection.executescript(MIGRATION.read_text(encoding="utf-8"))
    return connection


def test_tenant_foundation_creates_expected_tables():
    connection = migrated_db()
    names = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    assert {
        "organizations",
        "organization_memberships",
        "organization_api_credentials",
        "branches",
        "devices",
        "device_enrollment_tokens",
        "device_credentials",
        "device_heartbeats",
        "device_events",
        "device_configs",
        "device_commands",
        "call_tenant_assignments",
    }.issubset(names)


def test_device_identity_is_unique_inside_organization():
    connection = migrated_db()
    connection.execute(
        "INSERT INTO organizations(id,slug,name,created_at,updated_at) "
        "VALUES ('org-a','alpha','Alpha',1,1)"
    )
    connection.execute(
        "INSERT INTO organizations(id,slug,name,created_at,updated_at) "
        "VALUES ('org-b','beta','Beta',1,1)"
    )
    values = ("device-a", "org-a", "android-001", "Front desk", 1, 1)
    connection.execute(
        "INSERT INTO devices(id,organization_id,external_device_id,display_name,created_at,updated_at) "
        "VALUES (?,?,?,?,?,?)",
        values,
    )
    with pytest.raises(sqlite3.IntegrityError):
        connection.execute(
            "INSERT INTO devices(id,organization_id,external_device_id,display_name,created_at,updated_at) "
            "VALUES (?,?,?,?,?,?)",
            ("device-b", "org-a", "android-001", "Duplicate", 1, 1),
        )
    # The same external ID is valid for a different organization.
    connection.execute(
        "INSERT INTO devices(id,organization_id,external_device_id,display_name,created_at,updated_at) "
        "VALUES (?,?,?,?,?,?)",
        ("device-c", "org-b", "android-001", "Beta handset", 1, 1),
    )


def test_membership_role_and_call_assignment_foreign_keys():
    connection = migrated_db()
    connection.execute(
        "INSERT INTO organizations(id,slug,name,created_at,updated_at) "
        "VALUES ('org-a','alpha','Alpha',1,1)"
    )
    with pytest.raises(sqlite3.IntegrityError):
        connection.execute(
            "INSERT INTO organization_memberships "
            "(organization_id,user_id,role,created_at,updated_at) "
            "VALUES ('org-a','user-1','superadmin',1,1)"
        )
    connection.execute("INSERT INTO calls(id) VALUES ('call-1')")
    connection.execute(
        "INSERT INTO call_tenant_assignments(call_id,organization_id,created_at) "
        "VALUES ('call-1','org-a',1)"
    )
    with pytest.raises(sqlite3.IntegrityError):
        connection.execute(
            "INSERT INTO call_tenant_assignments(call_id,organization_id,created_at) "
            "VALUES ('missing-call','org-a',1)"
        )
