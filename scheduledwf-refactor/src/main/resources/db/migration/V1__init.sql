CREATE TABLE workflow_schedules (
  id UUID PRIMARY KEY,
  namespace VARCHAR(100) NOT NULL,
  name VARCHAR(200),
  workflow_name VARCHAR(200) NOT NULL,
  workflow_version INT NOT NULL DEFAULT 1,
  schedule_type VARCHAR(20) NOT NULL,
  cron_expression VARCHAR(200),
  run_at TIMESTAMPTZ,
  next_run TIMESTAMPTZ NOT NULL,
  max_concurrent_runs INT NOT NULL DEFAULT 1,
  last_triggered_at TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  payload JSONB,
  created_by VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE schedule_history (
  id UUID PRIMARY KEY,
  schedule_id UUID REFERENCES workflow_schedules(id),
  run_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(20),
  response JSONB,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shedlock(
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
