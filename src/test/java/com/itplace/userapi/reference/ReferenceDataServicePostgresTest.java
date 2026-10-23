package com.itplace.userapi.reference;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class ReferenceDataServicePostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private ReferenceDataService references;
    private JdbcTemplate jdbc;

    @BeforeEach void setUp() {
        DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS membership_grade_match, membership_grade_alias, membership_grade, membership_program, partner_category CASCADE");
        jdbc.execute("CREATE TABLE membership_grade(code varchar(30) PRIMARY KEY, carrier_code varchar(10) NOT NULL, label text NOT NULL, sort_order integer NOT NULL DEFAULT 0, active boolean NOT NULL DEFAULT true, profile_selectable boolean NOT NULL DEFAULT false, benefit_enabled boolean NOT NULL DEFAULT true, all_grade_member boolean NOT NULL DEFAULT true, UNIQUE(carrier_code,code))");
        jdbc.execute("CREATE TABLE membership_program(code varchar(50) PRIMARY KEY,label text NOT NULL,sort_order integer NOT NULL DEFAULT 0,active boolean NOT NULL DEFAULT true)");
        jdbc.execute("CREATE TABLE partner_category(code varchar(50) PRIMARY KEY,label text NOT NULL,sort_order integer NOT NULL DEFAULT 0,active boolean NOT NULL DEFAULT true)");
        jdbc.execute("CREATE TABLE membership_grade_alias(carrier_code varchar(10),source_label varchar(100),grade_code varchar(30),PRIMARY KEY(carrier_code,source_label),FOREIGN KEY(carrier_code,grade_code) REFERENCES membership_grade(carrier_code,code))");
        jdbc.execute("CREATE TABLE membership_grade_match(carrier_code varchar(10),profile_grade varchar(30),benefit_grade varchar(30),PRIMARY KEY(carrier_code,profile_grade,benefit_grade),FOREIGN KEY(carrier_code,profile_grade) REFERENCES membership_grade(carrier_code,code),FOREIGN KEY(carrier_code,benefit_grade) REFERENCES membership_grade(carrier_code,code))");
        jdbc.update("INSERT INTO membership_grade(carrier_code,code,label,profile_selectable,benefit_enabled,all_grade_member) VALUES ('SKT','SKT_GOLD','Gold',true,true,true),('SKT','SKT_PLATINUM','Platinum',true,true,true),('SKT','SKT_RETIRED','Retired',false,false,true),('KT','KT_GOLD','Gold',true,true,true),('LGU','DIAMOND','다이아몬드',false,true,true)");
        jdbc.update("INSERT INTO membership_grade_alias VALUES ('SKT','PLATINUM','SKT_PLATINUM')");
        jdbc.update("INSERT INTO membership_grade_match VALUES ('SKT','SKT_GOLD','SKT_PLATINUM')");
        jdbc.update("INSERT INTO membership_program VALUES ('BASIC_BENEFIT','기본 혜택',10,true)");
        jdbc.update("INSERT INTO partner_category VALUES ('푸드','음식·카페',50,true)");
        references = new ReferenceDataService(jdbc);
    }

    @Test void databaseAddedGradeSupportsCatalogProfileAliasesAndMatchesWithoutCodeChanges() {
        Grade platinum = Grade.of("SKT_PLATINUM");
        assertThat(references.isSelectableProfile(Carrier.SKT, platinum)).isTrue();
        assertThat(references.isSelectableProfile(Carrier.KT, platinum)).isFalse();
        assertThat(references.isSelectableProfile(Carrier.LGU, Grade.DIAMOND)).isFalse();
        assertThat(references.gradeLabel(Carrier.SKT, platinum)).isEqualTo("Platinum");
        assertThat(references.findGradeMention(Carrier.SKT, "내 PLATINUM 등급 혜택")).isEqualTo(platinum);
        assertThat(references.gradesForProfile(Carrier.SKT, Grade.SKT_GOLD)).containsExactly(Grade.SKT_GOLD, platinum);
        assertThat(references.getReferenceData().grades()).extracting(ReferenceData.Grade::code).contains("SKT_PLATINUM");
        assertThat(references.allGradeMembers(Carrier.SKT)).contains(Grade.of("SKT_PLATINUM")).doesNotContain(Grade.of("SKT_RETIRED"));
    }
}
