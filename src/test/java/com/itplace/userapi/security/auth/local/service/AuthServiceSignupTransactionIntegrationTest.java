package com.itplace.userapi.security.auth.local.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.auth.local.dto.request.SignUpRequest;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.security.verification.email.service.EmailService;
import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthGrantConsumptionStore;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuthServiceSignupTransactionIntegrationTest.JpaConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class AuthServiceSignupTransactionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @jakarta.annotation.Resource
    private UserRepository userRepository;
    @jakarta.annotation.Resource
    private AuthGrantConsumptionStore grantStore;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;
    @jakarta.annotation.Resource
    private PlatformTransactionManager transactionManager;

    private EmailService emailService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261125_0001__track_auth_grant_consumption.sql"))
                .execute(jdbc.getDataSource());
        userRepository.deleteAllInBatch();
        jdbc.update("DELETE FROM auth_grant_consumption");

        emailService = mock(EmailService.class);
        when(emailService.verifiedGrantIdentity("signup@example.com")).thenReturn("grant-nonce");
        MembershipProfileValidator membership = mock(MembershipProfileValidator.class);
        when(membership.isValidForSignUp(Carrier.SKT, Grade.SKT_VIP)).thenReturn(true);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("!Password123")).thenReturn("encoded-password");
        authService = new AuthService(
                mock(RedisTemplate.class), encoder, userRepository, mock(JWTUtil.class), mock(CookieUtil.class),
                emailService, membership, grantStore);
    }

    @Test
    void signupRollsBackAccountAndGrantReceiptTogetherAndCanRetry() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            authService.signUp(signupRequest());
            throw new IllegalStateException("force transaction rollback after signup returns");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.findByEmail("signup@example.com")).isEmpty();
        assertThat(receiptCount()).isZero();

        transaction.executeWithoutResult(status -> authService.signUp(signupRequest()));

        User registered = userRepository.findByEmail("signup@example.com").orElseThrow();
        assertThat(registered.getEmail()).isEqualTo("signup@example.com");
        assertThat(receiptCount()).isEqualTo(1);
    }

    private long receiptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_grant_consumption", Long.class);
    }

    private SignUpRequest signupRequest() {
        return SignUpRequest.builder()
                .nickname("가입 테스트")
                .email("signup@example.com")
                .password("!Password123")
                .passwordConfirm("!Password123")
                .gender(Gender.MALE)
                .carrier(Carrier.SKT)
                .membershipGradeCode(Grade.SKT_VIP)
                .birthday(LocalDate.of(1990, 1, 1))
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @EnableJpaAuditing
    static class JpaConfig {
        @Bean
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(POSTGRES.getJdbcUrl());
            source.setUsername(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(HikariDataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(
                jakarta.persistence.EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        AuthGrantConsumptionStore authGrantConsumptionStore(JdbcTemplate jdbcTemplate) {
            return new AuthGrantConsumptionStore(jdbcTemplate);
        }
    }
}
