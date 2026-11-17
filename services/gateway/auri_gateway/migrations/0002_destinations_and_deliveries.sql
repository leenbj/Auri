-- Destinations, hashed destination credentials, and per-destination deliveries (Ticket 02).

CREATE TABLE IF NOT EXISTS destinations (
    tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id),
    destination_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    send_mode TEXT NOT NULL,
    allowed_intents_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    created_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
    credential_issued INTEGER NOT NULL DEFAULT 0,
    revoked_at TEXT,
    PRIMARY KEY (tenant_id, destination_id)
);

CREATE TABLE IF NOT EXISTS destination_credentials (
    credential_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    destination_id TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    issued_by_device_id TEXT NOT NULL REFERENCES devices(device_id),
    issued_at TEXT NOT NULL,
    revoked_at TEXT,
    FOREIGN KEY (tenant_id, destination_id) REFERENCES destinations(tenant_id, destination_id)
);

CREATE TABLE IF NOT EXISTS deliveries (
    delivery_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    record_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    destination_id TEXT NOT NULL,
    state TEXT NOT NULL,
    intent TEXT NOT NULL,
    supersedes_revisions_json TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL,
    lease_token_hash TEXT UNIQUE,
    lease_expires_at TEXT,
    acknowledged_at TEXT,
    ack_note TEXT,
    rejected_at TEXT,
    reject_reason TEXT,
    cancelled_at TEXT,
    FOREIGN KEY (tenant_id, record_id, revision) REFERENCES revisions(tenant_id, record_id, revision),
    FOREIGN KEY (tenant_id, destination_id) REFERENCES destinations(tenant_id, destination_id),
    UNIQUE (tenant_id, record_id, revision, destination_id)
);

CREATE INDEX IF NOT EXISTS idx_destinations_tenant_created ON destinations(tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_destination_credentials_dest ON destination_credentials(tenant_id, destination_id, revoked_at);
CREATE INDEX IF NOT EXISTS idx_deliveries_dest_state ON deliveries(tenant_id, destination_id, state);
CREATE INDEX IF NOT EXISTS idx_deliveries_record ON deliveries(tenant_id, record_id, revision);
