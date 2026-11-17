-- Initial schema for Auri Gateway (Ticket 01: pairing & revision store).
-- Every tenant-scoped entity carries tenant_id derived solely from auth (ADR-0013).

CREATE TABLE IF NOT EXISTS gateway_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    gateway_id TEXT NOT NULL,
    default_tenant_id TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tenants (
    tenant_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    pairing_code_id TEXT PRIMARY KEY,
    code_hash TEXT NOT NULL UNIQUE,
    tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    redeemed_at TEXT,
    redeemed_device_id TEXT
);

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id),
    token_hash TEXT NOT NULL UNIQUE,
    platform TEXT NOT NULL,
    app_version TEXT NOT NULL,
    os_build TEXT NOT NULL,
    display_name TEXT NOT NULL,
    paired_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS records (
    tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id),
    record_id TEXT NOT NULL,
    origin_device_id TEXT NOT NULL REFERENCES devices(device_id),
    created_at TEXT NOT NULL,
    deleted_at TEXT,
    PRIMARY KEY (tenant_id, record_id)
);

CREATE TABLE IF NOT EXISTS revisions (
    tenant_id TEXT NOT NULL,
    record_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    origin_device_id TEXT NOT NULL REFERENCES devices(device_id),
    operation_id TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL,
    stored_at TEXT NOT NULL,
    text TEXT NOT NULL,
    text_sha256 TEXT NOT NULL,
    source TEXT NOT NULL,
    language TEXT NOT NULL,
    review_state TEXT NOT NULL,
    execution_location TEXT NOT NULL,
    engine TEXT,
    model_id TEXT,
    model_version TEXT,
    confidence REAL,
    segments_json TEXT NOT NULL,
    capture_json TEXT NOT NULL,
    intent TEXT NOT NULL,
    destination_ids_json TEXT NOT NULL,
    superseded_revisions_json TEXT NOT NULL,
    deliveries_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id, record_id, revision),
    FOREIGN KEY (tenant_id, record_id) REFERENCES records(tenant_id, record_id)
);

CREATE INDEX IF NOT EXISTS idx_pairing_codes_expires_at ON pairing_codes(expires_at);
CREATE INDEX IF NOT EXISTS idx_devices_tenant_id ON devices(tenant_id);
CREATE INDEX IF NOT EXISTS idx_revisions_record_state ON revisions(tenant_id, record_id, state);
