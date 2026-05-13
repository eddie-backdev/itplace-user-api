-- Recommendation cache batch boundary for personalized AI recommendations.
-- Production uses ddl-auto=validate, so apply before deploying entity changes.
-- Hibernate sends these camelCase identifiers unquoted, so PostgreSQL stores them lowercase.

ALTER TABLE recommendations
    ADD COLUMN IF NOT EXISTS cachebatchid VARCHAR(64),
    ADD COLUMN IF NOT EXISTS algorithmversion VARCHAR(64),
    ADD COLUMN IF NOT EXISTS active BOOLEAN;

UPDATE recommendations
SET algorithmversion = 'personalized-es-quality-v1'
WHERE algorithmversion IS NULL;

UPDATE recommendations
SET active = TRUE
WHERE active IS NULL;

UPDATE recommendations
SET cachebatchid = CONCAT('legacy-', userid, '-', TO_CHAR(createddate, 'YYYYMMDDHH24MISS'))
WHERE cachebatchid IS NULL;

ALTER TABLE recommendations
    ALTER COLUMN algorithmversion SET DEFAULT 'personalized-es-quality-v1',
    ALTER COLUMN algorithmversion SET NOT NULL,
    ALTER COLUMN active SET DEFAULT TRUE,
    ALTER COLUMN active SET NOT NULL,
    ALTER COLUMN cachebatchid SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_recommendations_user_active_created
    ON recommendations (userid, active, createddate DESC);

CREATE INDEX IF NOT EXISTS idx_recommendations_user_batch_active_rank
    ON recommendations (userid, cachebatchid, active, ranking);
