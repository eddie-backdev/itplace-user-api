package com.itplace.userapi.log.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

class CustomLogRepositoryImplTest {
    @Test
    void retrievesEventRankingsWithOneBoundedFacetAggregation() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        Document result = new Document("click", List.of(new Document("_id", "카페")))
                .append("dismiss", List.of(new Document("_id", "영화관")));
        when(mongo.aggregate(any(Aggregation.class), eq("logs"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(result), new Document()));
        var rankings = new CustomLogRepositoryImpl(mongo).aggregateTopPartnerNamesByEvents(7L, Map.of("click", 5, "dismiss", 10));
        assertThat(rankings).containsEntry("click", List.of("카페")).containsEntry("dismiss", List.of("영화관"));
        var pipeline = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(pipeline.capture(), eq("logs"), eq(Document.class));
        var stages = pipeline.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).get("$match", Document.class).getLong("userId")).isEqualTo(7L);
        Document facet = stages.get(1).get("$facet", Document.class);
        assertThat(facet.getList("click", Document.class).get(3).get("$limit")).isEqualTo(5L);
        assertThat(facet.getList("dismiss", Document.class).get(3).get("$limit")).isEqualTo(10L);
    }
}
