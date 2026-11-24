-- Durable monotonic revocation cutoff order (Ticket 22 round 4 F6).
-- Wall-clock stored_at comparisons break when the gateway clock ran ahead (e.g. 2099) and was later corrected:
-- a future stored_at pushes a wall-clock cutoff into the future, permanently blocking deliveries for revisions
-- stored after clock correction.
--
-- Give every revision a durable monotonic store_seq allocated from revision_sequence (immune to VACUUM and
-- clock changes), and store each Destination's cutoff as revocation_cutoff_seq (NULL = uncut).
-- Existing cutoffs from migration 0005 or earlier runtime are migrated conservatively to the tenant's latest
-- store_seq at upgrade time (0 when the tenant has no revisions yet), matching S1/F2 fail-closed semantics.

ALTER TABLE revisions ADD COLUMN store_seq INTEGER;

UPDATE revisions
SET store_seq = (
    SELECT COUNT(*) FROM revisions AS earlier WHERE earlier.rowid <= revisions.rowid
)
WHERE store_seq IS NULL;

CREATE TABLE IF NOT EXISTS revision_sequence (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_seq INTEGER NOT NULL
);

INSERT OR REPLACE INTO revision_sequence (id, last_seq)
VALUES (1, COALESCE((SELECT MAX(store_seq) FROM revisions), 0));

ALTER TABLE destinations ADD COLUMN revocation_cutoff_seq INTEGER;

UPDATE destinations
SET revocation_cutoff_seq = COALESCE(
    (SELECT MAX(r.store_seq) FROM revisions AS r WHERE r.tenant_id = destinations.tenant_id),
    0
)
WHERE revocation_cutoff IS NOT NULL
  AND revocation_cutoff_seq IS NULL;
