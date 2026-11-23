--
-- Copyright (c) 2026 devlive-community/pipeforge
--
-- Licensed under the MIT License. See the LICENSE file in the
-- project root for full license text.
--
-- Expand migration 3: the checkpoints and savepoints of runs, written by the master, and where an
-- attempt of a run started from. Only additions: the previous version ignores them. Times are UTC.

CREATE TABLE checkpoint (
    run_id         VARCHAR(64)   NOT NULL,
    checkpoint_id  BIGINT        NOT NULL,
    job_name       VARCHAR(255)  NOT NULL,
    attempt        INT           NOT NULL,
    type           VARCHAR(16)   NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    trigger_reason VARCHAR(32)   NOT NULL,
    name           VARCHAR(255),
    triggered_at   TIMESTAMP(3)  NOT NULL,
    completed_at   TIMESTAMP(3),
    size_bytes     BIGINT,
    path           VARCHAR(1024) NOT NULL,
    error_code     VARCHAR(32),
    error_message  TEXT,
    tasks_json     TEXT,
    PRIMARY KEY (run_id, checkpoint_id)
);

CREATE INDEX checkpoint_job ON checkpoint (job_name, status, checkpoint_id);
CREATE INDEX checkpoint_status ON checkpoint (status, triggered_at);

ALTER TABLE job_run_attempt ADD COLUMN restore_path VARCHAR(1024);
ALTER TABLE job_run_attempt ADD COLUMN restore_checkpoint_id BIGINT;
ALTER TABLE job_run_attempt ADD COLUMN last_checkpoint_id BIGINT;
