ALTER TABLE user_outbox ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE user_outbox ADD COLUMN tracestate TEXT;
