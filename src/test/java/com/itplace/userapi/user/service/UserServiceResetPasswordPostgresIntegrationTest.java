package com.itplace.userapi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.user.dto.request.ResetPasswordRequest;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.AuthGrantConsumptionStore;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
class UserServiceResetPasswordPostgresIntegrationTest {
    private static final String EMAIL = "reset@example.com";
    private static final String TOKEN = "legacy-redis-reset-token";
    private static final AtomicReference<String> ENCODED_PASSWORD = new AtomicReference<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static AnnotationConfigApplicationContext context;
    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    UserService userService;
    UserRepository userRepository;
    AuthCredentialRepository credentialRepository;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;

    @BeforeAll
    static void startContext() {
        POSTGRES.start();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        dataSource = context.getBean(DataSource.class) instanceof HikariDataSource hikari ? hikari : null;
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/migration/V20261125_0001__track_auth_grant_consumption.sql"))
                .execute(context.getBean(DataSource.class));
        jdbc.execute("""
                CREATE FUNCTION reject_test_password() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.passwordhash = 'force-rollback' THEN
                        RAISE EXCEPTION 'forced credential update failure';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_test_password BEFORE UPDATE ON authCredential "
                + "FOR EACH ROW EXECUTE FUNCTION reject_test_password()");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
        if (dataSource != null) dataSource.close();
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM auth_grant_consumption");
        jdbc.update("DELETE FROM authCredential");
        jdbc.update("DELETE FROM users");
        userService = context.getBean(UserService.class);
        userRepository = context.getBean(UserRepository.class);
        credentialRepository = context.getBean(AuthCredentialRepository.class);
        redis = context.getBean(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("resetPassword:" + TOKEN)).thenReturn(EMAIL);

        User user = User.builder().email(EMAIL).role(Role.USER).build();
        user.getAuthCredentials().add(AuthCredential.localPassword(user, "old-hash"));
        userRepository.saveAndFlush(user);
    }

    @Test
    void failedCredentialFlushRollsBackClaimAndSuccessfulRetryConsumesLegacyRedisToken() {
        ResetPasswordRequest request = request();
        ENCODED_PASSWORD.set("force-rollback");

        assertThatThrownBy(() -> userService.resetPassword(request)).isInstanceOf(RuntimeException.class);
        assertThat(passwordHash()).isEqualTo("old-hash");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_grant_consumption", Long.class)).isZero();

        ENCODED_PASSWORD.set("encoded-new");
        userService.resetPassword(request);

        assertThat(passwordHash()).isEqualTo("encoded-new");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_grant_consumption", Long.class)).isEqualTo(1L);
        assertThatThrownBy(() -> userService.resetPassword(request)).isInstanceOf(RuntimeException.class);
        assertThat(passwordHash()).isEqualTo("encoded-new");
    }

    private String passwordHash() {
        return credentialRepository.findByUser_IdAndType(userRepository.findByEmail(EMAIL).orElseThrow().getId(),
                AuthCredentialType.LOCAL_PASSWORD).orElseThrow().getPasswordHash();
    }

    private ResetPasswordRequest request() {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEmail(EMAIL);
        request.setResetPasswordToken(TOKEN);
        request.setNewPassword("new-password");
        request.setNewPasswordConfirm("new-password");
        return request;
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, AuthCredentialRepository.class})
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(POSTGRES.getJdbcUrl());
            source.setUsername(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.itplace.userapi.user.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }

        @Bean
        AuthGrantConsumptionStore authGrantConsumptionStore(JdbcTemplate jdbcTemplate) {
            return new AuthGrantConsumptionStore(jdbcTemplate);
        }

        @Bean
        StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new PasswordEncoder() {
                @Override public String encode(CharSequence rawPassword) { return ENCODED_PASSWORD.get(); }
                @Override public boolean matches(CharSequence rawPassword, String encodedPassword) { return false; }
            };
        }

        @Bean OtpUtil otpUtil() { return mock(OtpUtil.class); }
        @Bean FavoriteRepository favoriteRepository() { return mock(FavoriteRepository.class); }
        @Bean MembershipProfileValidator membershipProfileValidator() { return mock(MembershipProfileValidator.class); }

        @Bean
        UserService userService(UserRepository users, StringRedisTemplate redis, OtpUtil otp,
                PasswordEncoder encoder, FavoriteRepository favorites, SocialAccountRepository socialAccounts,
                AuthCredentialRepository credentials, MembershipProfileValidator validator,
                AuthGrantConsumptionStore grants) {
            return new UserServiceImpl(users, redis, otp, encoder, favorites, socialAccounts, credentials,
                    validator, grants);
        }
    }
}
