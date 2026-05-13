package com.itplace.userapi.ai.question.guard;

import com.itplace.userapi.ai.question.intent.QueryIntent;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BenefitCandidateGuard {

    public GuardResult filter(QueryIntent intent, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new GuardResult(List.of(), 0);
        }

        List<Candidate> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rejected = 0;

        for (Candidate candidate : candidates) {
            String dedupeKey = dedupeKey(candidate);
            if (dedupeKey.isBlank()
                    || seen.contains(dedupeKey)
                    || !metadataMatches(intent, candidate)
                    || !positivelyMatchesIntent(intent, candidate)
                    || conflictsWithIntent(intent, candidate)) {
                rejected++;
                continue;
            }
            seen.add(dedupeKey);
            accepted.add(candidate);
        }

        accepted.sort(Comparator
                .comparingInt((Candidate candidate) -> intentMatchScore(intent, candidate)).reversed()
                .thenComparing((Candidate candidate) -> candidate.getSemanticScore() == null ? 0.0 : candidate.getSemanticScore(), Comparator.reverseOrder()));

        return new GuardResult(accepted, rejected);
    }

    private static boolean metadataMatches(QueryIntent intent, Candidate candidate) {
        if (candidate == null || isBlank(candidate.getPartnerName())) {
            return false;
        }
        if (intent.carrier() != null) {
            if (isBlank(candidate.getCarrier()) || !intent.carrier().name().equals(candidate.getCarrier())) {
                return false;
            }
        }
        if (intent.grade() != null && !Boolean.TRUE.equals(candidate.getAllGrade())) {
            if (isBlank(candidate.getGrade()) || !intent.grade().name().equals(candidate.getGrade())) {
                return false;
            }
        }
        return true;
    }

    private static boolean conflictsWithIntent(QueryIntent intent, Candidate candidate) {
        if (!intent.hasIntentSignals() || intent.exclusions().isEmpty()) {
            return false;
        }

        String primary = normalize(String.join(" ",
                nullToBlank(candidate.getCategory()),
                nullToBlank(candidate.getMainCategory()),
                nullToBlank(candidate.getBenefitName()),
                nullToBlank(candidate.getPartnerName())
        ));
        String searchable = normalize(String.join(" ",
                primary,
                nullToBlank(candidate.getDescription()),
                nullToBlank(candidate.getContext())
        ));

        boolean excludedInPrimary = intent.exclusions().stream()
                .map(BenefitCandidateGuard::normalize)
                .anyMatch(primary::contains);
        if (excludedInPrimary) {
            return true;
        }

        boolean excluded = intent.exclusions().stream()
                .map(BenefitCandidateGuard::normalize)
                .anyMatch(searchable::contains);
        if (!excluded) {
            return false;
        }

        return intent.categoryHints().stream()
                .map(BenefitCandidateGuard::normalize)
                .noneMatch(primary::contains);
    }

    private static boolean positivelyMatchesIntent(QueryIntent intent, Candidate candidate) {
        if (intent == null || intent.categoryHints().isEmpty()) {
            return true;
        }
        return intentMatchScore(intent, candidate) > 0;
    }

    private static int intentMatchScore(QueryIntent intent, Candidate candidate) {
        if (intent == null || candidate == null || intent.categoryHints().isEmpty()) {
            return 0;
        }

        int score = 0;
        for (String hint : intent.categoryHints()) {
            String normalizedHint = normalize(hint);
            for (String alias : aliases(normalizedHint)) {
                if (alias.isBlank()) {
                    continue;
                }
                if (normalize(candidate.getCategory()).contains(alias)) {
                    score += 8;
                }
                if (normalize(candidate.getMainCategory()).contains(alias)) {
                    score += 6;
                }
                if (normalize(candidate.getBenefitName()).contains(alias)
                        || normalize(candidate.getPartnerName()).contains(alias)) {
                    score += 4;
                }
                if (normalize(candidate.getDescription()).contains(alias)
                        || normalize(candidate.getContext()).contains(alias)) {
                    score += 1;
                }
            }
        }
        return score;
    }

    private static List<String> aliases(String hint) {
        Map<String, List<String>> aliases = Map.ofEntries(
                Map.entry("영화", List.of("영화", "극장", "시네마")),
                Map.entry("영화관", List.of("영화", "극장", "시네마")),
                Map.entry("카페", List.of("카페", "커피", "음료", "디저트")),
                Map.entry("커피", List.of("커피", "카페")),
                Map.entry("음료", List.of("음료", "음료수", "주스", "에이드", "스무디", "커피", "카페", "편의점")),
                Map.entry("디저트", List.of("디저트", "제과", "베이커리", "빙수", "아이스크림", "카페")),
                Map.entry("빙수", List.of("빙수", "아이스크림", "디저트")),
                Map.entry("아이스크림", List.of("아이스크림", "빙수", "디저트")),
                Map.entry("제과", List.of("제과", "베이커리", "디저트", "카페")),
                Map.entry("편의점", List.of("편의점", "음료", "음료수")),
                Map.entry("쇼핑", List.of("쇼핑", "몰", "아울렛", "백화점")),
                Map.entry("실내", List.of("실내", "라운지", "쇼핑", "몰", "영화", "카페", "키즈카페")),
                Map.entry("워터", List.of("워터", "수영", "물놀이")),
                Map.entry("식당", List.of("식당", "외식", "레스토랑", "음식")),
                Map.entry("외식", List.of("외식", "식당", "레스토랑", "음식")),
                Map.entry("레스토랑", List.of("레스토랑", "식당", "외식")),
                Map.entry("관광", List.of("관광", "체험", "데이트", "테마", "복합문화")),
                Map.entry("체험", List.of("체험", "관광", "데이트", "테마", "복합문화")),
                Map.entry("전시관", List.of("전시", "전시관", "미술관", "복합문화")),
                Map.entry("복합문화", List.of("복합문화", "문화", "전시", "공연", "데이트")),
                Map.entry("가족", List.of("가족", "키즈", "패밀리")),
                Map.entry("키즈", List.of("키즈", "가족", "실내놀이터"))
        );
        return aliases.getOrDefault(hint, List.of(hint));
    }

    private static String dedupeKey(Candidate candidate) {
        if (candidate == null) {
            return "";
        }
        if (candidate.getBenefitId() != null) {
            return "benefit:" + candidate.getBenefitId();
        }
        if (candidate.getPartnerId() != null) {
            return "partner:" + candidate.getPartnerId();
        }
        return "name:" + nullToBlank(candidate.getPartnerName());
    }

    private static String normalize(String value) {
        return nullToBlank(value).toLowerCase(Locale.ROOT);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record GuardResult(List<Candidate> accepted, int rejectedCount) {
    }
}
