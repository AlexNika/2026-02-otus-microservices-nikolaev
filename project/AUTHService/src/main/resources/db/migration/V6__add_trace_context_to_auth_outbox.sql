ALTER TABLE auth_outbox ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE auth_outbox ADD COLUMN tracestate TEXT;
