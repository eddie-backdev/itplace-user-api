package com.itplace.userapi.user.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@RequiredArgsConstructor
public class AuthGrantConsumptionStore {

    private final JdbcTemplate jdbcTemplate;

    /** Must run in the same transaction as the operation authorized by the grant. */
    public boolean claim(String namespacedIdentity, Duration lifetime) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Grant consumption must be transactional");
        }
        long lifetimeMillis = lifetime.toMillis();
        if (lifetimeMillis <= 0) throw new IllegalArgumentException("Grant lifetime must be positive");

        String digest = digest(namespacedIdentity);
        jdbcTemplate.update("DELETE FROM auth_grant_consumption WHERE expires_at <= clock_timestamp()");
        return jdbcTemplate.update("""
                INSERT INTO auth_grant_consumption (identity_digest, expires_at)
                VALUES (?, clock_timestamp() + (? * INTERVAL '1 millisecond'))
                ON CONFLICT (identity_digest) DO NOTHING
                """, digest, lifetimeMillis) == 1;
    }

    private String digest(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
