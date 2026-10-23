package com.itplace.userapi.ai.rag.postgres;

import com.itplace.userapi.ai.rag.service.BenefitSearchCondition;
import com.itplace.userapi.ai.rag.service.EmbeddingSettings;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class PostgresBenefitSearchRepository {
    private static final Logger log = LoggerFactory.getLogger(PostgresBenefitSearchRepository.class);
    private static final AtomicLong LAST_VECTOR_WARNING = new AtomicLong();
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EmbeddingSettings embeddingSettings;
    @Value("${app.ai.benefits.embedding-version:benefit-carrier-grade-rag-v3}")
    private String embeddingVersion = "benefit-carrier-grade-rag-v3";

    public PostgresBenefitSearchRepository(JdbcTemplate jdbc, EmbeddingSettings embeddingSettings) {
        this.jdbc = jdbc; this.embeddingSettings = embeddingSettings;
    }

    public List<BenefitSearchHit> searchVector(List<Float> vector, Carrier carrier, Grade grade,
                                               BenefitSearchCondition condition, int limit) {
        if (vector == null || vector.size() != embeddingSettings.dimensions())
            throw new IllegalArgumentException("Embedding dimension does not match the configured provider model");
        if (vector.stream().anyMatch(v -> v == null || !Float.isFinite(v))
                || vector.stream().mapToDouble(v -> (double) v * v).sum() == 0)
            throw new IllegalArgumentException("Embedding must be finite and nonzero");
        String sql = """
                SELECT d.benefitid, d.benefitcarrierpolicyid, d.carriertierbenefitid,
                       1 - (d.embedding <=> ?::vector) AS score, d.metadata::text
                FROM benefit_search_document d
                JOIN benefit b ON b.benefitId = d.benefitid
                JOIN benefitCarrierPolicy p ON p.benefitCarrierPolicyId = d.benefitcarrierpolicyid
                LEFT JOIN carrierTierBenefit t ON t.carrierTierBenefitId = d.carriertierbenefitid
                WHERE d.embedding IS NOT NULL AND d.embedding_model=? AND d.embedding_dimensions=? AND d.embedding_version=? AND d.active
                  AND b.active IS NOT FALSE AND p.active IS NOT FALSE
                  AND p.benefitId = b.benefitId
                  AND (d.carriertierbenefitid IS NULL OR t.benefitCarrierPolicyId = p.benefitCarrierPolicyId)
                """ + filters(carrier, grade, condition) + " ORDER BY d.embedding <=> ?::vector, d.document_id LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(vectorLiteral(vector)); args.add(embeddingSettings.modelIdentity()); args.add(embeddingSettings.dimensions()); args.add(embeddingVersion); addFilters(args, carrier, grade, condition);
        args.add(vectorLiteral(vector)); args.add(Math.max(1, limit));
        return jdbc.query(sql, (rs, row) -> hit(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getDouble(4), rs.getString(5)), args.toArray());
    }

    public List<BenefitSearchHit> searchLexical(String query, Carrier carrier, Grade grade,
                                                BenefitSearchCondition condition, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        if (q.isBlank()) return List.of();
        List<String> terms = queryTerms(q);
        List<Object> args = new ArrayList<>();
        String rankSql = rankExpression(args, q);
        String matchSql = matchExpression(args, terms);
        String sql = """
                SELECT d.benefitid, d.benefitcarrierpolicyid, d.carriertierbenefitid, %s AS score, d.metadata::text
                FROM benefit_search_document d
                JOIN benefit b ON b.benefitId = d.benefitid
                JOIN benefitCarrierPolicy p ON p.benefitCarrierPolicyId = d.benefitcarrierpolicyid
                LEFT JOIN carrierTierBenefit t ON t.carrierTierBenefitId = d.carriertierbenefitid
                WHERE d.active AND b.active IS NOT FALSE AND p.active IS NOT FALSE
                  AND p.benefitId = b.benefitId
                  AND (d.carriertierbenefitid IS NULL OR t.benefitCarrierPolicyId = p.benefitCarrierPolicyId)
                  AND (%s)
                """.formatted(rankSql, matchSql) + filters(carrier, grade, condition) + " ORDER BY score DESC, d.document_id LIMIT ?";
        addFilters(args, carrier, grade, condition); args.add(Math.max(1, limit));
        return jdbc.query(sql, (rs, row) -> hit(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getDouble(4), rs.getString(5)), args.toArray());
    }

    public List<BenefitSearchHit> searchHybrid(List<Float> vector, String query, Carrier carrier, Grade grade,
                                              BenefitSearchCondition condition, int limit) {
        List<BenefitSearchHit> vectorHits = List.of();
        List<BenefitSearchHit> lexicalHits = List.of();
        try {
            if (vector != null && !vector.isEmpty()) vectorHits = searchVector(vector, carrier, grade, condition, limit * 4);
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            long previous = LAST_VECTOR_WARNING.get();
            if (now - previous >= 60_000 && LAST_VECTOR_WARNING.compareAndSet(previous, now))
                log.warn("PostgreSQL benefit vector search failed; continuing with lexical search. type={}", e.getClass().getSimpleName());
        }
        try { lexicalHits = searchLexical(query, carrier, grade, condition, limit * 4); }
        catch (RuntimeException e) { if (vectorHits.isEmpty()) throw e; }
        log.info("PostgreSQL hybrid retrieval vectorHits={} lexicalHits={} embeddingModel={} embeddingDimensions={}",
                vectorHits.size(), lexicalHits.size(), embeddingSettings.modelIdentity(), embeddingSettings.dimensions());
        java.util.Map<String, Double> ranks = new java.util.HashMap<>();
        java.util.Map<String, BenefitSearchHit> hits = new java.util.HashMap<>();
        merge(vectorHits, ranks, hits); merge(lexicalHits, ranks, hits);
        return hits.entrySet().stream().sorted((a,b) -> {
                    int byScore = Double.compare(ranks.get(b.getKey()), ranks.get(a.getKey()));
                    return byScore != 0 ? byScore : a.getKey().compareTo(b.getKey());
                })
                .limit(Math.max(1, limit)).map(e -> {
                    BenefitSearchHit h=e.getValue(); return new BenefitSearchHit(h.benefitId(),h.policyId(),h.tierBenefitId(),ranks.get(e.getKey()), h.metadata());
                }).toList();
    }

    public List<BenefitSearchHit> searchList(String query, List<Float> vector, MainCategory mainCategory,
                                             String category, UsageType usageType, List<Carrier> carriers, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        if (q.isBlank()) return List.of();
        List<BenefitSearchHit> lexical = List.of();
        List<BenefitSearchHit> semantic = List.of();
        RuntimeException lexicalFailure = null;
        try { lexical = listLexical(q, mainCategory, category, usageType, carriers, limit * 4); }
        catch (RuntimeException e) { lexicalFailure = e; }
        try {
            if (vector != null && !vector.isEmpty()) semantic = listVector(vector, mainCategory, category, usageType, carriers, limit * 4);
        } catch (RuntimeException e) { if (lexical.isEmpty()) throw e; }
        log.info("PostgreSQL list retrieval vectorHits={} lexicalHits={} embeddingModel={} embeddingDimensions={}",
                semantic.size(), lexical.size(), embeddingSettings.modelIdentity(), embeddingSettings.dimensions());
        if (lexicalFailure != null && semantic.isEmpty()) throw lexicalFailure;
        java.util.Map<String,Double> scores = new java.util.HashMap<>();
        java.util.Map<String,BenefitSearchHit> unique = new java.util.HashMap<>();
        mergeBenefits(lexical, scores, unique, 1.15); mergeBenefits(semantic, scores, unique, 1.0);
        return unique.entrySet().stream().sorted((a,b) -> {
                    int byScore = Double.compare(scores.get(b.getKey()), scores.get(a.getKey()));
                    return byScore != 0 ? byScore : a.getKey().compareTo(b.getKey());
                })
                .limit(Math.max(1, limit)).map(e -> {
                    BenefitSearchHit h=e.getValue(); return new BenefitSearchHit(h.benefitId(),h.policyId(),h.tierBenefitId(),scores.get(e.getKey()),h.metadata());
                }).toList();
    }

    private List<BenefitSearchHit> listLexical(String q, MainCategory main, String category, UsageType usage, List<Carrier> carriers, int limit) {
        List<String> terms = queryTerms(q);
        List<Object> args = new ArrayList<>();
        String rankSql = rankExpression(args, q);
        String matchSql = matchExpression(args, terms);
        String sql = """
                SELECT d.benefitid,d.benefitcarrierpolicyid,d.carriertierbenefitid,%s,d.metadata::text
                FROM benefit_search_document d JOIN benefit b ON b.benefitId=d.benefitid
                JOIN benefitCarrierPolicy p ON p.benefitCarrierPolicyId=d.benefitcarrierpolicyid
                LEFT JOIN carrierTierBenefit t ON t.carrierTierBenefitId=d.carriertierbenefitid
                WHERE d.active AND b.active IS NOT FALSE AND p.active IS NOT FALSE
                  AND p.benefitId=b.benefitId
                  AND (d.carriertierbenefitid IS NULL OR t.benefitCarrierPolicyId=p.benefitCarrierPolicyId)
                  AND (%s)
                """.formatted(rankSql,matchSql) + listFilters(main, category, usage, carriers) + " ORDER BY 4 DESC,d.document_id LIMIT ?";
        addListFilters(args,main,category,usage,carriers); args.add(limit);
        return jdbc.query(sql,(rs,row)->hit(rs.getLong(1),rs.getLong(2),(Long)rs.getObject(3),rs.getDouble(4),rs.getString(5)),args.toArray());
    }

    private static String rankExpression(List<Object> args, String query) {
        List<String> phraseVariants = aliasVariants(query);
        String partnerMatch = phraseMatch(args, "lower(coalesce(d.metadata->>'partnerName',''))", phraseVariants);
        String benefitMatch = phraseMatch(args, "lower(coalesce(d.metadata->>'benefitName',''))", phraseVariants);
        String carrierMatch = phraseMatch(args, "lower(coalesce(d.metadata->>'carrierBenefitName',''))", phraseVariants);
        String textMatch = phraseMatch(args, "lower(d.searchable_text)", phraseVariants);
        StringBuilder score = new StringBuilder("(CASE WHEN ").append(partnerMatch).append(" THEN 60 WHEN ")
                .append(benefitMatch).append(" THEN 50 WHEN ").append(carrierMatch).append(" THEN 40 WHEN ")
                .append(textMatch).append(" THEN 30 ELSE 0 END");

        String intentTypes = categoryIntentTypes(query);
        if (intentTypes != null) {
            score.append(" + CASE WHEN d.metadata->>'businessType'=ANY(string_to_array(?, ',')) THEN 100 ELSE 0 END");
            args.add(intentTypes);
        }
        for (String token : queryTokens(query)) {
            score.append(" + CASE WHEN (").append(matchExpression(args, searchVariants(token)))
                    .append(") THEN 10 ELSE 0 END");
        }
        return score.append(')').toString();
    }

    private static String phraseMatch(List<Object> args, String expression, List<String> variants) {
        List<String> clauses = new ArrayList<>();
        for (String variant : variants) {
            clauses.add("strpos(" + expression + ",?)>0");
            args.add(variant);
        }
        return "(" + String.join(" OR ", clauses) + ")";
    }

    private static String matchExpression(List<Object> args, List<String> terms) {
        List<String> clauses = new ArrayList<>();
        for (String term : terms) {
            clauses.add("lower(d.searchable_text) LIKE ? ESCAPE E'\\\\' OR EXISTS " +
                    "(SELECT 1 FROM jsonb_array_elements_text(coalesce(d.metadata->'tags','[]'::jsonb)) x WHERE lower(x)=?)");
            args.add(likePattern(term)); args.add(term);
        }
        return String.join(" OR ", clauses);
    }

    private static List<String> queryTerms(String query) {
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        terms.add(query);
        for (String token : queryTokens(query)) terms.addAll(searchVariants(token));
        return List.copyOf(terms);
    }

    private static List<String> queryTokens(String query) {
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        java.util.Set<String> generic = java.util.Set.of("혜택","멤버십","할인","추천","가능","찾아","주세요","부탁","비교","온라인","오프라인","사용","이용","해주세요");
        for (String token : query.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && !generic.contains(token)) tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    private static List<String> aliasVariants(String term) {
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        variants.add(term);
        String normalized = term.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("cu")) variants.add("씨유");
        if (normalized.equals("씨유")) variants.add("cu");
        if (normalized.equals("gs25") || normalized.equals("지에스25")) {
            variants.add("gs25");
            variants.add("지에스25");
        }
        return List.copyOf(variants);
    }

    private static List<String> searchVariants(String token) {
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>(aliasVariants(token));
        switch (token.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT)) {
            case "편의점" -> variants.add("convenience_store");
            case "카페", "커피" -> {
                variants.add("beverage_cafe");
                variants.add("dessert");
            }
            case "영화", "영화관" -> variants.add("movie_theater");
            default -> { }
        }
        return List.copyOf(variants);
    }

    private static String categoryIntentTypes(String query) {
        String normalized = query.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "편의점" -> "CONVENIENCE_STORE";
            case "카페", "커피" -> "BEVERAGE_CAFE,DESSERT";
            case "영화", "영화관" -> "MOVIE_THEATER";
            default -> null;
        };
    }
    private static String likePattern(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private List<BenefitSearchHit> listVector(List<Float> vector, MainCategory main, String category, UsageType usage, List<Carrier> carriers, int limit) {
        String literal = vectorLiteralChecked(vector);
        String sql = """
                SELECT d.benefitid,d.benefitcarrierpolicyid,d.carriertierbenefitid,1-(d.embedding <=> ?::vector),d.metadata::text
                FROM benefit_search_document d JOIN benefit b ON b.benefitId=d.benefitid
                JOIN benefitCarrierPolicy p ON p.benefitCarrierPolicyId=d.benefitcarrierpolicyid
                LEFT JOIN carrierTierBenefit t ON t.carrierTierBenefitId=d.carriertierbenefitid
                WHERE d.embedding IS NOT NULL AND d.embedding_model=? AND d.embedding_dimensions=? AND d.embedding_version=? AND d.active AND b.active IS NOT FALSE AND p.active IS NOT FALSE
                  AND p.benefitId=b.benefitId
                  AND (d.carriertierbenefitid IS NULL OR t.benefitCarrierPolicyId=p.benefitCarrierPolicyId)
                """ + listFilters(main, category, usage, carriers) + " ORDER BY d.embedding <=> ?::vector,d.document_id LIMIT ?";
        List<Object> args = new ArrayList<>(); args.add(literal); args.add(embeddingSettings.modelIdentity()); args.add(embeddingSettings.dimensions()); args.add(embeddingVersion); addListFilters(args,main,category,usage,carriers); args.add(literal); args.add(limit);
        return jdbc.query(sql,(rs,row)->hit(rs.getLong(1),rs.getLong(2),(Long)rs.getObject(3),rs.getDouble(4),rs.getString(5)),args.toArray());
    }

    private static String listFilters(MainCategory main,String category,UsageType usage,List<Carrier> carriers) {
        StringBuilder sql=new StringBuilder();
        if(main!=null) sql.append(" AND d.metadata->>'mainCategory'=?");
        if(category!=null&&!category.isBlank()) sql.append(" AND lower(d.metadata->>'category')=lower(?)");
        if(usage!=null) sql.append(" AND p.usageType IN (?,?)");
        if(carriers!=null&&!carriers.isEmpty()) sql.append(" AND p.carrier=ANY(string_to_array(?, ','))");
        return sql.toString();
    }
    private static void addListFilters(List<Object> args,MainCategory main,String category,UsageType usage,List<Carrier> carriers) {
        if(main!=null) args.add(main.name()); if(category!=null&&!category.isBlank()) args.add(category.strip());
        if(usage!=null) {args.add(usage.getLabel());args.add(UsageType.BOTH.getLabel());}
        if(carriers!=null&&!carriers.isEmpty()) args.add(carriers.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
    }
    private static void mergeBenefits(List<BenefitSearchHit> hits,Map<String,Double> scores,Map<String,BenefitSearchHit> docs,double weight) {
        Set<Long> seen=new java.util.HashSet<>(); int rank=0;
        for(BenefitSearchHit h:hits) { if(!seen.add(h.benefitId())) continue; rank++;
            String id=Long.toString(h.benefitId()); scores.merge(id,weight/(60+rank),Double::sum); docs.putIfAbsent(id,h); }
    }
    private BenefitSearchHit hit(long benefit,long policy,Long tier,double score,String metadata) {
        try { return new BenefitSearchHit(benefit,policy,tier,score,mapper.readTree(metadata)); }
        catch(Exception e) { throw new IllegalStateException("Invalid search document metadata",e); }
    }
    private String vectorLiteralChecked(List<Float> vector) {
        if (vector==null || vector.size()!=embeddingSettings.dimensions() || vector.stream().anyMatch(v->v==null||!Float.isFinite(v))
                || vector.stream().mapToDouble(v->(double)v*v).sum()==0) throw new IllegalArgumentException("Invalid embedding vector");
        return vectorLiteral(vector);
    }

    private static void merge(List<BenefitSearchHit> hits, java.util.Map<String,Double> ranks,
                              java.util.Map<String,BenefitSearchHit> snapshots) {
        for (int i=0;i<hits.size();i++) { BenefitSearchHit h=hits.get(i); String k=documentKey(h);
            ranks.merge(k,1.0/(60+i+1),Double::sum); snapshots.putIfAbsent(k,h); }
    }

    private static String documentKey(BenefitSearchHit hit) {
        JsonNode metadata = hit.metadata();
        String documentId = metadata == null ? null : metadata.path("documentId").asText(null);
        return documentId == null || documentId.isBlank()
                ? hit.benefitId()+":"+hit.policyId()+":"+hit.tierBenefitId() : documentId;
    }

    private String filters(Carrier carrier, Grade grade, BenefitSearchCondition c) {
        StringBuilder s = new StringBuilder();
        if (carrier != null) s.append(" AND p.carrier = ?");
        if (grade != null) s.append(" AND ((t.carrierTierBenefitId IS NOT NULL AND (t.grade = ? OR EXISTS (SELECT 1 FROM membership_grade_match gm WHERE gm.carrier_code=p.carrier AND gm.profile_grade=? AND gm.benefit_grade=t.grade) OR (t.isAll IS TRUE AND EXISTS (SELECT 1 FROM membership_grade g WHERE g.carrier_code=p.carrier AND g.code=? AND g.active AND g.all_grade_member) AND NOT EXISTS (SELECT 1 FROM membership_grade required_grade WHERE required_grade.carrier_code=p.carrier AND required_grade.active AND required_grade.all_grade_member AND NOT EXISTS (SELECT 1 FROM carrierTierBenefit required_tier WHERE required_tier.benefitCarrierPolicyId=p.benefitCarrierPolicyId AND required_tier.context=t.context AND required_tier.grade=required_grade.code))))) OR (t.carrierTierBenefitId IS NULL AND d.metadata->>'offerKey' IS NOT NULL AND (d.metadata->>'grade' = ? OR EXISTS (SELECT 1 FROM membership_grade_match gm WHERE gm.carrier_code=p.carrier AND gm.profile_grade=? AND gm.benefit_grade=d.metadata->>'grade'))))");
        if (c != null && !c.requiredBusinessTypes().isEmpty()) s.append(" AND d.metadata->>'businessType' = ANY (string_to_array(?, ','))");
        if (c != null && !c.excludedBusinessTypes().isEmpty()) s.append(" AND coalesce(d.metadata->>'businessType','') <> ALL (string_to_array(?, ','))");
        String canonicalPartner = "regexp_replace(lower(coalesce(d.metadata->>'partnerName','')), '[^[:alnum:]]', '', 'g')";
        if (c != null && !c.requiredPartnerNames().isEmpty()) s.append(" AND ").append(canonicalPartner).append(" = ANY (string_to_array(?, ','))");
        if (c != null && !c.excludedPartnerNames().isEmpty()) s.append(" AND ").append(canonicalPartner).append(" <> ALL (string_to_array(?, ','))");
        return s.toString();
    }

    private void addFilters(List<Object> args, Carrier carrier, Grade grade, BenefitSearchCondition c) {
        if (carrier != null) args.add(carrier.name());
        if (grade != null) { args.add(grade.getCode()); args.add(grade.getCode()); args.add(grade.getCode()); args.add(grade.getCode()); args.add(grade.getCode()); }
        if (c != null && !c.requiredBusinessTypes().isEmpty()) args.add(String.join(",", c.requiredBusinessTypes()));
        if (c != null && !c.excludedBusinessTypes().isEmpty()) args.add(String.join(",", c.excludedBusinessTypes()));
        if (c != null && !c.requiredPartnerNames().isEmpty()) args.add(String.join(",", c.requiredPartnerNames()));
        if (c != null && !c.excludedPartnerNames().isEmpty()) args.add(String.join(",", c.excludedPartnerNames()));
    }
    private static String vectorLiteral(List<Float> vector) { return "[" + String.join(",", vector.stream().map(String::valueOf).toList()) + "]"; }
    private static String like(String value) { return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
}
