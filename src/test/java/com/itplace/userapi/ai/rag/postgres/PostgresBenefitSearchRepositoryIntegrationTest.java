package com.itplace.userapi.ai.rag.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.ai.rag.service.BenefitSearchCondition;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBenefitSearchRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg18-trixie");

    private JdbcTemplate jdbc;
    private PostgresBenefitSearchRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("DROP TABLE IF EXISTS benefit_search_document, membership_grade_match, membership_grade, carrierTierBenefit, benefitCarrierPolicy, benefit CASCADE");
        jdbc.execute("CREATE TABLE benefit(benefitId bigint PRIMARY KEY, active boolean)");
        jdbc.execute("CREATE TABLE benefitCarrierPolicy(benefitCarrierPolicyId bigint PRIMARY KEY, benefitId bigint, carrier text, active boolean)");
        jdbc.execute("CREATE TABLE carrierTierBenefit(carrierTierBenefitId bigint PRIMARY KEY, benefitCarrierPolicyId bigint, grade text, isAll boolean, context text)");
        jdbc.execute("CREATE TABLE membership_grade(code text PRIMARY KEY, carrier_code text NOT NULL, active boolean NOT NULL DEFAULT true, all_grade_member boolean NOT NULL DEFAULT true, UNIQUE(carrier_code, code))");
        jdbc.execute("CREATE TABLE membership_grade_match(carrier_code text NOT NULL, profile_grade text NOT NULL, benefit_grade text NOT NULL)");
        jdbc.update("INSERT INTO membership_grade(code,carrier_code,all_grade_member) VALUES ('VIP','SKT',true),('GOLD','SKT',true)");
        jdbc.update("INSERT INTO carrierTierBenefit VALUES (223,22,'VIP',false,'all')");
        jdbc.execute("""
                CREATE TABLE benefit_search_document(document_id text, benefitid bigint, benefitcarrierpolicyid bigint,
                  carriertierbenefitid bigint, carrier text, grade text, is_all_grade boolean, usage_type text,
                  active boolean, searchable_text text, metadata jsonb, embedding vector(1536), embedding_model text, embedding_dimensions smallint, embedding_version text)
                """);
        jdbc.update("INSERT INTO benefit VALUES (1, true), (2, true), (3, false), (4,true)");
        jdbc.update("INSERT INTO benefitCarrierPolicy VALUES (11,1,'SKT',true),(22,2,'SKT',true),(33,3,'SKT',true),(44,4,'SKT',true)");
        jdbc.update("INSERT INTO carrierTierBenefit(carrierTierBenefitId,benefitCarrierPolicyId,grade,isAll,context) VALUES (111,11,'VIP',false,'one'),(222,22,'GOLD',true,'all'),(333,33,'VIP',false,'one')");
        String vector = vector(1f);
        jdbc.update("INSERT INTO benefit_search_document VALUES ('benefit:1:policy:11:tier:111',1,11,111,'SKT','VIP',false,'BOTH',true,'스타벅스 아메리카노 무료', '{\"tags\":[\"커피\"],\"businessType\":\"cafe\"}', ?::vector,'text-embedding-3-small',1536,'benefit-carrier-grade-rag-v3')", vector);
        jdbc.update("INSERT INTO benefit_search_document VALUES ('benefit:2:policy:22:tier:222',2,22,222,'SKT','GOLD',true,'BOTH',true,'커피 할인', '{\"tags\":[\"카페\"],\"businessType\":\"cafe\"}', ?::vector,'text-embedding-3-small',1536,'benefit-carrier-grade-rag-v3')", vector);
        jdbc.update("INSERT INTO benefit_search_document VALUES ('benefit:3:policy:33:tier:333',3,33,333,'SKT','VIP',false,'BOTH',true,'스타벅스 무료', '{\"tags\":[],\"businessType\":\"cafe\"}', ?::vector,'text-embedding-3-small',1536,'benefit-carrier-grade-rag-v3')", vector);
        jdbc.update("INSERT INTO benefit_search_document VALUES ('benefit:4:policy:44:tier:policy',4,44,null,'SKT',null,false,'both',true,'씨유 영화 50%_off 할인', '{\"tags\":[\"CU\"],\"partnerName\":\"CU\",\"benefitName\":\"영화 50%_off 할인\",\"businessType\":\"OTHER\"}', ?::vector,'text-embedding-3-small',1536,'benefit-carrier-grade-rag-v3')", vector);
        addPolicyDocument(5, "GS25 편의점 할인", "{\"partnerName\":\"GS25\",\"benefitName\":\"편의점 할인\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[]}");
        addPolicyDocument(6, "GS25 편의점 택배 할인", "{\"partnerName\":\"GS25\",\"benefitName\":\"편의점 택배 할인\",\"businessType\":\"OTHER\",\"tags\":[]}");
        addPolicyDocument(7, "일반 카페 아메리카노 할인", "{\"partnerName\":\"일반 카페\",\"benefitName\":\"아메리카노 할인\",\"businessType\":\"BEVERAGE_CAFE\",\"tags\":[]}");
        addPolicyDocument(8, "키즈카페 어린이 체험 할인", "{\"partnerName\":\"키즈카페\",\"benefitName\":\"어린이 체험 할인\",\"businessType\":\"KIDS_PLAY\",\"tags\":[]}");
        addPolicyDocument(9, "스터디카페 공부공간 할인", "{\"partnerName\":\"스터디카페\",\"benefitName\":\"공부공간 할인\",\"businessType\":\"STUDY_SPACE\",\"tags\":[]}");
        addPolicyDocument(10, "영화관 영화 예매 할인", "{\"partnerName\":\"영화관\",\"benefitName\":\"영화 예매 할인\",\"businessType\":\"MOVIE_THEATER\",\"tags\":[]}");
        addPolicyDocument(11, "베이커리 카페 디저트 할인", "{\"partnerName\":\"베이커리 카페\",\"benefitName\":\"디저트 할인\",\"businessType\":\"DESSERT\",\"tags\":[]}");
        addPolicyDocument(12, "커피 구독 할인", "{\"partnerName\":\"커피 구독\",\"benefitName\":\"커피 구독 할인\",\"businessType\":\"OTHER\",\"tags\":[]}");
        addPolicyDocument(13, "청소연구소 구독 할인", "{\"partnerName\":\"청소연구소\",\"benefitName\":\"청소연구소 구독 할인\",\"businessType\":\"OTHER\",\"tags\":[]}");
        addPolicyDocument(14, "지에스25 편의점 할인", "{\"partnerName\":\"지에스25\",\"benefitName\":\"편의점 할인\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[]}");
        addPolicyDocument(15, "GS25 택배 혜택", "{\"partnerName\":\"GS25\",\"benefitName\":\"GS25 택배 할인\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[\"CONVENIENCE_STORE\"]}");
        repository = new PostgresBenefitSearchRepository(jdbc, new com.itplace.userapi.ai.rag.service.EmbeddingSettings("openai", "text-embedding-3-small", 1536, "https://api.openai.com"));
    }

    @Test
    void lexicalExactAndAliasSearchRespectGradeAndCurrentActiveState() {
        var hits = repository.searchLexical("커피", Carrier.SKT, Grade.VIP, BenefitSearchCondition.none(), 10);
        assertThat(hits).extracting(BenefitSearchHit::benefitId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(repository.searchLexical("카페", Carrier.SKT, Grade.VIP, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).containsExactly(2L);
        assertThat(repository.searchLexical("영화 할인", Carrier.SKT, null, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).contains(4L);
        assertThat(repository.searchLexical("cu", Carrier.SKT, null, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).contains(4L);
        assertThat(repository.searchLexical("%_", Carrier.SKT, null, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).containsExactly(4L);
        assertThat(repository.searchLexical("cu", Carrier.SKT, Grade.VIP, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).doesNotContain(4L);
    }

    @Test
    void vectorSearchRequiresExactDimensionAndFiltersInactiveSourceRows() {
        assertThat(repository.searchVector(Collections.nCopies(1536, 1f), Carrier.SKT, Grade.VIP,
                BenefitSearchCondition.none(), 10)).extracting(BenefitSearchHit::benefitId).containsExactly(1L, 2L);
        assertThatThrownBy(() -> repository.searchVector(List.of(1f), Carrier.SKT, Grade.VIP,
                BenefitSearchCondition.none(), 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gradeScopedSourceOfferDocumentIsSearchableWithoutInventedTierId() {
        jdbc.update("INSERT INTO benefit VALUES (20, true)");
        jdbc.update("INSERT INTO benefitCarrierPolicy VALUES (220,20,'SKT',true)");
        String documentId = "benefit:20:policy:220:offer:vip-source:grade:VIP";
        jdbc.update("""
                INSERT INTO benefit_search_document VALUES (?,20,220,null,'SKT','VIP',false,'online',true,
                  'source-only VIP benefit', ?::jsonb, null, 'text-embedding-3-small', 1536, 'benefit-carrier-grade-rag-v3')
                """, documentId,
                "{\"documentId\":\"" + documentId + "\",\"offerKey\":\"vip-source\",\"grade\":\"VIP\",\"carrier\":\"SKT\",\"active\":true}");

        var hits = repository.searchLexical("source-only VIP benefit", Carrier.SKT, Grade.VIP,
                BenefitSearchCondition.none(), 10);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.benefitId()).isEqualTo(20L);
            assertThat(hit.policyId()).isEqualTo(220L);
            assertThat(hit.tierBenefitId()).isNull();
            assertThat(hit.metadata().path("offerKey").asText()).isEqualTo("vip-source");
        });
    }

    @Test
    void lexicalRankingExpandsKoreanGs25AliasAndPrefersSpecificBusinessTypeAndMultipleQueryTerms() {
        var gs25 = repository.searchLexical("지에스25", Carrier.SKT, null, BenefitSearchCondition.none(), 10);
        assertThat(gs25).extracting(BenefitSearchHit::benefitId).contains(5L, 6L);
        assertThat(repository.searchLexical("gs25", Carrier.SKT, null, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).contains(14L);

        var convenience = repository.searchLexical("편의점", Carrier.SKT, null, BenefitSearchCondition.none(), 10);
        assertThat(convenience.get(0).benefitId()).isIn(5L, 14L);
        assertThat(convenience.get(1).benefitId()).isIn(5L, 14L);
        assertThat(convenience.get(2).benefitId()).isEqualTo(15L);
        assertThat(convenience.get(3).benefitId()).isEqualTo(6L);
        assertThat(repository.searchLexical("편의점 혜택", Carrier.SKT, null, BenefitSearchCondition.none(), 10))
                .extracting(BenefitSearchHit::benefitId).contains(15L);
        var movie = repository.searchLexical("영화", Carrier.SKT, null, BenefitSearchCondition.none(), 10);
        assertThat(movie).extracting(BenefitSearchHit::benefitId).startsWith(10L);

        var cafe = repository.searchLexical("카페", Carrier.SKT, null, BenefitSearchCondition.none(), 10);
        assertThat(cafe).extracting(BenefitSearchHit::benefitId).contains(8L, 9L);
        assertThat(cafe.get(0).benefitId()).isIn(7L, 11L);

        var subscriptions = repository.searchLexical("커피 구독", Carrier.SKT, null, BenefitSearchCondition.none(), 10);
        assertThat(subscriptions).extracting(BenefitSearchHit::benefitId).startsWith(12L, 13L);
    }

    @Test
    void canonicalPartnerFilterRunsBeforeTopKAndRejectsLongerBrandNames() {
        for (int i = 0; i < 45; i++) {
            addPolicyDocument(1000 + i, "편의점 혜택 할인", "{\"partnerName\":\"무관 제휴처 " + i
                    + "\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[]}");
        }
        addPolicyDocument(1100, "GS25 편의점 혜택 할인", "{\"partnerName\":\"GS25\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[]}");
        addPolicyDocument(1101, "GS25택배 편의점 혜택 할인", "{\"partnerName\":\"GS25 택배\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[]}");
        var condition = new BenefitSearchCondition(List.of("CONVENIENCE_STORE"), List.of(), List.of(), List.of(),
                List.of("gs-25"), List.of("GS25 택배"));

        var hits = repository.searchLexical("편의점 혜택", Carrier.SKT, null, condition, 1);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.benefitId()).isEqualTo(1100L);
            assertThat(hit.metadata().path("partnerName").asText()).isEqualTo("GS25");
        });

        addPolicyDocument(1102, "이마트24 편의점 할인", "{\"partnerName\":\"이마트24\",\"businessType\":\"CONVENIENCE_STORE\",\"tags\":[]}");
        assertThat(repository.searchLexical("편의점 할인", Carrier.SKT, null,
                new BenefitSearchCondition(List.of(), List.of(), List.of(), List.of(), List.of("이마트 24"), List.of()), 10))
                .extracting(BenefitSearchHit::benefitId).containsExactly(1102L);
    }

    @Test
    void anUnknownCanonicalPartnerHasNoUnrelatedFallbackHits() {
        var condition = new BenefitSearchCondition(List.of(), List.of(), List.of(), List.of(),
                List.of("없는 브랜드"), List.of());

        assertThat(repository.searchLexical("편의점 할인", Carrier.SKT, null, condition, 40)).isEmpty();
    }

    @Test
    void canonicalPartnerAndBusinessTypeFiltersComposeWithRequestedGrade() {
        addTierPolicyDocument(1200, "경쟁 제휴처", "MOVIE_THEATER");
        addTierPolicyDocument(1201, "GS25 택배", "CONVENIENCE_STORE");
        addTierPolicyDocument(1202, "GS25", "CONVENIENCE_STORE");
        var condition = new BenefitSearchCondition(List.of("CONVENIENCE_STORE"), List.of(), List.of(), List.of(),
                List.of("gs25"), List.of("GS25 택배"));

        var hits = repository.searchLexical("편의점 할인", Carrier.SKT, Grade.VIP, condition, 10);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.benefitId()).isEqualTo(1202L);
            assertThat(hit.metadata().path("partnerName").asText()).isEqualTo("GS25");
        });
    }

    private void addPolicyDocument(long benefitId, String searchableText, String metadata) {
        long policyId = benefitId + 50;
        jdbc.update("INSERT INTO benefit VALUES (?, true)", benefitId);
        jdbc.update("INSERT INTO benefitCarrierPolicy VALUES (?, ?, 'SKT', true)", policyId, benefitId);
        jdbc.update("""
                INSERT INTO benefit_search_document VALUES (?, ?, ?, null, 'SKT', null, false, 'both', true, ?, ?::jsonb,
                  null, 'text-embedding-3-small', 1536, 'benefit-carrier-grade-rag-v3')
                """, "benefit:" + benefitId + ":policy:" + policyId + ":tier:policy", benefitId, policyId, searchableText, metadata);
    }

    private void addTierPolicyDocument(long benefitId, String partnerName, String businessType) {
        long policyId = benefitId + 50;
        long tierId = benefitId + 100;
        jdbc.update("INSERT INTO benefit VALUES (?, true)", benefitId);
        jdbc.update("INSERT INTO benefitCarrierPolicy VALUES (?, ?, 'SKT', true)", policyId, benefitId);
        jdbc.update("INSERT INTO carrierTierBenefit VALUES (?, ?, 'VIP', false, '등급 혜택')", tierId, policyId);
        jdbc.update("""
                INSERT INTO benefit_search_document VALUES (?, ?, ?, ?, 'SKT', 'VIP', false, 'both', true,
                  '편의점 할인 혜택', ?::jsonb, null, 'text-embedding-3-small', 1536, 'benefit-carrier-grade-rag-v3')
                """, "benefit:" + benefitId + ":policy:" + policyId + ":tier:" + tierId, benefitId, policyId,
                tierId, "{\"partnerName\":\"" + partnerName + "\",\"businessType\":\"" + businessType + "\",\"tags\":[]}");
    }

    private static String vector(float first) {
        List<Float> values = new java.util.ArrayList<>(Collections.nCopies(1536, 0f));
        values.set(0, first);
        return "[" + String.join(",", values.stream().map(String::valueOf).toList()) + "]";
    }
}
