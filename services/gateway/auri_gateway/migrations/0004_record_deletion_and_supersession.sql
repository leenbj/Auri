-- Record deletion tombstone metadata and delivery supersession index (Ticket 04).

ALTER TABLE records ADD COLUMN deleted_by_device_id TEXT REFERENCES devices(device_id);
ALTER TABLE records ADD COLUMN delete_cancelled_delivery_ids_json TEXT;

CREATE INDEX IF NOT EXISTS idx_deliveries_record_dest
    ON deliveries(tenant_id, record_id, destination_id, revision);
