package com.itplace.userapi.ai.chat;

import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.chat.dto.BenefitChatResponse;
import com.itplace.userapi.ai.chat.intent.BenefitChatPlan;
import com.itplace.userapi.ai.chat.intent.BenefitChatPlanner;
import com.itplace.userapi.ai.chat.llm.BenefitChatAnswerer;
import com.itplace.userapi.ai.rag.service.BenefitSearchCondition;
import com.itplace.userapi.ai.rag.service.BenefitSearchService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.service.BenefitOfferMatcher;
import com.itplace.userapi.benefit.service.BenefitFactFormatter;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitChatService {
    private static final int CANDIDATE_LIMIT = 40;
    private static final int RESPONSE_LIMIT = 8;

    private final BenefitChatPlanner planner;
    private final BenefitChatAnswerer answerer;
    private final EmbeddingService embeddingService;
    private final BenefitSearchService benefitSearchService;
    private final UserRepository userRepository;
    private final StoreService storeService;
    private final BenefitOfferMatcher benefitOfferMatcher;
    private final ReferenceDataService referenceDataService;
    @Value("${app.ai.enabled:false}")
    private boolean aiEnabled;
    @Value("${app.ai.embedding.provider:${EMBEDDING_PROVIDER:openai}}")
    private String embeddingProvider = "openai";
    @Value("${app.ai.embedding.model:${EMBEDDING_MODEL:text-embedding-3-small}}")
    private String embeddingModel = "text-embedding-3-small";
    @Value("${app.ai.embedding.dimensions:${EMBEDDING_DIMENSIONS:0}}")
    private int embeddingDimensions;
    @Value("${app.search.provider:elasticsearch}")
    private String searchProvider = "elasticsearch";
    @Value("${app.ai.chat.model:${spring.ai.openai.chat.model:configured}}")
    private String chatModel = "configured";

    public BenefitChatResponse chat(BenefitChatRequest request, Long userId) {
        return chat(request, userId, (event, data) -> {});
    }

    public BenefitChatResponse chat(BenefitChatRequest request, Long userId, BiConsumer<String, Object> onEvent) {
        BiConsumer<String, Object> events = onEvent == null ? (event, data) -> {} : onEvent;
        String traceId = UUID.randomUUID().toString();
        long plannerStarted = System.nanoTime();
        var profile = userId == null ? null : userRepository.findById(userId).orElse(null);
        Carrier profileCarrier = profile == null ? null : profile.getCarrier();
        Grade profileGrade = profile == null ? null : profile.getMembershipGradeCode();
        checkInterrupted();
        events.accept("status", Map.of("stage", "planning"));
        BenefitChatPlan plan;
        try {
            plan = planner.plan(request, profileCarrier, profileGrade);
        } catch (com.itplace.userapi.ai.chat.intent.PartnerCatalogResolver.CatalogUnavailableException e) {
            log.warn("benefit_chat stage=planner traceId={} status=unavailable type={}", traceId, e.getClass().getSimpleName());
            return response("지금 혜택 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", List.of(), "UNAVAILABLE", List.of(), traceId);
        }
        checkInterrupted();
        log.info("benefit_chat stage=planner traceId={} model={} enabled={} elapsedMs={} mode={} categories={} carriers={}",
                traceId, chatModelName(), aiEnabled, elapsedMillis(plannerStarted), plan.mode(),
                plan.categories().size(), plan.carriers().size());

        if ("OUT_OF_SCOPE".equals(plan.mode())) {
            return response(plan.clarification(), List.of(), "OUT_OF_SCOPE", List.of(), traceId);
        }
        if ("CLARIFY".equals(plan.mode())) {
            return response(plan.clarification(), List.of(), "CLARIFICATION", List.of(), traceId);
        }
        boolean browseRequest = browseRequest(plan);
        if (!hasMeaningfulTopic(plan) && !browseRequest) {
            return response("어떤 분야의 혜택을 찾으시나요? 예를 들어 영화, 카페, 식당처럼 말씀해 주세요.",
                    List.of(), "CLARIFICATION", List.of("영화 혜택", "카페 혜택", "식당 혜택"), traceId);
        }

        if (plan.carriers().isEmpty() && !plan.compareCarriers()) {
            return response("어느 통신사 혜택을 찾으세요?", List.of(), "CLARIFICATION",
                    List.of("SKT", "KT", "LG U+", "전체 비교"), traceId);
        }

        checkInterrupted();
        events.accept("status", Map.of("stage", "searching"));
        List<Float> embedding = null;
        if (aiEnabled && !browseRequest) {
            long embeddingStarted = System.nanoTime();
            try {
                embedding = embeddingService.embedQuery(plan.query());
                log.info("benefit_chat stage=embedding traceId={} provider={} model={} dimensions={} elapsedMs={} status=success",
                        traceId, embeddingProvider, embeddingModel, embedding == null ? 0 : embedding.size(),
                        elapsedMillis(embeddingStarted));
            }
            catch (RuntimeException e) {
                rethrowCancellation(e);
                log.warn("benefit_chat stage=embedding traceId={} provider={} model={} configuredDimensions={} elapsedMs={} status=fallback type={}",
                        traceId, embeddingProvider, embeddingModel, embeddingDimensions, elapsedMillis(embeddingStarted),
                        e.getClass().getSimpleName());
            }
        }
        checkInterrupted();
        BenefitSearchService.ChatSearchResult search;
        long retrievalStarted = System.nanoTime();
        try { search = retrieve(request, plan, embedding); }
        catch (RuntimeException e) {
            rethrowCancellation(e);
            log.warn("benefit_chat stage=retrieval traceId={} provider={} elapsedMs={} status=unavailable type={}",
                    traceId, searchProvider, elapsedMillis(retrievalStarted), e.getClass().getSimpleName());
            return response("지금 혜택 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", List.of(), "UNAVAILABLE", List.of(), traceId);
        }
        log.info("benefit_chat stage=retrieval traceId={} provider={} elapsedMs={} available={} candidates={}",
                traceId, searchProvider, elapsedMillis(retrievalStarted), search.available(), search.candidates().size());
        checkInterrupted();
        if (!search.available()) {
            return response("지금 혜택 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", List.of(), "UNAVAILABLE", List.of(), traceId);
        }
        if (plan.grade() == null && !plan.gradeUnspecified() && !plan.compareCarriers()
                && plan.carriers().size() == 1 && gradeAffectsBenefits(search.candidates())) {
            Carrier carrier = plan.carriers().get(0);
            List<String> choices = gradeChoices(carrier);
            if (!choices.isEmpty()) {
                return response(carrierLabel(carrier) + " 등급을 알려주시면 받을 수 있는 혜택을 골라드릴게요.", List.of(),
                        "CLARIFICATION", choices, traceId);
            }
        }
        List<Candidate> candidates = search.candidates().stream()
                .map(candidate -> reflectSelectedGrade(candidateForPlan(candidate, plan), plan)).toList();

        if (candidates.isEmpty()) {
            if (plan.followUp() && "DETAIL".equals(plan.mode()) && !request.previousCandidateKeys().isEmpty()) {
                return response("이전 대화의 혜택 정보를 다시 확인할 수 없어요. 어떤 제휴처인지 알려주시면 다시 찾아볼게요.",
                        List.of(), "CLARIFICATION", List.of(), traceId);
            }
            return response("요청하신 조건에 맞는 혜택을 찾지 못했어요.", List.of(), "NO_RESULTS", List.of(), traceId);
        }
        List<Candidate> retrievedCandidates = candidates;
        Map<String, Candidate> currentByKey = new LinkedHashMap<>();
        retrievedCandidates.forEach(candidate -> currentByKey.putIfAbsent(candidateKey(candidate), candidate));
        List<Candidate> previous = request.previousCandidateKeys().stream().map(currentByKey::get)
                .filter(java.util.Objects::nonNull).toList();
        boolean resolvedPriorCandidates = plan.followUp() && !previous.isEmpty();
        Integer ordinal = ordinal(request.question());
        long previousBrands = previous.stream().map(this::partnerIdentity).distinct().count();
        if (plan.followUp() && plan.partners().isEmpty() && previousBrands > 1 && ordinal == null
                && containsAny(request.question(), "그거", "그 혜택", "자세히", "상세")) {
            return response("어떤 혜택을 말씀하시는지 번호로 알려주세요.", List.of(), "CLARIFICATION",
                    List.of("첫 번째 혜택", "두 번째 혜택"), traceId);
        }
        if (ordinal != null && plan.followUp()) {
            Map<String, List<Candidate>> brandGroups = new LinkedHashMap<>();
            checkInterrupted();
            Map<String, Candidate> originalByKey = benefitSearchService.resolveChatCandidates(
                            request.previousCandidateKeys(), null, null).stream()
                    .collect(java.util.stream.Collectors.toMap(this::candidateKey, candidate -> candidate, (a, b) -> a));
            checkInterrupted();
            for (String key : request.previousCandidateKeys()) {
                Candidate original = originalByKey.get(key);
                if (original == null) {
                    return response("이전 대화의 혜택 정보를 다시 확인할 수 없어요. 어떤 제휴처인지 알려주시면 다시 찾아볼게요.",
                            List.of(), "CLARIFICATION", List.of(), traceId);
                }
                brandGroups.computeIfAbsent(partnerIdentity(original), ignored -> new ArrayList<>()).add(original);
            }
            if (ordinal >= brandGroups.size()) {
                return response("이전 대화에서 해당 번호의 혜택을 찾지 못했어요. 어떤 제휴처인지 알려주세요.",
                        List.of(), "CLARIFICATION", List.of(), traceId);
            }
            List<Candidate> originalGroup = new ArrayList<>(brandGroups.values()).get(ordinal);
            String selectedBrand = partnerIdentity(originalGroup.get(0));
            List<Candidate> selectedGroup = candidates.stream().filter(candidate -> selectedBrand.equals(partnerIdentity(candidate))).toList();
            if (selectedGroup.isEmpty()) {
                return response("이전 대화에서 해당 번호의 혜택을 다시 확인할 수 없어요. 어떤 제휴처인지 알려주세요.",
                        List.of(), "CLARIFICATION", List.of(), traceId);
            }
            List<String> carrierChoices = originalGroup.stream().map(Candidate::getCarrier).filter(java.util.Objects::nonNull)
                    .distinct().toList();
            if (carrierChoices.size() > 1 && plan.carriers().size() != 1) {
                String brand = java.util.Objects.toString(originalGroup.get(0).getPartnerName(), "제휴처");
                return response(brand + "의 어느 통신사 혜택을 자세히 볼까요?", List.of(), "CLARIFICATION",
                        carrierChoices.stream().map(value -> brand + " " + carrierLabel(value) + " 혜택 이용 조건 알려줘").toList(), traceId);
            }
            candidates = selectedGroup.stream().filter(java.util.Objects::nonNull).toList();
        }
        candidates = diversify(candidates, plan);
        if ("SEARCH".equals(plan.mode()) && fastSearchSupported(plan) && simpleSearchQuestion(request)) {
            List<Candidate> selected = fastSearchSelection(candidates);
            List<BenefitChatResponse.BenefitChatPartner> partners = selected.stream()
                    .map(candidate -> toPartner(candidate, nearbyStore(candidate, request, plan))).toList();
            List<String> suggested = new ArrayList<>();
            String reason = applyNearbyGuidance(fastSearchIntro(plan, partners.size()), suggested, request, plan);
            log.info("benefit_chat stage=response traceId={} candidateCount={} status={}", traceId, partners.size(), partners.isEmpty() ? "NO_RESULTS" : "ANSWER");
            return response(reason, partners, partners.isEmpty() ? "NO_RESULTS" : "ANSWER", suggested.stream().limit(3).toList(), traceId);
        }
        checkInterrupted();
        if (previewSupported(plan, resolvedPriorCandidates) && safePreviewCandidates(candidates, plan)) {
            List<BenefitChatResponse.BenefitChatPartner> preview = candidates.stream().limit(RESPONSE_LIMIT)
                    .map(candidate -> toPartner(candidate, nearbyStore(candidate, request, plan))).toList();
            if (!preview.isEmpty()) events.accept("cards", new BenefitChatResponse("", preview, "ANSWER", List.of(), traceId));
        }
        events.accept("status", Map.of("stage", "answering"));
        checkInterrupted();
        long answerStarted = System.nanoTime();
        BenefitChatAnswerer.ChatAnswer answer = answerer.answer(request, plan, candidates);
        checkInterrupted();
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        candidates.forEach(candidate -> byKey.put(candidateKey(candidate), candidate));
        List<BenefitChatResponse.BenefitChatPartner> partners = answer.candidateKeys().stream()
                .map(byKey::get).filter(java.util.Objects::nonNull).distinct().limit(RESPONSE_LIMIT)
                .map(candidate -> toPartner(candidate, nearbyStore(candidate, request, plan))).toList();
        String status = partners.isEmpty() ? "NO_RESULTS" : "ANSWER";
        log.info("benefit_chat stage=response traceId={} elapsedMs={} candidateCount={} status={}",
                traceId, elapsedMillis(answerStarted), partners.size(), status);
        List<String> suggested = new ArrayList<>(answer.suggestedQuestions());
        String reason = applyNearbyGuidance(safeReason(answer.reason(), partners.size()), suggested, request, plan);
        return response(reason, partners, status, suggested.stream().limit(3).toList(), traceId);
    }

    private String applyNearbyGuidance(String reason, List<String> suggested, BenefitChatRequest request, BenefitChatPlan plan) {
        if (plan.nearby() && !plan.location().isBlank()) {
            reason += " 요청하신 지역의 매장 위치는 아직 확인할 수 없어요. 지도에서 해당 지역을 검색해 주세요.";
        } else if (plan.nearby() && request.lat() == null) {
            if (!suggested.contains("내 주변 매장 찾아줘")) suggested.add("내 주변 매장 찾아줘");
            reason += " 가까운 매장을 찾으려면 현재 위치 접근을 허용하거나 지도에서 지역을 검색해 주세요.";
        }
        return reason;
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Benefit chat cancelled");
    }

    private void rethrowCancellation(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Benefit chat cancelled");
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.CancellationException || current instanceof InterruptedException
                    || current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("cancel")) {
                if (current instanceof InterruptedException) Thread.currentThread().interrupt();
                throw failure instanceof RuntimeException runtime ? runtime
                        : new java.util.concurrent.CancellationException("Benefit chat cancelled");
            }
        }
    }

    private boolean fastSearchSupported(BenefitChatPlan plan) {
        return !plan.compareCarriers() && plan.partners().isEmpty()
                && !searchCondition(plan).requiredBusinessTypes().isEmpty();
    }

    private boolean simpleSearchQuestion(BenefitChatRequest request) {
        String question = request == null ? "" : java.util.Objects.toString(request.question(), "");
        return !containsAny(question, "얼마", "금액", "할인액", "할인율", "몇 원", "몇원", "몇 번", "몇번", "몇 회", "몇회",
                "횟수", "조건", "자세히", "상세", "비교", "차이", "사용법", "이용 방법", "가능한지", "되나요");
    }

    private List<Candidate> fastSearchSelection(List<Candidate> candidates) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        return candidates.stream().filter(candidate -> seen.add(partnerIdentity(candidate) + "|"
                        + java.util.Objects.toString(candidate.getCarrier(), "") + "|"
                        + java.util.Objects.toString(candidate.getGrade(), "")))
                .limit(RESPONSE_LIMIT).toList();
    }

    private String partnerIdentity(Candidate candidate) {
        if (candidate.getPartnerId() != null) return candidate.getPartnerId().toString();
        if (candidate.getPartnerName() != null && !candidate.getPartnerName().isBlank()) {
            return candidate.getPartnerName().trim().toLowerCase(java.util.Locale.ROOT);
        }
        return java.util.Objects.toString(candidate.getBenefitId(), candidateKey(candidate));
    }

    private String fastSearchIntro(BenefitChatPlan plan, int count) {
        String topic = !plan.partners().isEmpty() ? plan.partners().get(0)
                : !plan.categories().isEmpty() ? plan.categories().get(0) : "요청하신 조건";
        topic = topic.replaceFirst("\\s*혜택$", "").trim();
        return count == 0 ? "요청하신 조건에 맞는 혜택을 찾지 못했어요."
                : topic + " 혜택을 확인해 보세요.";
    }

    private boolean previewSupported(BenefitChatPlan plan, boolean resolvedPriorCandidates) {
        return !plan.partners().isEmpty()
                || "DETAIL".equals(plan.mode()) && resolvedPriorCandidates
                || !searchCondition(plan).requiredBusinessTypes().isEmpty();
    }

    private boolean safePreviewCandidates(List<Candidate> candidates, BenefitChatPlan plan) {
        return !candidates.isEmpty() && candidates.stream().allMatch(candidate -> matchesPlan(candidate, plan));
    }

    private String chatModelName() {
        return chatModel;
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private BenefitSearchService.ChatSearchResult retrieve(BenefitChatRequest request, BenefitChatPlan plan, List<Float> embedding) {
        List<Carrier> carriers = plan.carriers().isEmpty()
                ? java.util.Collections.singletonList(plan.compareCarriers() ? null : planCarrier(plan))
                : plan.carriers();
        Map<String, Candidate> merged = new LinkedHashMap<>();
        boolean available = true;
        List<String> previousKeys = request.previousCandidateKeys();
        java.util.Set<Long> priorPartnerIds = new java.util.HashSet<>();
        if (plan.followUp() && !previousKeys.isEmpty()) {
            for (Carrier carrier : carriers) {
                benefitSearchService.resolveChatCandidates(previousKeys, carrier, plan.grade())
                        .stream().filter(candidate -> matchesPlan(candidate, plan))
                        .forEach(candidate -> merged.putIfAbsent(candidateKey(candidate), candidate));
            }
            merged.values().stream().map(Candidate::getPartnerId).filter(java.util.Objects::nonNull).forEach(priorPartnerIds::add);
            if ("DETAIL".equals(plan.mode()) || "COMPARE".equals(plan.mode())) {
                if (!merged.isEmpty()) return new BenefitSearchService.ChatSearchResult(List.copyOf(merged.values()), true);
                if ("DETAIL".equals(plan.mode())) return new BenefitSearchService.ChatSearchResult(List.of(), true);
                // Invalid, expired, or no longer eligible identities are ignored; current search may still answer.
            } else {
                merged.clear();
            }
        }
        if (browseRequest(plan)) {
            for (Carrier carrier : carriers) {
                benefitSearchService.browseChatCandidates(carrier, plan.grade(), plan.channel(), CANDIDATE_LIMIT)
                        .stream().filter(candidate -> matchesPlan(candidate, plan))
                        .forEach(candidate -> merged.putIfAbsent(candidateKey(candidate), candidate));
            }
            List<Candidate> values = new ArrayList<>(merged.values());
            if (plan.followUp() && filtersPreviousAlternatives(request, plan) && !previousKeys.isEmpty()) {
                values.removeIf(candidate -> priorPartnerIds.contains(candidate.getPartnerId()));
            }
            return new BenefitSearchService.ChatSearchResult(diversify(values, plan).stream().limit(CANDIDATE_LIMIT).toList(), true);
        }
        BenefitSearchCondition searchCondition = searchCondition(plan);
        for (Carrier carrier : carriers) {
            BenefitSearchService.ChatSearchResult result = benefitSearchService.queryChat(carrier, plan.grade(), embedding,
                    plan.query(), CANDIDATE_LIMIT, searchCondition);
            available &= result.available();
            for (Candidate candidate : result.candidates()) {
                if (!matchesPlan(candidate, plan)) continue;
                merged.putIfAbsent(candidateKey(candidate), candidate);
            }
        }
        List<Candidate> values = new ArrayList<>(merged.values());
        if (plan.followUp() && filtersPreviousAlternatives(request, plan) && !previousKeys.isEmpty()) {
            List<Candidate> alternatives = values.stream().filter(c -> !priorPartnerIds.contains(c.getPartnerId())).toList();
            values = new ArrayList<>(alternatives);
        }
        return new BenefitSearchService.ChatSearchResult(diversify(values, plan).stream().limit(CANDIDATE_LIMIT).toList(), available);
    }

    private BenefitSearchCondition searchCondition(BenefitChatPlan plan) {
        List<String> requiredTypes = BenefitChatCategories.businessTypes(plan.categories());
        List<String> excludedTypes = BenefitChatCategories.businessTypes(plan.excludedPartners());
        List<String> excludedNames = plan.excludedPartners().stream()
                .filter(category -> BenefitChatCategories.businessTypes(category).isEmpty()).toList();
        return new BenefitSearchCondition(requiredTypes, excludedTypes, List.of(), List.of(),
                plan.partners(), excludedNames);
    }

    private boolean asksAlternative(BenefitChatRequest request) {
        return request.question() != null && containsAny(request.question(), "다른 곳", "다른 제휴처", "다른 혜택", "말고", "대신");
    }

    private boolean filtersPreviousAlternatives(BenefitChatRequest request, BenefitChatPlan plan) {
        if (!asksAlternative(request) || request.question() == null) return false;
        return plan.excludedPartners().stream()
                .filter(excluded -> BenefitChatCategories.businessTypes(excluded).isEmpty())
                .noneMatch(excluded -> !isGenericAlternativeReferent(excluded)
                && java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(excluded) + "\\s*(?:은|는)?\\s*(?:말고|제외하고|빼고|없이|아니고|아니라)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(request.question()).find());
    }

    private boolean isGenericAlternativeReferent(String value) {
        return value != null && value.replaceAll("\\s+", "").matches("(?:그|이|저)(?:거|것|곳|혜택|제휴처)");
    }

    private boolean browseRequest(BenefitChatPlan plan) {
        return !hasMeaningfulTopic(plan) && (!"ANY".equals(plan.channel()) || plan.nearby());
    }

    private boolean hasMeaningfulTopic(BenefitChatPlan plan) {
        java.util.Set<String> generic = java.util.Set.of("혜택", "멤버십", "할인", "통신사", "등급", "가능", "추천", "찾아", "주세요", "부탁", "비교", "온라인", "오프라인", "근처", "주변", "어디", "알려", "이용", "사용", "쓸", "쓸만한", "정보", "좀", "online", "offline", "benefit", "benefits", "membership", "discount", "nearby");
        if (plan.partners().stream().anyMatch(partner -> meaningfulToken(partner, generic))) return true;
        if (plan.categories().stream().anyMatch(category -> meaningfulToken(category, generic))) return true;
        return meaningfulToken(plan.query(), generic);
    }

    private boolean meaningfulToken(String text, java.util.Set<String> generic) {
        if (text == null) return false;
        return java.util.Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .map(this::stripKoreanIntentEnding)
                .anyMatch(token -> token.length() > 1 && !generic.contains(token));
    }

    private String stripKoreanIntentEnding(String token) {
        for (String ending : List.of("해주세요", "해줘", "찾아줘", "알려줘", "에서", "으로", "에게", "한테", "은", "는", "이", "가", "을", "를", "도", "만", "좀", "줘", "서")) {
            if (token.length() > ending.length() + 1 && token.endsWith(ending)) {
                return token.substring(0, token.length() - ending.length());
            }
        }
        return token;
    }

    private List<Candidate> diversify(List<Candidate> candidates, BenefitChatPlan plan) {
        if (candidates.size() <= 1) return candidates;
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String group = plan.compareCarriers() ? candidate.getCarrier()
                    : String.valueOf(candidate.getPartnerId());
            groups.computeIfAbsent(group == null ? "" : group, ignored -> new ArrayList<>()).add(candidate);
        }
        List<Candidate> result = new ArrayList<>();
        int index = 0;
        boolean added;
        do {
            added = false;
            for (List<Candidate> group : groups.values()) {
                if (index < group.size()) { result.add(group.get(index)); added = true; }
            }
            index++;
        } while (added && result.size() < CANDIDATE_LIMIT);
        return result;
    }

    private Integer ordinal(String question) {
        if (question == null) return null;
        if (containsAny(question, "첫 번째", "첫번째", "첫째")) return 0;
        if (containsAny(question, "두 번째", "두번째", "둘째")) return 1;
        if (containsAny(question, "세 번째", "세번째", "셋째")) return 2;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([1-9])\\s*번째").matcher(question);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) - 1 : null;
    }

    private Carrier planCarrier(BenefitChatPlan plan) {
        return plan.carriers().isEmpty() ? null : plan.carriers().get(0);
    }

    private boolean matchesPlan(Candidate candidate, BenefitChatPlan plan) {
        BenefitSearchCondition condition = searchCondition(plan);
        if (!condition.matchesPartnerName(candidate.getPartnerName())) return false;
        String usage = candidate.getUsageType() == null ? "" : candidate.getUsageType().toUpperCase(java.util.Locale.ROOT);
        if (candidate.getConditions() != null
                && candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.UNRESOLVED
                && !"ANY".equals(plan.channel())) return false;
        if (candidate.getConditions() != null && !candidate.getConditions().offers().isEmpty()
                && !benefitOfferMatcher.hasKnownOffers(candidate.getConditions()) && !"ANY".equals(plan.channel())) return false;
        if (benefitOfferMatcher.hasKnownOffers(candidate.getConditions())) {
            UsageType channel = "ONLINE".equals(plan.channel()) ? UsageType.ONLINE
                    : "OFFLINE".equals(plan.channel()) ? UsageType.OFFLINE : null;
            Carrier candidateCarrier = carrier(candidate);
            if (plan.grade() != null && candidateCarrier == null) return false;
            if (!benefitOfferMatcher.supports(candidate.getConditions(), channel, candidateCarrier, eligibleGrades(candidate, plan))) {
                if (!"ANY".equals(plan.channel())
                        || candidate.getConditions().offerResolution() != com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL
                        || candidate.getTierBenefitId() == null) return false;
            }
        } else {
            if ("ONLINE".equals(plan.channel()) && !List.of("ONLINE", "BOTH").contains(usage)) return false;
            if ("OFFLINE".equals(plan.channel()) && !List.of("OFFLINE", "BOTH").contains(usage)) return false;
        }
        List<String> businessTypes = condition.requiredBusinessTypes();
        if (!businessTypes.isEmpty() && !businessTypes.contains(candidate.getBusinessType())
                && !(businessTypes.contains("CONVENIENCE_STORE") && matchesConvenienceStore(candidate))) return false;
        if (condition.excludedBusinessTypes().contains(candidate.getBusinessType())) return false;
        return true;
    }

    private Candidate candidateForPlan(Candidate candidate, BenefitChatPlan plan) {
        if (!benefitOfferMatcher.hasKnownOffers(candidate.getConditions())) {
            if (candidate.getConditions() == null || candidate.getConditions().offerResolution() == null) return candidate;
            if (candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.UNRESOLVED) {
                return candidate.toBuilder().conditions(candidate.getConditions().withoutOffers())
                        .usageType("UNKNOWN")
                        .description("개별 혜택의 이용 채널과 세부 조건은 확인이 필요합니다.")
                        .manual(null).onlineContext(null).offlineContext(null).build();
            }
            return candidate.toBuilder().conditions(candidate.getConditions().withoutOffers())
                    .context(null).onlineContext(null).offlineContext(null).manual(null)
                    .description("개별 혜택의 채널과 등급을 구분할 수 없는 정보입니다.").build();
        }
        UsageType channel = "ONLINE".equals(plan.channel()) ? UsageType.ONLINE
                : "OFFLINE".equals(plan.channel()) ? UsageType.OFFLINE : null;
        var conditions = benefitOfferMatcher.scope(candidate.getConditions(), channel, carrier(candidate), eligibleGrades(candidate, plan));
        List<BenefitOffer> offers = conditions.offers();
        if (offers.isEmpty() && candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL
                && channel == null) {
            return candidate.toBuilder().conditions(conditions).usageType(candidate.getUsageType())
                    .description("일부 개별 혜택은 확인되었지만 이 등급에 연결할 수 없는 원문이 있어 추가 확인이 필요합니다.")
                    .manual(null).onlineContext(null).offlineContext(null).build();
        }
        String summary = offers.stream().map(offer -> {
            String scope = offer.usageType() == null ? "채널 미확정" : offer.usageType().name();
            String source = candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE
                    ? null : offer.sourceText();
            return (channel == null ? scope + ": " : "") + first(offer.title(), source);
        }).filter(java.util.Objects::nonNull).reduce((a, b) -> a + " / " + b).orElse("");
        String detail = offers.stream().map(offer -> String.join(" ",
                        java.util.stream.Stream.of(candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE ? null : offer.sourceText(), String.join(" ", offer.products()),
                                String.join(" ", offer.availability()), String.join(" ", offer.limits()),
                                String.join(" ", offer.usage()), String.join(" ", offer.exclusions()),
                                String.join(" ", BenefitFactFormatter.format(offer.facts())))
                                .filter(value -> value != null && !value.isBlank()).toList()))
                .filter(value -> !value.isBlank()).reduce((a, b) -> a + " / " + b).orElse("");
        if (channel == null) {
            detail = offers.stream().map(offer -> (offer.usageType() == null ? "UNKNOWN" : offer.usageType().name()) + ": " + String.join(" ",
                            java.util.stream.Stream.of(candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE ? null : offer.sourceText(), String.join(" ", offer.products()),
                                    String.join(" ", offer.availability()), String.join(" ", offer.limits()),
                                    String.join(" ", offer.usage()), String.join(" ", offer.exclusions()),
                                    String.join(" ", BenefitFactFormatter.format(offer.facts())))
                                    .filter(value -> value != null && !value.isBlank()).toList()))
                    .filter(value -> !value.isBlank()).reduce((a, b) -> a + " / " + b).orElse("");
        }
        String displayContext = candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE
                ? candidate.getContext() : summary;
        List<UsageType> offerChannels = offers.stream().map(BenefitOffer::usageType).distinct().toList();
        boolean hasOnline = offerChannels.contains(UsageType.ONLINE) || offerChannels.contains(UsageType.BOTH);
        boolean hasOffline = offerChannels.contains(UsageType.OFFLINE) || offerChannels.contains(UsageType.BOTH);
        String selectedUsageType = channel != null ? channel.name()
                : offerChannels.contains(null) || !hasOnline && !hasOffline ? "UNKNOWN"
                : hasOnline && hasOffline ? "BOTH" : hasOnline ? "ONLINE" : "OFFLINE";
        String onlineSummary = offers.stream().filter(offer -> offer.usageType() == UsageType.ONLINE || offer.usageType() == UsageType.BOTH)
                .map(offer -> first(offer.title(), candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE ? null : offer.sourceText())).filter(java.util.Objects::nonNull)
                .distinct().reduce((a, b) -> a + " / " + b).orElse("");
        String offlineSummary = offers.stream().filter(offer -> offer.usageType() == UsageType.OFFLINE || offer.usageType() == UsageType.BOTH)
                .map(offer -> first(offer.title(), candidate.getConditions().offerResolution() == com.itplace.userapi.benefit.dto.OfferResolution.SINGLE ? null : offer.sourceText())).filter(java.util.Objects::nonNull)
                .distinct().reduce((a, b) -> a + " / " + b).orElse("");
        return candidate.toBuilder().conditions(conditions)
                .usageType(selectedUsageType)
                .description(summary).context(displayContext == null || displayContext.isBlank() ? null : displayContext)
                .manual(detail.isBlank() ? null : detail)
                .onlineContext(onlineSummary.isBlank() ? null : onlineSummary)
                .offlineContext(offlineSummary.isBlank() ? null : offlineSummary)
                .build();
    }

    private Candidate reflectSelectedGrade(Candidate candidate, BenefitChatPlan plan) {
        if (!Boolean.TRUE.equals(candidate.getAllGrade()) || plan.grade() == null) return candidate;
        Carrier carrier = carrier(candidate);
        if (carrier == null || !plan.carriers().isEmpty() && !plan.carriers().contains(carrier)
                || !referenceDataService.isSelectableProfile(carrier, plan.grade())) return candidate;
        return candidate.toBuilder().grade(plan.grade().name()).build();
    }

    private Carrier carrier(Candidate candidate) {
        try { return candidate.getCarrier() == null ? null : Carrier.valueOf(candidate.getCarrier()); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private List<Grade> eligibleGrades(Candidate candidate, BenefitChatPlan plan) {
        if (plan.grade() == null) return List.of();
        Carrier carrier = carrier(candidate);
        return carrier == null ? List.of() : referenceDataService.gradesForProfile(carrier, plan.grade());
    }

    private boolean matchesConvenienceStore(Candidate candidate) {
        String text = String.join(" ", java.util.Objects.toString(candidate.getCategory(), ""),
                java.util.Objects.toString(candidate.getMainCategory(), ""), java.util.Objects.toString(candidate.getPartnerName(), ""))
                .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        return "CONVENIENCE_STORE".equals(candidate.getBusinessType()) || text.contains("편의점");
    }

    private boolean gradeAffectsBenefits(List<Candidate> candidates) {
        return candidates.stream().anyMatch(candidate -> {
            List<BenefitOffer> offers = candidate.getConditions() == null ? List.of() : candidate.getConditions().offers();
            return Boolean.FALSE.equals(candidate.getAllGrade()) || offers.stream().anyMatch(offer -> !Boolean.TRUE.equals(offer.allGrades())
                    && (Boolean.FALSE.equals(offer.allGrades()) || !offer.gradeCodes().isEmpty()));
        });
    }

    private List<String> gradeChoices(Carrier carrier) {
        return referenceDataService.getReferenceData().grades().stream()
                .filter(grade -> carrier.name().equals(grade.carrier()) && grade.active() && grade.profileSelectable())
                .map(grade -> carrierLabel(carrier) + " " + grade.label() + " 등급이야")
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), choices -> {
                    choices.add(carrierLabel(carrier) + " 등급은 모르겠어");
                    return List.copyOf(choices);
                }));
    }

    private String carrierLabel(Carrier carrier) {
        return carrier == Carrier.LGU ? "LG U+" : carrier.name();
    }

    private String carrierLabel(String carrier) {
        try { return carrierLabel(Carrier.valueOf(carrier)); }
        catch (RuntimeException ignored) { return carrier; }
    }

    private BenefitChatResponse.BenefitChatPartner toPartner(Candidate c, StoreDetailResponse store) {
        return new BenefitChatResponse.BenefitChatPartner(c.getPartnerId(), c.getBenefitId(), c.getPartnerName(),
                c.getBenefitName(), c.getCategory(), store == null || store.getStore() == null ? null : store.getStore().getStoreId(),
                store == null || store.getStore() == null ? null : store.getStore().getStoreName(), store == null ? 0 : 1,
                chatDisplayText(first(c.getContext(), c.getDescription())), c.getImgUrl(), candidateKey(c), c.getPolicyId(), c.getTierBenefitId(),
                c.getCarrier(), c.getGrade(), c.getUsageType(), safeHttpUrl(c.getSourceUrl()),
                c.getSourceCapturedAt() == null ? null : c.getSourceCapturedAt().toString(),
                benefitOfferMatcher.hasKnownOffers(c.getConditions()) ? c.getConditions().publicView().offers() : List.of(),
                c.getConditions() == null ? null : c.getConditions().offerResolution(),
                c.getConditions() == null ? null : c.getConditions().publicView().channelDecision(),
                List.of(),
                c.getConditions() == null ? List.of() : c.getConditions().publicView().normalizationIssues());
    }

    private String candidateKey(Candidate c) {
        return c.getCandidateKey() != null ? c.getCandidateKey() : "benefit:%s:policy:%s:tier:%s".formatted(
                c.getBenefitId(), c.getPolicyId(), c.getTierBenefitId() == null ? "policy" : c.getTierBenefitId());
    }

    private String safeHttpUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? value : null;
        } catch (RuntimeException e) { return null; }
    }

    private StoreDetailResponse nearbyStore(Candidate c, BenefitChatRequest request, BenefitChatPlan plan) {
        if (!plan.nearby() || request.lat() == null || !plan.location().isBlank()) return null;
        try {
            return storeService.findNearbyByBenefitCandidate(request.lat(), request.lng(), c.getPartnerId(),
                    c.getPartnerName(), c.getCategory(), c.getBenefitId(), request.lat(), request.lng())
                    .stream().findFirst().orElse(null);
        } catch (RuntimeException e) {
            rethrowCancellation(e);
            log.debug("nearby store enrichment failed: candidateKey={}", candidateKey(c));
            return null;
        }
    }

    private boolean containsAny(String text, String... values) {
        return java.util.Arrays.stream(values).anyMatch(text::contains);
    }

    private String safeReason(String reason, int count) {
        if (reason != null && !reason.isBlank()) return reason.substring(0, Math.min(3000, reason.length()));
        if (count == 0) return "요청하신 조건에 맞는 혜택을 찾지 못했어요.";
        return "요청하신 조건과 일치하는 혜택 %d개를 찾았어요.".formatted(count);
    }

    private static String first(String a, String b) { return a == null || a.isBlank() ? b : a; }

    private static String chatDisplayText(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("(?i)\\b(?:OFFLINE|ONLINE|BOTH|UNKNOWN)\\s*:", " ")
                .replaceAll("\\s*/\\s*/+", " / ").replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private BenefitChatResponse response(String reason, List<BenefitChatResponse.BenefitChatPartner> partners,
                                         String status, List<String> questions, String traceId) {
        return new BenefitChatResponse(reason, partners, status, questions, traceId);
    }
}
