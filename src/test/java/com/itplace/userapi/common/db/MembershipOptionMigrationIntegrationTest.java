package com.itplace.userapi.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MembershipOptionMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void separatesOptionsAndRetiresOnlyProvenLegacyPoliciesWithoutChangingIdentity() throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE benefit (benefitId BIGINT PRIMARY KEY, partnerId BIGINT, benefitName TEXT, canonicalKey TEXT);
                    CREATE TABLE benefitCarrierPolicy (
                        benefitCarrierPolicyId BIGINT PRIMARY KEY, benefitId BIGINT REFERENCES benefit,
                        carrier TEXT, active BOOLEAN, sourceKey TEXT, sourceUrl TEXT,
                        carrierBenefitName TEXT, type TEXT, description TEXT, usageType TEXT, lastCrawledAt TIMESTAMP
                    );
                    CREATE TABLE carrierTierBenefit (benefitCarrierPolicyId BIGINT, context TEXT);
                    CREATE TABLE favorite (benefitId BIGINT REFERENCES benefit);
                    CREATE TABLE benefit_cache_refresh (partner_id BIGINT PRIMARY KEY, changed_at TIMESTAMPTZ DEFAULT clock_timestamp());
                    INSERT INTO benefit SELECT i, i, '원문 ' || i, 'stable-key-' || i FROM generate_series(1, 10) AS i;
                    INSERT INTO favorite VALUES (1), (2), (5);
                    INSERT INTO benefitCarrierPolicy VALUES
                      (1,1,'SKT',true,'SKT:1할인형',NULL,'GS25 할인형','할인','할인','both','2026-10-03'),
                      (2,2,'SKT',true,'SKT:1적립형',NULL,'GS25 적립형','증정','할인','both','2026-10-03'),
                      (3,3,'SKT',true,'SKT:2적립형',NULL,'카페 적립형','증정','10% 적립','both','2026-10-03'),
                      (4,4,'SKT',true,'SKT:event',NULL,'할인형 고객 행사','할인','행사 안내','both','2026-10-03'),
                      (5,5,'KT',true,'KT:gs25','https://membership.kt.com/discount/partner/PartnerList.do','GS25 KT 멤버십','할인','할인','both','2026-07-15'),
                      (6,5,'KT',true,'KT:c2256','https://membership.kt.com/discount/partner/C22/56/PartnerDetail.do','GS25 KT 멤버십','할인','할인','both','2026-10-03'),
                      (7,6,'KT',true,'KT:unmatched','https://membership.kt.com/discount/partner/PartnerList.do','단독 KT 멤버십','할인','할인','both','2026-07-15'),
                      (8,7,'KT',true,'KT:online','https://membership.kt.com/discount/partner/PartnerList.do','채널 KT 멤버십','할인','할인','online','2026-07-15'),
                      (9,7,'KT',true,'KT:c227','https://membership.kt.com/discount/partner/C22/7/PartnerDetail.do','채널 KT 멤버십','할인','할인','offline','2026-10-03'),
                      (10,8,'KT',true,'KT:event','https://membership.kt.com/event','행사 KT 멤버십','할인','할인','both','2026-07-15'),
                      (11,8,'KT',true,'KT:c228','https://membership.kt.com/discount/partner/C22/8/PartnerDetail.do','행사 KT 멤버십','할인','할인','both','2026-10-03'),
                      (12,9,'KT',true,'KT:renamed','https://membership.kt.com/discount/partner/PartnerList.do','다른 상품 KT 멤버십','할인','할인','both','2026-07-15'),
                      (13,9,'KT',true,'KT:c229','https://membership.kt.com/discount/partner/C22/9/PartnerDetail.do','상품 KT 멤버십','할인','할인','both','2026-10-03'),
                      (14,10,'LGU',true,'LGU:10',NULL,'카페 LGU+ 멤버십','할인','할인','both','2026-10-03');
                    INSERT INTO carrierTierBenefit VALUES (1,'1천 원당 200원 할인'), (2,'1천 원당 200원 할인'), (3,'10% 적립');
                    """);

            migrate(statement);

            assertThat(value(statement, "SELECT membershipoptiontype FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=1"))
                    .isEqualTo("DISCOUNT");
            assertThat(value(statement, "SELECT membershipoptiontype || ':' || sourceoptionlabel || ':' || type FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=2"))
                    .isEqualTo("POINT_ACCRUAL:적립형:할인");
            assertThat(value(statement, "SELECT type FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=3"))
                    .isEqualTo("적립");
            assertThat(value(statement, "SELECT count(*) FROM benefitCarrierPolicy WHERE membershipoptiontype='UNKNOWN'"))
                    .isEqualTo("11");
            assertThat(value(statement, "SELECT string_agg(benefitCarrierPolicyId::text, ',') FROM benefitCarrierPolicy WHERE NOT active"))
                    .isEqualTo("5");
            assertThat(value(statement, "SELECT count(*) FROM benefitCarrierPolicy"))
                    .isEqualTo("14");
            assertThat(value(statement, "SELECT count(*) FROM favorite"))
                    .isEqualTo("3");
            assertThat(value(statement, "SELECT count(*) FROM benefit WHERE canonicalKey='stable-key-' || benefitId AND benefitName='원문 ' || benefitId"))
                    .isEqualTo("10");
            assertThat(value(statement, "SELECT carrierBenefitName || ':' || sourceKey FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=2"))
                    .isEqualTo("GS25 적립형:SKT:1적립형");
            assertThat(value(statement, "SELECT count(*) FROM benefit_cache_refresh"))
                    .isEqualTo("10");

            // 재실행에도 명시적으로 수정한 선택 유형과 활성 정책 수를 보존한다.
            statement.execute("UPDATE benefitCarrierPolicy SET membershipoptiontype='NOT_APPLICABLE', sourceoptionlabel='별도 확인' WHERE benefitCarrierPolicyId=3");
            migrate(statement);
            assertThat(value(statement, "SELECT membershipoptiontype || ':' || sourceoptionlabel FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=3"))
                    .isEqualTo("NOT_APPLICABLE:별도 확인");
            assertThat(value(statement, "SELECT count(*) FROM benefitCarrierPolicy WHERE active"))
                    .isEqualTo("13");
            connection.rollback();
        }
    }

    private static void migrate(Statement statement) throws SQLException, IOException {
        for (String file : new String[]{
                "V20261006_0001__separate_membership_option_type.sql",
                "V20261006_0002__retire_superseded_kt_list_policies.sql"}) {
            try (var stream = MembershipOptionMigrationIntegrationTest.class.getResourceAsStream("/db/migration/" + file)) {
                if (stream == null) throw new IOException("Missing migration: " + file);
                statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static String value(Statement statement, String query) throws SQLException {
        try (var result = statement.executeQuery(query)) {
            result.next();
            return result.getString(1);
        }
    }
}
