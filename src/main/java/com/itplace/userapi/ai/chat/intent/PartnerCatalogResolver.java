package com.itplace.userapi.ai.chat.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Resolves user-authored partner names against the canonical partner and alias catalog. */
@Component
public class PartnerCatalogResolver {
    private static final java.time.Duration CATALOG_TTL = java.time.Duration.ofMinutes(2);
    private static final List<String> KOREAN_SUFFIXES = List.of("은", "는", "이", "가", "을", "를", "과", "와", "랑", "이랑", "나", "도", "만", "의", "에", "에서", "으로", "로", "에게", "한테", "까지", "부터", "마다", "처럼", "혜택", "할인", "멤버십", "쿠폰", "앱", "홈페이지", "매장", "이용", "제휴", "추천", "알려", "어때", "있", "가능");
    private static final Map<String, String> LEGACY_KOREAN_ALIASES = Map.of(
            "씨유", "CU", "지에스25", "GS25", "씨지브이", "CGV", "파리바게트", "파리바게뜨");

    private final JdbcTemplate jdbcTemplate;
    private final long catalogTtlNanos;
    private volatile CatalogSnapshot catalog;

    @Autowired
    public PartnerCatalogResolver(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, CATALOG_TTL);
    }

    PartnerCatalogResolver(JdbcTemplate jdbcTemplate, java.time.Duration catalogTtl) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogTtlNanos = catalogTtl.toNanos();
    }

    public List<Name> names() {
        CatalogSnapshot current = catalog;
        long now = System.nanoTime();
        if (current == null || now - current.loadedAtNanos() >= catalogTtlNanos) {
            synchronized (this) {
                current = catalog;
                now = System.nanoTime();
                if (current == null || now - current.loadedAtNanos() >= catalogTtlNanos) {
                    catalog = current = new CatalogSnapshot(load(), now);
                }
            }
        }
        return current.names();
    }

    public boolean isCatalogName(String value) {
        String normalized = normalize(value);
        return !normalized.isBlank() && names().stream().anyMatch(name -> name.normalized().equals(normalized)
                || normalize(name.canonical()).equals(normalized));
    }

    void setNamesForTest(List<Name> names) {
        catalog = new CatalogSnapshot(withLegacyAliases(names == null ? List.of() : names), System.nanoTime());
    }

    private List<Name> load() {
        Map<String, List<String>> candidates = new TreeMap<>();
        try {
            jdbcTemplate.query("""
                    SELECT p.partnerName AS canonical_name, p.partnerName AS variant
                    FROM partner p
                    UNION ALL
                    SELECT p.partnerName AS canonical_name, pa.aliasName AS variant
                    FROM partneralias pa JOIN partner p ON p.partnerId = pa.partnerId
                    UNION ALL
                    SELECT p.partnerName AS canonical_name, pa.normalizedAlias AS variant
                    FROM partneralias pa JOIN partner p ON p.partnerId = pa.partnerId
                    """, rs -> {
                String canonical = rs.getString("canonical_name");
                String variant = rs.getString("variant");
                String normalized = normalize(variant);
                if (canonical != null && !canonical.isBlank() && !normalized.isBlank()) {
                    candidates.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(canonical);
                }
            });
        } catch (DataAccessException failure) {
            throw new CatalogUnavailableException(failure);
        }

        return withLegacyAliases(resolveCandidates(candidates));
    }

    private static List<Name> withLegacyAliases(List<Name> names) {
        List<Name> compatible = new ArrayList<>(names);
        for (Map.Entry<String, String> alias : LEGACY_KOREAN_ALIASES.entrySet()) {
            String normalized = normalize(alias.getKey());
            boolean present = compatible.stream().anyMatch(name -> name.normalized().equals(normalized));
            if (!present) compatible.add(new Name(alias.getValue(), normalized));
        }
        return sortNames(compatible);
    }

    static List<Name> resolveCandidates(Map<String, List<String>> candidates) {
        List<Name> resolved = new ArrayList<>();
        candidates.forEach((variant, rawCanonicals) -> {
            List<String> canonicals = rawCanonicals.stream().distinct().toList();
            if (canonicals.size() == 1) {
                resolved.add(new Name(canonicals.get(0), variant));
                return;
            }
            List<String> exact = canonicals.stream().filter(canonical -> normalize(canonical).equals(variant)).toList();
            if (!exact.isEmpty()) resolved.add(new Name(exact.stream().sorted().findFirst().orElseThrow(), variant));
            // An alias shared by multiple canonical names is ambiguous; never select by DB row order.
        });
        return sortNames(resolved);
    }

    private static List<Name> sortNames(List<Name> names) {
        return names.stream()
                .sorted(Comparator.comparingInt((Name name) -> name.normalized().length()).reversed()
                        .thenComparing(Name::normalized).thenComparing(Name::canonical))
                .toList();
    }

    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase).forEach(normalized::appendCodePoint);
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    public List<Mention> mentions(String text) {
        if (text == null || text.isBlank()) return List.of();
        StringBuilder normalized = new StringBuilder(text.length());
        List<Integer> originalStarts = new ArrayList<>();
        List<Integer> originalEnds = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int end = i + Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) appendMapped(normalized, originalStarts, originalEnds,
                    Character.toLowerCase(cp), i, end);
            i = end;
        }

        List<Name> catalogNames = names();
        List<Mention> found = new ArrayList<>();
        int cursor = 0;
        while (cursor < normalized.length()) {
            Mention best = null;
            for (Name name : catalogNames) {
                int nameLength = name.normalized().length();
                if (nameLength > normalized.length() - cursor || normalized.indexOf(name.normalized(), cursor) != cursor) continue;
                int start = originalStarts.get(cursor);
                int end = originalEnds.get(cursor + nameLength - 1);
                if (hasBoundaries(text, start, end)) {
                    best = new Mention(name.canonical(), start, end);
                    break;
                }
            }
            if (best == null) {
                cursor++;
                continue;
            }
            found.add(best);
            while (cursor < originalStarts.size() && originalStarts.get(cursor) < best.end()) cursor++;
        }
        return List.copyOf(found);
    }

    private static void appendMapped(StringBuilder output, List<Integer> starts, List<Integer> ends,
                                     int codePoint, int originalStart, int originalEnd) {
        output.appendCodePoint(codePoint);
        for (int i = 0; i < Character.charCount(codePoint); i++) {
            starts.add(originalStart);
            ends.add(originalEnd);
        }
    }

    private static boolean hasBoundaries(String text, int start, int end) {
        if (start > 0 && isAsciiAlphaNumeric(text.charAt(start - 1))) return false;
        if (end < text.length() && isAsciiAlphaNumeric(text.charAt(end))) return false;
        if (start > 0 && isHangul(text.codePointBefore(start))) {
            int previous = text.codePointBefore(start);
            if (previous != '나' && previous != '와' && previous != '과' && previous != '랑') return false;
        }
        if (end < text.length() && isHangul(text.codePointAt(end))) {
            String suffix = text.substring(end);
            if (KOREAN_SUFFIXES.stream().noneMatch(suffix::startsWith)) return false;
        }
        return true;
    }

    private static boolean isAsciiAlphaNumeric(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }

    private static boolean isHangul(int codePoint) {
        return codePoint >= 0xAC00 && codePoint <= 0xD7A3 || codePoint >= 0x1100 && codePoint <= 0x11FF
                || codePoint >= 0x3130 && codePoint <= 0x318F;
    }

    public record Name(String canonical, String normalized) {}
    public record Mention(String canonical, int start, int end) {}
    private record CatalogSnapshot(List<Name> names, long loadedAtNanos) {}

    public static class CatalogUnavailableException extends RuntimeException {
        public CatalogUnavailableException(Throwable cause) { super("Partner catalog lookup failed", cause); }
    }
}
