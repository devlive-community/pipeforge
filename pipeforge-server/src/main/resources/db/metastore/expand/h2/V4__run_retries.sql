--
-- Copyright (c) 2026 devlive-community/pipeforge
--
-- Licensed under the MIT License. See the LICENSE file in the
-- project root for full license text.
--
-- Expand migration 4: run-level retries. A run keeps what a new attempt needs to be submitted the
-- same way (its variables, scheduled time and plugin versions), its current attempt and when it is
-- retried; metric samples name the attempt they belong to. Only additions: the previous version
-- ignores them. Times are UTC.

ALTER TABLE job_run ADD COLUMN attempt INT;
ALTER TABLE job_run ADD COLUMN retry_at TIMESTAMP(3);
ALTER TABLE job_run ADD COLUMN submission_json CLOB;
ALTER TABLE job_run_metrics ADD COLUMN attempt INT;
