package com.itplace.userapi.map.repository;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MapPartnerViewRepository {
    private final JdbcTemplate jdbcTemplate;

    public MapPartnerViewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int recordIfEligible(long storeId, Long userId, String anonymousSessionHash, LocalDate viewDay) {
        return jdbcTemplate.update("""
                INSERT INTO map_partner_view_daily (partner_id, user_id, anonymous_session_hash, view_day)
                SELECT s.partnerId, CAST(? AS BIGINT), CAST(? AS CHAR(64)), CAST(? AS DATE)
                FROM store s
                WHERE s.storeId = ?
                  AND s.active = true
                  AND EXISTS (
                      SELECT 1
                      FROM benefit b
                      JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                      WHERE b.partnerId = s.partnerId
                        AND COALESCE(b.active, true) = true
                        AND COALESCE(bcp.active, true) = true
                        AND (bcp.publicationStatus IS NULL OR bcp.publicationStatus = 'APPROVED')
                  )
                ON CONFLICT DO NOTHING
                """, userId, anonymousSessionHash, java.sql.Date.valueOf(viewDay), storeId);
    }

    public int deleteOlderThan(LocalDate cutoffDay) {
        return jdbcTemplate.update("DELETE FROM map_partner_view_daily WHERE view_day < ?",
                java.sql.Date.valueOf(cutoffDay));
    }
}
