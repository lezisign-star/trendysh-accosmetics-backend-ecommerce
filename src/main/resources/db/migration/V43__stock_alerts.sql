-- Stock alerts + forecasting (2026-09-30). Staff opt in to alert emails per account (default: everyone,
-- an admin can switch people off in Settings), and one singleton settings row holds the numbers the
-- forecast needs. last_digest_on stops the daily digest going out twice if the app restarts at 07:00.
ALTER TABLE staff_user ADD COLUMN receives_stock_alerts BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE stock_alert_settings (
    id             UUID PRIMARY KEY,
    lead_time_days INT         NOT NULL DEFAULT 7 CHECK (lead_time_days BETWEEN 0 AND 120),
    safety_days    INT         NOT NULL DEFAULT 3 CHECK (safety_days BETWEEN 0 AND 60),
    digest_enabled BOOLEAN     NOT NULL DEFAULT true,
    last_digest_on DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO stock_alert_settings (id) VALUES ('00000000-0000-0000-0000-0000000000a1');
