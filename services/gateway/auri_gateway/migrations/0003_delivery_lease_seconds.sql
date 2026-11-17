-- Lease length the Agent asked for at claim time, so renewing grants the same length (Ticket 03).

ALTER TABLE deliveries ADD COLUMN lease_seconds INTEGER;
