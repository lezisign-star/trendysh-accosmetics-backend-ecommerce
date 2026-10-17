-- One-time maintenance jobs run by the server itself (e.g. the 2026-10-17 repair of Roniki gel photos with a pale patch):
-- a row here means the job has run and must never run again. The job's code is deleted afterwards; the row stays.
CREATE TABLE one_time_task (
    name    VARCHAR(100) PRIMARY KEY,
    done_at TIMESTAMPTZ  NOT NULL,
    result  TEXT
);
