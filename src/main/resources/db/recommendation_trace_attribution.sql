-- Personalized recommendation attribution and rank trace persistence.
-- Production uses ddl-auto=validate, so apply before deploying entity changes.
-- Hibernate sends these camelCase identifiers unquoted with the current StandardImpl
-- physical naming strategy, so PostgreSQL stores them lowercase.

ALTER TABLE recommendations
    ADD COLUMN IF NOT EXISTS requestid VARCHAR(64),
    ADD COLUMN IF NOT EXISTS impressionid VARCHAR(64),
    ADD COLUMN IF NOT EXISTS candidatesource VARCHAR(64);

CREATE TABLE IF NOT EXISTS recommendation_rank_traces (
    id BIGSERIAL PRIMARY KEY,
    requestid VARCHAR(64) NOT NULL,
    userid BIGINT NOT NULL,
    servicetype VARCHAR(64) NOT NULL,
    algorithmversion VARCHAR(64) NOT NULL,
    experimentarm VARCHAR(64) NOT NULL,
    cachestatus VARCHAR(32) NOT NULL,
    invalidationreason VARCHAR(128),
    attributioncomplete BOOLEAN NOT NULL,
    tracejson TEXT NOT NULL,
    createddate TIMESTAMP NOT NULL,
    lastmodifieddate TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recommendations_requestid
    ON recommendations (requestid);

CREATE INDEX IF NOT EXISTS idx_recommendation_rank_traces_requestid
    ON recommendation_rank_traces (requestid);

CREATE INDEX IF NOT EXISTS idx_recommendation_rank_traces_user_created
    ON recommendation_rank_traces (userid, createddate DESC);
