--
-- Copyright (c) 2026 devlive-community/pipeforge
--
-- Licensed under the MIT License. See the LICENSE file in the
-- project root for full license text.
--
-- Sample data for the local development environment. The pipeforge user can read the
-- binlog (CDC) and write to every database in this instance.

GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'pipeforge'@'%';
GRANT ALL PRIVILEGES ON `pipeforge%`.* TO 'pipeforge'@'%';
FLUSH PRIVILEGES;

USE pipeforge;

CREATE TABLE customers (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(200),
    city       VARCHAR(100),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE orders (
    id          BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    ordered_at  DATETIME(3) NOT NULL,
    KEY idx_orders_customer (customer_id)
);

INSERT INTO customers (id, name, email, city) VALUES
    (1, 'Alice', 'alice@example.org', 'Berlin'),
    (2, 'Bob', 'bob@example.org', 'Shanghai'),
    (3, 'Carol', NULL, 'Toronto'),
    (4, 'Dave', 'dave@example.org', 'Sydney'),
    (5, 'Eve', 'eve@example.org', 'Tokyo');

INSERT INTO orders (id, customer_id, amount, status, ordered_at) VALUES
    (1001, 1, 120.50, 'PAID', '2026-01-02 10:00:00'),
    (1002, 2, 35.00, 'PAID', '2026-01-02 11:30:00'),
    (1003, 1, 980.99, 'SHIPPED', '2026-01-03 09:15:00'),
    (1004, 3, 12.00, 'CANCELLED', '2026-01-03 17:45:00'),
    (1005, 4, 450.10, 'PAID', '2026-01-04 08:05:00'),
    (1006, 5, 75.25, 'NEW', '2026-01-04 21:40:00');
