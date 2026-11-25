CREATE TABLE auth_grant_consumption (
    identity_digest CHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auth_grant_consumption_expires_at
    ON auth_grant_consumption (expires_at);
