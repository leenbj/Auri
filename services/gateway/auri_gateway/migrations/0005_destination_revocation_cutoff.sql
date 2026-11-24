-- Revocation cutoff of a Destination (Ticket 22 re-review R1/R2; backfill hardened in round 3, S1, and again in round 4, F2).
-- Set when a credential is issued to a suspended Destination (its last credential had been revoked): texts stored
-- no later than the cutoff are invisible to every Agent read path of that Destination and never get a new Delivery.
-- NULL = no cutoff (never suspended, or suspended and not yet resumed).

ALTER TABLE destinations ADD COLUMN revocation_cutoff TEXT;

-- Suspensions that already ended in an existing gateway get their cutoff here. The database only holds wall-clock times,
-- and a gateway clock that was set back makes them lie: a text stored while the clock ran ahead can carry a later
-- stored_at than the replacement credential's issued_at, and the replacement credential's issued_at can even be earlier
-- than the revoke time of the credential it replaced. So this backfill trusts neither the order of the times nor a
-- single replacement time, and it fails closed: a Destination stays uncut only when its credential history PROVES that
-- every credential after the first replaced the one before it in one atomic step.
--
-- 1. Which Destinations get a cutoff (any one signature is enough):
--    (a) wall clock: some credential was issued after every earlier credential (by time) had been revoked strictly before it;
--    (b) predecessor chain: some credential is not the first one of its Destination (insertion order, rowid) and the credential
--        inserted right before it (same Destination) was not revoked at the very instant it was issued. A rotation revokes
--        the old credential and issues the new one at the same instant, in one transaction (store.py
--        issue_destination_credential uses one timestamp), so the old one is always the immediate predecessor. A merely
--        coincidental equal time (some OTHER credential revoked at that instant) is not a rotation, and a NULL revoke time
--        is never equal to anything;
--    (c) two credentials of one Destination were issued at the same instant: their order is unknowable, so is any
--        "revoked just before it was issued" chain through them;
--    (d) trace of a suspension: a CANCELLED delivery of the Destination was cancelled at the very instant a credential was
--        revoked, and a later credential exists. Revoking the last credential cancels the queued deliveries at that instant
--        (store.py revoke_credential), a rotation cancels nothing; so this is a suspension even when its revoke and the
--        reissue share a timestamp.
--    What is left uncut is a history in which every pair is a clean, single, atomic-looking rotation. Old data cannot do
--    better: a single equal revoke/issue pair with none of the signatures above is indistinguishable from a rotation, and
--    treating it as a suspension would silently strand the texts a plain rotation leaves queued for the new credential.
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
WHERE
    -- (a) wall clock
    EXISTS (
        SELECT 1 FROM destination_credentials AS c
        WHERE c.tenant_id = destinations.tenant_id AND c.destination_id = destinations.destination_id
          AND EXISTS (
              SELECT 1 FROM destination_credentials AS p
              WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.issued_at < c.issued_at
          )
          AND NOT EXISTS (
              SELECT 1 FROM destination_credentials AS p
              WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.issued_at < c.issued_at
                AND (p.revoked_at IS NULL OR p.revoked_at >= c.issued_at)
          )
    )
    -- (b) predecessor chain
    OR EXISTS (
        SELECT 1 FROM destination_credentials AS c
        WHERE c.tenant_id = destinations.tenant_id AND c.destination_id = destinations.destination_id
          AND EXISTS (
              SELECT 1 FROM destination_credentials AS p
              WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id AND p.rowid < c.rowid
          )
          AND NOT EXISTS (
              SELECT 1 FROM destination_credentials AS p
              WHERE p.tenant_id = c.tenant_id AND p.destination_id = c.destination_id
                AND p.rowid = (
                    SELECT MAX(q.rowid) FROM destination_credentials AS q
                    WHERE q.tenant_id = c.tenant_id AND q.destination_id = c.destination_id AND q.rowid < c.rowid
                )
                AND p.revoked_at = c.issued_at
          )
    )
    -- (c) two credentials issued at the same instant
    OR EXISTS (
        SELECT 1 FROM destination_credentials AS c
        JOIN destination_credentials AS d
          ON d.tenant_id = c.tenant_id AND d.destination_id = c.destination_id
         AND d.credential_id <> c.credential_id AND d.issued_at = c.issued_at
        WHERE c.tenant_id = destinations.tenant_id AND c.destination_id = destinations.destination_id
    )
    -- (d) a delivery was cancelled at the instant a credential was revoked, and a later credential exists
    OR EXISTS (
        SELECT 1 FROM destination_credentials AS p
        JOIN deliveries AS d
          ON d.tenant_id = p.tenant_id AND d.destination_id = p.destination_id
         AND d.state = 'CANCELLED' AND d.cancelled_at = p.revoked_at
        WHERE p.tenant_id = destinations.tenant_id AND p.destination_id = destinations.destination_id
          AND EXISTS (
              SELECT 1 FROM destination_credentials AS c
              WHERE c.tenant_id = p.tenant_id AND c.destination_id = p.destination_id AND c.rowid > p.rowid
          )
    );
