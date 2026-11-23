-- Hosted Call API tenant/device foundation (SQLite).
-- Additive migration: does not alter or delete existing calls or SIP settings.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS organizations (
  id TEXT PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','suspended','closed')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS organization_memberships (
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('owner','admin','operator','viewer')),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','invited','suspended')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_memberships_user ON organization_memberships(user_id, status);

CREATE TABLE IF NOT EXISTS organization_api_credentials (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  label TEXT NOT NULL,
  user_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  token_prefix TEXT NOT NULL,
  scopes TEXT NOT NULL DEFAULT '[]',
  last_used_at INTEGER,
  expires_at INTEGER,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_org_credentials_org ON organization_api_credentials(organization_id, revoked_at);

CREATE TABLE IF NOT EXISTS branches (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  code TEXT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (organization_id, code)
);
CREATE INDEX IF NOT EXISTS idx_branches_org_status ON branches(organization_id, status);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  external_device_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  branch_name TEXT,
  branch_id TEXT REFERENCES branches(id) ON DELETE SET NULL,
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','active','disabled','revoked')),
  presence TEXT NOT NULL DEFAULT 'offline'
    CHECK (presence IN ('online','unstable','offline')),
  app_id TEXT,
  app_version TEXT,
  android_version TEXT,
  manufacturer TEXT,
  model TEXT,
  last_seen_at INTEGER,
  last_ip TEXT,
  battery_level INTEGER CHECK (battery_level IS NULL OR battery_level BETWEEN 0 AND 100),
  network_type TEXT,
  internet_available INTEGER,
  sip_status TEXT NOT NULL DEFAULT 'unknown',
  sim_status TEXT NOT NULL DEFAULT 'unknown',
  agent_status TEXT NOT NULL DEFAULT 'unknown',
  active_call_id TEXT,
  config_version INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (organization_id, external_device_id)
);
CREATE INDEX IF NOT EXISTS idx_devices_org_presence ON devices(organization_id, presence, status);
CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen_at);

CREATE TABLE IF NOT EXISTS device_enrollment_tokens (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  label TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses > 0),
  used_count INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
  revoked_at INTEGER,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_enrollment_org ON device_enrollment_tokens(organization_id, expires_at);

CREATE TABLE IF NOT EXISTS device_credentials (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  token_prefix TEXT NOT NULL,
  last_used_at INTEGER,
  expires_at INTEGER,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_credentials_device ON device_credentials(device_id, revoked_at);

CREATE TABLE IF NOT EXISTS device_heartbeats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  received_at INTEGER NOT NULL,
  battery_level INTEGER,
  network_type TEXT,
  internet_available INTEGER,
  sip_status TEXT,
  sim_status TEXT,
  agent_status TEXT,
  active_call_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_heartbeats_device_time ON device_heartbeats(device_id, received_at DESC);

CREATE TABLE IF NOT EXISTS device_events (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  event_type TEXT NOT NULL,
  severity TEXT NOT NULL DEFAULT 'info'
    CHECK (severity IN ('debug','info','warning','error','critical')),
  event_code TEXT NOT NULL,
  details_json TEXT NOT NULL DEFAULT '{}',
  occurred_at INTEGER NOT NULL,
  received_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_events_org_time ON device_events(organization_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_events_device_time ON device_events(device_id, received_at DESC);

CREATE TABLE IF NOT EXISTS device_configs (
  device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  version INTEGER NOT NULL DEFAULT 1,
  config_json TEXT NOT NULL DEFAULT '{}',
  updated_by TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS device_commands (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  command_type TEXT NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','delivered','acknowledged','completed','failed','expired','cancelled')),
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  delivered_at INTEGER,
  acknowledged_at INTEGER,
  completed_at INTEGER,
  result_code TEXT,
  result_summary TEXT
);
CREATE INDEX IF NOT EXISTS idx_device_commands_queue ON device_commands(device_id, status, created_at);

-- Tenant assignment is kept separate to make this migration safe for existing calls.
-- New tenant-aware call creation must write this row in the same transaction as calls.
CREATE TABLE IF NOT EXISTS call_tenant_assignments (
  call_id TEXT PRIMARY KEY REFERENCES calls(id) ON DELETE CASCADE,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
  device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_call_tenant_org ON call_tenant_assignments(organization_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_call_tenant_device ON call_tenant_assignments(device_id, created_at DESC);


-- Local-development limiter fallback. Multi-instance deployments must use Atlas.
CREATE TABLE IF NOT EXISTS request_limits (
  key_hash TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (key_hash, window_start)
);

-- Append-only organization administration audit trail; never stores raw credentials.
CREATE TABLE IF NOT EXISTS admin_audit_events (
  id TEXT PRIMARY KEY,
  organization_id TEXT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  actor_user_id TEXT NOT NULL,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_org_created
  ON admin_audit_events(organization_id, created_at DESC);
