package com.itplace.userapi.ai.chat.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.junit.jupiter.api.Test;

class PartnerCatalogResolverTest {
    @Test
    void resolvesLongestCanonicalMatchAcrossSpacingAndPunctuationVariants() {
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(List.of(
                new PartnerCatalogResolver.Name("GS25", "gs25"),
                new PartnerCatalogResolver.Name("GS25편의점택배", "gs25편의점택배"),
                new PartnerCatalogResolver.Name("Touch1", "touch1"),
                new PartnerCatalogResolver.Name("파고다어학원", "파고다어학원"),
                new PartnerCatalogResolver.Name("파고다어학원", "파고다"),
                new PartnerCatalogResolver.Name("메가박스", "메가박스"),
                new PartnerCatalogResolver.Name("CGV", "cgv"),
                new PartnerCatalogResolver.Name("롯데시네마", "롯데시네마")));

        assertThat(resolver.mentions("GS25 편의점 택배와 gs-25, Touch 1, 파고다 어학원"))
                .extracting(PartnerCatalogResolver.Mention::canonical)
                .containsExactly("GS25편의점택배", "GS25", "Touch1", "파고다어학원");
        assertThat(resolver.mentions("GS25와 GS25 혜택"))
                .extracting(PartnerCatalogResolver.Mention::canonical).containsExactly("GS25", "GS25");
        assertThat(resolver.mentions("씨유 및 지에스25 혜택"))
                .extracting(PartnerCatalogResolver.Mention::canonical).containsExactly("CU", "GS25");
        assertThat(resolver.mentions("씨지브이 영화 혜택"))
                .extracting(PartnerCatalogResolver.Mention::canonical).containsExactly("CGV");
        assertThat(resolver.mentions("메가박스는 제외, CGV나롯데시네마 혜택"))
                .extracting(PartnerCatalogResolver.Mention::canonical)
                .containsExactly("메가박스", "CGV", "롯데시네마");
    }

    @Test
    void resolvesParisBaguetteCommonSpellingToCatalogCanonical() {
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(List.of(new PartnerCatalogResolver.Name("파리바게뜨", "파리바게뜨")));

        assertThat(resolver.mentions("파리바게트 멤버십 할인"))
                .extracting(PartnerCatalogResolver.Mention::canonical)
                .containsExactly("파리바게뜨");
    }

    @Test
    void unknownTextDoesNotResolveToAnUnrelatedCatalogEntry() {
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(List.of(new PartnerCatalogResolver.Name("스타벅스", "스타벅스")));

        assertThat(resolver.mentions("스벅 말고 풀무원 혜택" )).isEmpty();
    }

    @Test
    void latinBrandsRequireAsciiBoundariesAndKoreanShortNamesRequireAWordBoundary() {
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(List.of(
                new PartnerCatalogResolver.Name("CU", "cu"),
                new PartnerCatalogResolver.Name("달콤", "달콤"),
                new PartnerCatalogResolver.Name("달빛편의점", "달빛편의점")));

        assertThat(resolver.mentions("CULTURE는 CU 혜택"))
                .extracting(PartnerCatalogResolver.Mention::canonical).containsExactly("CU");
        assertThat(resolver.mentions("달콤한 디저트, 달콤 혜택"))
                .extracting(PartnerCatalogResolver.Mention::canonical).containsExactly("달콤");
        assertThat(resolver.mentions("코덱스달빛편의점 혜택")).isEmpty();
    }

    @Test
    void ambiguousAliasesAreOmittedAndExactCanonicalNameWinsDeterministically() {
        Map<String, List<String>> rows = Map.of(
                "shared", List.of("Zeta Shop", "Alpha Shop"),
                "alpha", List.of("Alpha", "Zeta Shop"));

        assertThat(PartnerCatalogResolver.resolveCandidates(rows))
                .containsExactly(new PartnerCatalogResolver.Name("Alpha", "alpha"));
    }

    @Test
    void canonicalsWithEquivalentSpacingResolveToAStableCanonical() {
        assertThat(PartnerCatalogResolver.resolveCandidates(Map.of(
                "클럽디오아시스", List.of("클럽디오아시스", "클럽디 오아시스"))))
                .containsExactly(new PartnerCatalogResolver.Name("클럽디 오아시스", "클럽디오아시스"));
    }

    @Test
    void unicodeBeforeBrandKeepsMentionOffsetsCorrect() {
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(List.of(new PartnerCatalogResolver.Name("CU", "cu")));
        String text = "😀𐐀 CU 혜택";

        PartnerCatalogResolver.Mention mention = resolver.mentions(text).get(0);

        assertThat(text.substring(mention.start(), mention.end())).isEqualTo("CU");
    }

