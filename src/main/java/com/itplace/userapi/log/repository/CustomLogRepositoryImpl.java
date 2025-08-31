package com.itplace.userapi.log.repository;

import com.itplace.userapi.log.dto.PartnerNameResult;
import com.itplace.userapi.log.dto.RankResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.LimitOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.mongodb.core.query.Criteria;
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
    public List<String> aggregateTopPartnerNamesByEvent(Long userId, String event, int topK) {
        MatchOperation match = Aggregation.match(
                Criteria.where("userId").is(userId).and("event").is(event)
        );

        GroupOperation group = Aggregation.group("partnerName").count().as("count");

        SortOperation sort = Aggregation.sort(Sort.by(Sort.Direction.DESC, "count"));
        LimitOperation limit = Aggregation.limit(topK);

        ProjectionOperation project = Aggregation.project()
                .and("_id").as("partnerName")
                .and("count").as("count");

        Aggregation aggregation = Aggregation.newAggregation(
                match, group, sort, limit, project
        );

        return mongoTemplate.aggregate(aggregation, "logs", PartnerNameResult.class)
                .getMappedResults().stream()
                .map(PartnerNameResult::getPartnerName)
                .filter(Objects::nonNull)
                .toList();
    }

}


