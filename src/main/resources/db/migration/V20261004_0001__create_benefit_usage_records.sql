CREATE TABLE benefitUsageRecord (
    recordId BIGSERIAL PRIMARY KEY,
    userId BIGINT NOT NULL REFERENCES users(userId) ON DELETE CASCADE,
    benefitId BIGINT NOT NULL,
    partnerId BIGINT NOT NULL,
    benefitName VARCHAR(512) NOT NULL,
    partnerName VARCHAR(255) NOT NULL,
    partnerImage VARCHAR(512),
    usedAt DATE NOT NULL,
    savedAmountKrw BIGINT,
    memo VARCHAR(200),
    CONSTRAINT chk_benefit_usage_saved_amount
        CHECK (savedAmountKrw IS NULL OR savedAmountKrw BETWEEN 0 AND 100000000)
);

CREATE INDEX idx_benefit_usage_user_used_at
    ON benefitUsageRecord (userId, usedAt DESC, recordId DESC);
