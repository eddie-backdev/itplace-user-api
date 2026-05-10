package com.itplace.userapi.common.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalThreeCarrierSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        addUserMembershipProfileColumns();
        addBenefitPolicyNormalizationTables();
        normalizeBenefitColumnVariants();
        hydrateExistingPoliciesFromLegacyBenefitColumns();
        backfillBenefitPolicyNormalizationTables();
        hydrateExistingPoliciesFromLegacyBenefitColumns();
        dropLegacyBenefitPolicyColumns();
        addBenefitIndexes();
        addInquiryTable();
        log.info("로컬 통신 3사 멤버십 스키마 보정 완료");
    }

    private void addUserMembershipProfileColumns() {
        jdbcTemplate.execute("""
                ALTER TABLE users
                    ADD COLUMN IF NOT EXISTS carrier VARCHAR(10),
                    ADD COLUMN IF NOT EXISTS "membershipGradeCode" VARCHAR(30),
                    ADD COLUMN IF NOT EXISTS "membershipVerified" BOOLEAN NOT NULL DEFAULT FALSE
                """);
    }

    private void addBenefitPolicyNormalizationTables() {
        jdbcTemplate.execute("""
                ALTER TABLE benefit
                    ADD COLUMN IF NOT EXISTS canonicalkey VARCHAR(512)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS benefitCarrierPolicy (
                    benefitCarrierPolicyId BIGSERIAL PRIMARY KEY,
                    benefitId BIGINT NOT NULL REFERENCES benefit(benefitId),
                    carrier VARCHAR(10) NOT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    sourceKey VARCHAR(512),
                    sourceUrl VARCHAR(512),
                    sourceCategory VARCHAR(100),
                    lastCrawledAt TIMESTAMP,
                    benefitLimit BIGINT REFERENCES benefitPolicy(benefitPolicyId),
                    carrierBenefitName VARCHAR(512),
                    type VARCHAR(50),
                    description TEXT,
                    manual TEXT,
                    usageType VARCHAR(50),
                    url VARCHAR(512),
                    createdDate TIMESTAMP,
                    lastModifiedDate TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_benefit_carrier_policy_source
                    ON benefitCarrierPolicy (carrier, sourceKey)
                    WHERE sourceKey IS NOT NULL
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_benefit_carrier_policy_benefit_carrier
                    ON benefitCarrierPolicy (benefitId, carrier)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS carrierTierBenefit (
                    carrierTierBenefitId BIGSERIAL PRIMARY KEY,
                    benefitCarrierPolicyId BIGINT NOT NULL REFERENCES benefitCarrierPolicy(benefitCarrierPolicyId) ON DELETE CASCADE,
                    grade VARCHAR(30) NOT NULL,
                    context TEXT NOT NULL,
                    isAll BOOLEAN,
                    discountValue INTEGER
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_carrier_tier_benefit_policy
                    ON carrierTierBenefit (benefitCarrierPolicyId, grade)
                """);
    }

    private void normalizeBenefitColumnVariants() {
        ensureLegacyBackfillColumns();
        copyColumnValueIfBothExist("sourcekey", "sourceKey");
        copyColumnValueIfBothExist("sourceurl", "sourceUrl");
        copyColumnValueIfBothExist("sourcecategory", "sourceCategory");
        copyColumnValueIfBothExist("lastcrawledat", "lastCrawledAt");
        copyColumnValueIfBothExist("canonicalkey", "canonicalKey");
    }

    private void backfillBenefitPolicyNormalizationTables() {
        jdbcTemplate.execute("""
                UPDATE benefit
                SET canonicalkey = LOWER(REGEXP_REPLACE(COALESCE(partnerId::TEXT, '') || ':' || COALESCE(benefitName, ''), '[^가-힣a-zA-Z0-9]+', '', 'g'))
                WHERE canonicalkey IS NULL
                """);
        if (!columnExists("benefit", "carrier")) {
            log.info("benefit 레거시 통신사 정책 컬럼이 없어 정책 정규화 백필을 건너뜁니다");
            return;
        }
        jdbcTemplate.execute("""
                INSERT INTO benefitCarrierPolicy (
                    benefitId,
                    carrier,
                    active,
                    sourceKey,
                    sourceUrl,
                    sourceCategory,
                    lastCrawledAt,
                    benefitLimit,
                    carrierBenefitName,
                    type,
                    description,
                    manual,
                    usageType,
                    url,
                    createdDate,
                    lastModifiedDate
                )
                SELECT
                    b.benefitId,
                    b.carrier,
                    COALESCE(b.active, TRUE),
                    b.sourceKey,
                    NULL,
                    b.sourceCategory,
                    b.lastCrawledAt,
                    b.benefitLimit,
                    b.benefitName,
                    b.type,
                    b.description,
                    b.manual,
                    b.usageType,
                    b.url,
                    b.createdDate,
                    b.lastModifiedDate
                FROM benefit b
                WHERE b.carrier IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM benefitCarrierPolicy p
                      WHERE p.benefitId = b.benefitId
                        AND p.carrier = b.carrier
                        AND COALESCE(p.sourceKey, '') = COALESCE(b.sourceKey, '')
                  )
                """);
        jdbcTemplate.execute("""
                INSERT INTO carrierTierBenefit (
                    benefitCarrierPolicyId,
                    grade,
                    context,
                    isAll,
                    discountValue
                )
                SELECT
                    p.benefitCarrierPolicyId,
                    tb.grade,
                    tb.context,
                    tb.isAll,
                    tb.discountValue
                FROM tierBenefit tb
                JOIN benefit b ON b.benefitId = tb.benefitId
                JOIN benefitCarrierPolicy p
                  ON p.benefitId = b.benefitId
                 AND p.carrier = b.carrier
                 AND COALESCE(p.sourceKey, '') = COALESCE(b.sourceKey, '')
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM carrierTierBenefit ctb
                    WHERE ctb.benefitCarrierPolicyId = p.benefitCarrierPolicyId
                      AND ctb.grade = tb.grade
                      AND ctb.context = tb.context
                )
                """);
    }

    private void hydrateExistingPoliciesFromLegacyBenefitColumns() {
        if (!columnExists("benefit", "carrier")) {
            return;
        }
        jdbcTemplate.execute("""
                UPDATE benefitCarrierPolicy p
                SET sourceKey = COALESCE(p.sourceKey, b.sourceKey),
                    sourceUrl = COALESCE(p.sourceUrl, b.sourceUrl),
                    sourceCategory = COALESCE(p.sourceCategory, b.sourceCategory),
                    lastCrawledAt = COALESCE(p.lastCrawledAt, b.lastCrawledAt),
                    benefitLimit = COALESCE(p.benefitLimit, b.benefitLimit),
                    carrierBenefitName = COALESCE(p.carrierBenefitName, b.benefitName),
                    type = COALESCE(p.type, b.type),
                    description = COALESCE(p.description, b.description),
                    manual = COALESCE(p.manual, b.manual),
                    usageType = COALESCE(p.usageType, b.usageType),
                    url = COALESCE(p.url, b.url)
                FROM benefit b
                WHERE p.benefitId = b.benefitId
                  AND p.carrier = b.carrier
                """);
    }

    private void dropLegacyBenefitPolicyColumns() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS uq_benefit_carrier_source_key");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_benefit_active_carrier");
        jdbcTemplate.execute("""
                ALTER TABLE benefit
                    DROP COLUMN IF EXISTS carrier,
                    DROP COLUMN IF EXISTS type,
                    DROP COLUMN IF EXISTS description,
                    DROP COLUMN IF EXISTS manual,
                    DROP COLUMN IF EXISTS usagetype,
                    DROP COLUMN IF EXISTS "usageType",
                    DROP COLUMN IF EXISTS url,
                    DROP COLUMN IF EXISTS sourcekey,
                    DROP COLUMN IF EXISTS "sourceKey",
                    DROP COLUMN IF EXISTS sourceurl,
                    DROP COLUMN IF EXISTS "sourceUrl",
                    DROP COLUMN IF EXISTS sourcecategory,
                    DROP COLUMN IF EXISTS "sourceCategory",
                    DROP COLUMN IF EXISTS lastcrawledat,
                    DROP COLUMN IF EXISTS "lastCrawledAt",
                    DROP COLUMN IF EXISTS benefitlimit,
                    DROP COLUMN IF EXISTS "benefitLimit",
                    DROP COLUMN IF EXISTS "canonicalKey"
                """);
    }

    private void addBenefitIndexes() {
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_benefit_active_main_category
                    ON benefit (active, maincategory)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_benefit_canonicalkey
                    ON benefit (canonicalkey)
                """);
    }

    private void ensureLegacyBackfillColumns() {
        if (!columnExists("benefit", "carrier")) {
            return;
        }
        jdbcTemplate.execute("""
                ALTER TABLE benefit
                    ADD COLUMN IF NOT EXISTS sourcekey VARCHAR(512),
                    ADD COLUMN IF NOT EXISTS sourceurl VARCHAR(512),
                    ADD COLUMN IF NOT EXISTS sourcecategory VARCHAR(100),
                    ADD COLUMN IF NOT EXISTS lastcrawledat TIMESTAMP
                """);
    }

    private void copyColumnValueIfBothExist(String targetColumn, String sourceColumn) {
        if (!columnExists("benefit", targetColumn) || !columnExists("benefit", sourceColumn)) {
            return;
        }
        jdbcTemplate.execute("UPDATE benefit SET " + quoteIdentifier(targetColumn) + " = COALESCE("
                + quoteIdentifier(targetColumn) + ", " + quoteIdentifier(sourceColumn) + ")");
    }

    private void addInquiryTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS inquiries (
                    id BIGSERIAL PRIMARY KEY,
                    category VARCHAR(50) NOT NULL,
                    title VARCHAR(200) NOT NULL,
                    content TEXT NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
                    createdDate TIMESTAMP NOT NULL,
                    lastModifiedDate TIMESTAMP NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_inquiries_status_created
                    ON inquiries (status, createdDate DESC)
                """);
    }

    private boolean columnExists(String tableName, String columnName) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_name = ?
                      AND column_name = ?
                )
                """, Boolean.class, tableName, columnName);
        return Boolean.TRUE.equals(exists);
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
