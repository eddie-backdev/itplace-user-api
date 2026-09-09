package com.itplace.userapi.log.repository;

import com.itplace.userapi.log.dto.RankResult;
import com.itplace.userapi.log.entity.LogDocument;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.FacetOperation;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.LimitOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CustomLogRepositoryImpl implements CustomLogRepository {

    private final MongoTemplate mongoTemplate;


    @Override
    public List<RankResult> findTopSearchRank(Instant from, Instant to) {
        MatchOperation matchOperation = Aggregation.match(
                Criteria.where("event").is("search")
                        .and("loggingAt").gte(from).lt(to)
        );
        GroupOperation groupOperation = Aggregation.group("partnerId")
                .count().as("count");
        SortOperation sortOperation = Aggregation.sort(Direction.DESC, "count");
        LimitOperation limitOperation = Aggregation.limit(5);

        ProjectionOperation projectionOperation = Aggregation.project()
                .and("_id").as("partnerId")
                .and("count").as("count");

        Aggregation aggregation = Aggregation.newAggregation(
                matchOperation, groupOperation, sortOperation, limitOperation, projectionOperation);

        return mongoTemplate.aggregate(aggregation, "logs", RankResult.class)
                .getMappedResults();
    }

    @Override
    public Map<String, List<String>> aggregateTopPartnerNamesByEvents(Long userId, Map<String, Integer> limits) {
        if (limits.isEmpty()) {
            return Map.of();
        }
        FacetOperation facets = Aggregation.facet();
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            facets = facets.and(
                    Aggregation.match(Criteria.where("event").is(entry.getKey())),
                    Aggregation.group("partnerName").count().as("count"),
                    Aggregation.sort(Sort.by(Sort.Order.desc("count"), Sort.Order.asc("_id"))),
                    Aggregation.limit(entry.getValue())
            ).as(entry.getKey());
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("event").in(limits.keySet())), facets);
        Document result = mongoTemplate.aggregate(aggregation, "logs", Document.class).getUniqueMappedResult();
        if (result == null) {
            return Map.of();
        }
        Map<String, List<String>> partners = new LinkedHashMap<>();
        limits.keySet().forEach(event -> partners.put(event, result.getList(event, Document.class, List.of()).stream()
                .map(row -> row.getString("_id")).filter(Objects::nonNull).toList()));
        return partners;
    }

    @Override
    public Optional<Instant> findLatestLoggingAtByEvents(Long userId, List<String> events) {
        if (events == null || events.isEmpty()) {
            return Optional.empty();
        }

        Query query = new Query()
                .addCriteria(Criteria.where("userId").is(userId).and("event").in(events))
                .with(Sort.by(Sort.Direction.DESC, "loggingAt"))
                .limit(1);

        LogDocument latest = mongoTemplate.findOne(query, LogDocument.class, "logs");
        return latest == null || latest.getLoggingAt() == null
                ? Optional.empty()
                : Optional.of(latest.getLoggingAt());
    }

    @Override
    public Optional<String> findLatestParamByEvents(Long userId, List<String> events) {
        if (events == null || events.isEmpty()) {
            return Optional.empty();
        }

        Query query = new Query()
                .addCriteria(Criteria.where("userId").is(userId)
                        .and("event").in(events)
                        .and("param").ne(null))
                .with(Sort.by(Sort.Direction.DESC, "loggingAt"))
                .limit(1);

        LogDocument latest = mongoTemplate.findOne(query, LogDocument.class, "logs");
        return latest == null || latest.getParam() == null || latest.getParam().isBlank()
                ? Optional.empty()
                : Optional.of(latest.getParam());
    }

}


