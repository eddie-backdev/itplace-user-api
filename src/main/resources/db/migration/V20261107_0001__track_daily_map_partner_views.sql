CREATE TABLE map_partner_view_daily (
    id BIGSERIAL PRIMARY KEY,
    partner_id BIGINT NOT NULL REFERENCES partner(partnerId) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(userId) ON DELETE CASCADE,
    anonymous_session_hash CHAR(64),
    view_day DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_map_partner_view_daily_identity CHECK (
        (user_id IS NOT NULL AND anonymous_session_hash IS NULL) OR
        (user_id IS NULL AND anonymous_session_hash IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_map_partner_view_user_day
    ON map_partner_view_daily (user_id, partner_id, view_day)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_map_partner_view_session_day
    ON map_partner_view_daily (anonymous_session_hash, partner_id, view_day)
    WHERE user_id IS NULL;

CREATE INDEX idx_map_partner_view_partner_day
    ON map_partner_view_daily (partner_id, view_day);

CREATE INDEX idx_map_partner_view_retention
    ON map_partner_view_daily (view_day);
