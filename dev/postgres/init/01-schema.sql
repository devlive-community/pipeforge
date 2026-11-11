--
-- Copyright (c) 2026 devlive-community/pipeforge
--
-- Licensed under the MIT License. See the LICENSE file in the
-- project root for full license text.
--
-- Sample data for the local development environment. wal_level=logical is set on the
-- command line, so the pipeforge user can create logical replication slots (CDC).

ALTER ROLE pipeforge WITH REPLICATION;

CREATE SCHEMA inventory;

CREATE TABLE inventory.products (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    price       NUMERIC(12, 2) NOT NULL,
    tags        TEXT[],
    attributes  JSONB,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE inventory.products REPLICA IDENTITY FULL;

INSERT INTO inventory.products (id, name, price, tags, attributes) VALUES
    (1, 'Keyboard', 49.90, ARRAY['input', 'usb'], '{"layout": "ansi"}'),
    (2, 'Mouse', 19.90, ARRAY['input'], '{"dpi": 1600}'),
    (3, 'Monitor', 229.00, ARRAY['display'], '{"size": 27}'),
    (4, 'Cable', 4.50, NULL, NULL);

-- Empty target schema for MySQL -> PostgreSQL examples.
CREATE SCHEMA sync;
