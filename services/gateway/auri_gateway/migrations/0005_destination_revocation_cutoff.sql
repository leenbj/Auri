-- Revocation cutoff of a Destination (Ticket 22 re-review R1/R2; backfill hardened in round 3, S1).
-- Set when a credential is issued to a suspended Destination (its last credential had been revoked): texts stored
-- no later than the cutoff are invisible to every Agent read path of that Destination and never get a new Delivery.
-- NULL = no cutoff (never suspended, or suspended and not yet resumed).

ALTER TABLE destinations ADD COLUMN revocation_cutoff TEXT;

-- Suspensions that already ended in an existing gateway get their cutoff here. The database only holds wall-clock times,
-- and a gateway clock that was set back makes them lie: a text stored while the clock ran ahead can carry a later
-- stored_at than the replacement credential's issued_at, and the replacement credential's issued_at can even be earlier
-- than the revoke time of the credential it replaced. So this backfill trusts neither the order of the times nor a
-- single replacement time:
--
-- 1. Which Destinations had a finished suspension (either signature is enough, so the old reading is still honoured):
--    (a) wall clock: some credential was issued after every earlier credential (by time) had been revoked strictly before it;
--    (b) clock-proof: some credential is not the first one of its Destination (insertion order, rowid) and no credential
--        was revoked at the very instant it was issued. A rotation revokes the old credential and issues the new one at
--        the same instant (store.py issue_destination_credential uses one timestamp); a suspension leaves no such pair.
--    A Destination that was only rotated has neither, so it gets no cutoff and its queued texts stay claimable.
-- 2. Their cutoff is the latest of: every issue and revoke time of the Destination's credentials, and the stored_at of
--    every revision in its tenant at upgrade time. "Texts stored after the replacement credential" and "texts stored
--    before the suspension under a fast clock" look the same in old data, so everything stored before the upgrade stays
--    invisible to the replacement credential (that can include unclaimed texts stored after the replacement credential
--    but before the upgrade: the conservative price). Texts stored after the upgrade are unaffected. Visibility needs
--    stored_at strictly greater than the cutoff, so a text stored at the cutoff instant is hidden too.
UPDATE destinations
SET revocation_cutoff = MAX(
    COALESCE((
        SELECT MAX(c.issued_at) FROM destination_credentials AS c
        WHERE c.tenant_id = destinations.tenant_id AND c.destination_id = destinations.destination_id
    ), ''),
    COALESCE((
        SELECT MAX(c.revoked_at) FROM destination_credentials AS c
        WHERE c.tenant_id = destinations.tenant_id AND c.destination_id = destinations.destination_id
    ), ''),
    COALESCE((SELECT MAX(r.stored_at) FROM revisions AS r WHERE r.tenant_id = destinations.tenant_id), '')
)
WHERE EXISTS (
    SELECT 1 FROM destination_credentials AS c
    WHERE c.tenant_id = destinations.tenant_id AND c.destination_id = destinations.destination_id
      AND (
          (
              EXISTS (
                  SELECT 1 FROM destination_credentials AS p
                  WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.issued_at < c.issued_at
              )
              AND NOT EXISTS (
                  SELECT 1 FROM destination_credentials AS p
                  WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.issued_at < c.issued_at
                    AND (p.revoked_at IS NULL OR p.revoked_at >= c.issued_at)
              )
          )
          OR (
              EXISTS (
                  SELECT 1 FROM destination_credentials AS p
                  WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.rowid < c.rowid
              )
              AND NOT EXISTS (
                  SELECT 1 FROM destination_credentials AS q
                  WHERE q.tenant_id = c.tenant_id AND q.destination_id = c.destination_id
                    AND q.credential_id <> c.credential_id AND q.revoked_at = c.issued_at
              )
          )
      )
);