    @Test
    void catalogRefreshesAfterTtlAndDatabaseFailureIsNotAnEmptyCatalog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PartnerCatalogResolver expiring = new PartnerCatalogResolver(jdbc, java.time.Duration.ZERO);
        expiring.names();
        expiring.names();
        verify(jdbc, org.mockito.Mockito.times(2)).query(anyString(), any(RowCallbackHandler.class));

        JdbcTemplate brokenJdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("offline"))
                .when(brokenJdbc).query(anyString(), any(RowCallbackHandler.class));
        PartnerCatalogResolver unavailable = new PartnerCatalogResolver(brokenJdbc);
        assertThatThrownBy(unavailable::names)
                .isInstanceOf(PartnerCatalogResolver.CatalogUnavailableException.class);
    }

    @Test
    void fullFrozenCatalogResolvesEveryCanonicalAndAliasWithoutChoosingAmbiguousRows() throws Exception {
        JsonNode fixture = fixture("partner-catalog-2026-10-23.json");
        Map<String, List<String>> candidates = new TreeMap<>();
        Map<String, Set<String>> rawNamesByVariant = new TreeMap<>();
        int aliases = 0;
        for (JsonNode partner : fixture) {
            String canonical = partner.path("partnerName").asText();
            add(candidates, rawNamesByVariant, canonical, canonical);
            for (JsonNode alias : partner.path("aliases")) {
                aliases++;
                add(candidates, rawNamesByVariant, alias.path("aliasName").asText(), canonical);
                add(candidates, rawNamesByVariant, alias.path("normalizedAlias").asText(), canonical);
            }
        }
        List<PartnerCatalogResolver.Name> resolved = PartnerCatalogResolver.resolveCandidates(candidates);
        Map<String, String> resolvedByVariant = resolved.stream().collect(Collectors.toMap(
                PartnerCatalogResolver.Name::normalized, PartnerCatalogResolver.Name::canonical));
        long ambiguousAliases = candidates.entrySet().stream()
                .filter(entry -> entry.getValue().stream().distinct().count() > 1)
                .filter(entry -> entry.getValue().stream().distinct()
                        .noneMatch(name -> PartnerCatalogResolver.normalize(name).equals(entry.getKey())))
                .count();
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(resolved);

        assertThat(fixture.size()).isEqualTo(486);
        assertThat(aliases).isEqualTo(35);
        assertThat(ambiguousAliases).isEqualTo(0);
        assertThat(resolvedByVariant).hasSize(resolved.size());
        for (var entry : rawNamesByVariant.entrySet()) {
            String variant = entry.getValue().iterator().next();
            List<String> expected = resolvedByVariant.containsKey(entry.getKey())
                    ? List.of(resolvedByVariant.get(entry.getKey())) : List.of();
            assertThat(resolver.mentions(variant + " 혜택")).extracting(PartnerCatalogResolver.Mention::canonical)
                    .as("catalog variant %s", variant).containsExactlyElementsOf(expected);
            assertThat(resolver.mentions(spaceAndPunctuate(variant) + " 혜택"))
                    .extracting(PartnerCatalogResolver.Mention::canonical)
                    .as("formatted catalog variant %s", variant).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void frozenActiveSnapshotNamesAreAllRecognized() throws Exception {
        JsonNode fixture = fixture("active-partners-2026-10-23.json");
        List<PartnerCatalogResolver.Name> names = new ArrayList<>();
        for (JsonNode partner : fixture.path("partners")) {
            String name = partner.path("name").asText();
            names.add(new PartnerCatalogResolver.Name(name, PartnerCatalogResolver.normalize(name)));
        }
        PartnerCatalogResolver resolver = new PartnerCatalogResolver(null);
        resolver.setNamesForTest(names);

        assertThat(fixture.path("activePartnerCount").asInt()).isEqualTo(373);
        for (PartnerCatalogResolver.Name name : names) {
            assertThat(resolver.mentions(name.canonical() + " 혜택"))
                    .as("active catalog partner %s", name.canonical())
                    .extracting(PartnerCatalogResolver.Mention::canonical).containsExactly(name.canonical());
        }
    }

    private static JsonNode fixture(String file) throws Exception {
        try (InputStream input = PartnerCatalogResolverTest.class.getResourceAsStream(
                "/com/itplace/userapi/ai/chat/intent/" + file)) {
            return new ObjectMapper().readTree(input);
        }
    }

    private static void add(Map<String, List<String>> candidates, Map<String, Set<String>> rawNames,
                            String variant, String canonical) {
        String normalized = PartnerCatalogResolver.normalize(variant);
        if (normalized.isBlank()) return;
        candidates.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(canonical);
        rawNames.computeIfAbsent(normalized, ignored -> new TreeSet<>()).add(variant);
    }

    private static String spaceAndPunctuate(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(codePoint -> result.appendCodePoint(codePoint).append('-'));
        return result.toString();
    }
}
