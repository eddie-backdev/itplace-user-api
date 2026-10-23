CREATE TABLE IF NOT EXISTS partneralias (
    partneraliasid BIGSERIAL PRIMARY KEY,
    partnerid BIGINT NOT NULL REFERENCES partner(partnerid),
    aliasname VARCHAR(255) NOT NULL,
    normalizedalias VARCHAR(255) NOT NULL,
    source VARCHAR(20) NOT NULL,
    createddate TIMESTAMP,
    lastmodifieddate TIMESTAMP,
    CONSTRAINT uq_partner_alias_normalized UNIQUE (normalizedalias)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partner_alias_normalized ON partneralias (normalizedalias);
