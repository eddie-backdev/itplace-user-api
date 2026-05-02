package com.itplace.userapi.partner.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 제휴처 대표 이미지는 검수된 정본 스토리지(R2/S3)에 올라간 URL만 신뢰한다.
 * 통신사 크롤링 원본 이미지는 Benefit 원천 후보일 뿐 Partner.image를 직접 덮어쓰지 않는다.
 */
public final class PartnerImagePolicy {

    private static final String R2_DEV_HOST_SUFFIX = ".r2.dev";
    private static final String LEGACY_CANONICAL_BUCKET = "itplacepartners.s3.ap-northeast-2.amazonaws.com";
    private static final String ITPLACE_IMAGE_HOST = "images.itplace.click";

    private PartnerImagePolicy() {
    }

    public static String resolveCanonicalImage(String currentImage, String candidateImage) {
        String current = trimToNull(currentImage);
        String candidate = trimToNull(candidateImage);

        if (isCanonicalStorageUrl(current)) {
            return current;
        }

        if (isCanonicalStorageUrl(candidate)) {
            return candidate;
        }

        // R2 업로드/스냅샷 후보가 아직 정본 URL을 제공하지 못하더라도,
        // 기존에 화면에서 정상 노출되던 이미지를 즉시 지우면 GS25 같은 제휴처가 무이미지로 퇴행한다.
        // 신규 raw 후보는 저장하지 않되, 기존 값은 다음 성공적인 R2 정규화 전까지 보존한다.
        return current;
    }

    public static boolean isCanonicalStorageUrl(String imageUrl) {
        String trimmed = trimToNull(imageUrl);
        if (trimmed == null) {
            return false;
        }

        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }

            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return "https".equals(normalizedScheme)
                    && (normalizedHost.endsWith(R2_DEV_HOST_SUFFIX)
                    || ITPLACE_IMAGE_HOST.equals(normalizedHost)
                    || LEGACY_CANONICAL_BUCKET.equals(normalizedHost));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
