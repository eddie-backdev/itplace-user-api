package com.itplace.userapi.ai.question.controller;

import com.itplace.userapi.ai.question.dto.request.QuestionSaveRequest;
import com.itplace.userapi.ai.question.service.ElasticQuestionService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final EmbeddingService embeddingService;
    private final ElasticQuestionService elasticQuestionService; // saveQuestion() 정의된 서비스

    @PostMapping("/save")
    public ResponseEntity<String> saveQuestion(@RequestBody QuestionSaveRequest request) {
        try {
            List<Float> embedding = embeddingService.embed(request.getQuestion());
            String id = UUID.randomUUID().toString();
            String indexName = "questions";

            elasticQuestionService.saveQuestion(
                    indexName,
                    id,
                    request.getQuestion(),
                    request.getCategory(),
                    embedding
            );

            return ResponseEntity.ok("질문 문서가 성공적으로 저장되었습니다. (id: " + id + ")");
        } catch (Exception e) {
            log.error("질문 문서 저장 실패: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("저장 중 오류가 발생했습니다.");
        }
    }
}

