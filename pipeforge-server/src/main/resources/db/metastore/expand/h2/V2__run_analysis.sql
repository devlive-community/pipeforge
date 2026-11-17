--
-- Copyright (c) 2026 devlive-community/pipeforge
--
-- Licensed under the MIT License. See the LICENSE file in the
-- project root for full license text.
--
-- Expand migration 2: the run analysis of a run, kept when it ends (a nullable column: the previous
-- version ignores it).

ALTER TABLE job_run ADD COLUMN analysis_json CLOB;
