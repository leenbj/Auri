-- Revocation cutoff of a Destination (Ticket 22 re-review R1/R2).
-- Set when a credential is issued to a suspended Destination (its last credential had been revoked): texts stored
-- no later than the cutoff are invisible to every Agent read path of that Destination and never get a new Delivery.
-- NULL = no cutoff (never suspended, or suspended and not yet resumed).

ALTER TABLE destinations ADD COLUMN revocation_cutoff TEXT;

-- Suspensions that already ended in an existing gateway: the latest credential issued after a gap, i.e. when every
-- earlier credential had been revoked strictly before it (a rotation revokes the old one at the very instant it issues).
UPDATE destinations
SET revocation_cutoff = (
    SELECT MAX(c.issued_at)
    FROM destination_credentials AS c
    WHERE c.tenant_id = destinations.tenant_id
      AND c.destination_id = destinations.destination_id
      AND EXISTS (
          SELECT 1 FROM destination_credentials AS p
          WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.issued_at < c.issued_at
      )
      AND NOT EXISTS (
          SELECT 1 FROM destination_credentials AS p
          WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.issued_at < c.issued_at
            AND (p.revoked_at IS NULL OR p.revoked_at >= c.issued_at)
      )
);
