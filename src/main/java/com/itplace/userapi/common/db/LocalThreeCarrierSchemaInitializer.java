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
        addBenefitCrawlMetadataColumns();
        addBenefitIndexes();
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

    private void addBenefitCrawlMetadataColumns() {
        jdbcTemplate.execute("""
                ALTER TABLE benefit
                    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
                    ADD COLUMN IF NOT EXISTS "sourceKey" VARCHAR(255),
                    ADD COLUMN IF NOT EXISTS "sourceCategory" VARCHAR(100),
                    ADD COLUMN IF NOT EXISTS "lastCrawledAt" TIMESTAMP
                """);
    }

    private void addBenefitIndexes() {
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_benefit_carrier_source_key
                    ON benefit (carrier, "sourceKey")
                    WHERE "sourceKey" IS NOT NULL
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_benefit_active_carrier
                    ON benefit (active, carrier)
                """);
    }
}
