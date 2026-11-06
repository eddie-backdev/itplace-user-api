package com.itplace.userapi.map.service;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.exception.MapViewException;
import com.itplace.userapi.map.repository.MapPartnerViewRepository;
import com.itplace.userapi.map.repository.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapPartnerViewService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String UUID_V4_PATTERN = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private final StoreRepository storeRepository;
    private final MapPartnerViewRepository viewRepository;
    private final Clock clock;

    @Autowired
    public MapPartnerViewService(StoreRepository storeRepository, MapPartnerViewRepository viewRepository) {
        this(storeRepository, viewRepository, Clock.systemUTC());
    }

    MapPartnerViewService(StoreRepository storeRepository, MapPartnerViewRepository viewRepository, Clock clock) {
        this.storeRepository = storeRepository;
        this.viewRepository = viewRepository;
        this.clock = clock;
    }

    @Transactional
    public String hashAnonymousSessionId(String anonymousSessionId) {
        if (anonymousSessionId == null || anonymousSessionId.length() != 36
                || !anonymousSessionId.matches(UUID_V4_PATTERN)) {
            throw new MapViewException(StoreCode.MAP_VIEW_INVALID_SESSION);
        }
        UUID sessionId = UUID.fromString(anonymousSessionId);
        if (sessionId.version() != 4 || !sessionId.toString().equalsIgnoreCase(anonymousSessionId)) {
            throw new MapViewException(StoreCode.MAP_VIEW_INVALID_SESSION);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Transactional
    public void recordStoreView(long storeId, Long userId, String anonymousSessionHash) {
        if (storeRepository.findActiveStoreWithPartner(storeId).isEmpty()) {
            throw new MapViewException(StoreCode.STORE_NOT_FOUND);
        }
        viewRepository.recordIfEligible(storeId, userId, userId == null ? anonymousSessionHash : null,
                LocalDate.now(clock.withZone(SEOUL)));
    }
}
