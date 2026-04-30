CREATE TABLE chat_sessions (
    id                  BIGSERIAL PRIMARY KEY,
    session_uuid        VARCHAR(36) NOT NULL UNIQUE,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    closed_at           TIMESTAMP,
    "createdDate"       TIMESTAMP NOT NULL,
    "lastModifiedDate"  TIMESTAMP NOT NULL
);
CREATE INDEX idx_chat_sessions_status ON chat_sessions(status);
CREATE INDEX idx_chat_sessions_uuid   ON chat_sessions(session_uuid);

CREATE TABLE chat_messages (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT NOT NULL REFERENCES chat_sessions(id),
    sender_type         VARCHAR(10) NOT NULL,
    content             TEXT NOT NULL,
    "createdDate"       TIMESTAMP NOT NULL,
    "lastModifiedDate"  TIMESTAMP NOT NULL
);
CREATE INDEX idx_chat_messages_session ON chat_messages(session_id);
