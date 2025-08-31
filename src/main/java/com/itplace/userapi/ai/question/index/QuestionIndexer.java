//package com.itplace.userapi.ai.question.index;
//
//import com.itplace.userapi.ai.question.service.ElasticQuestionService;
//import com.itplace.userapi.ai.rag.service.EmbeddingService;
//import java.io.BufferedReader;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.List;
//import java.util.UUID;
//import lombok.RequiredArgsConstructor;
//import org.springframework.boot.ApplicationArguments;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.stereotype.Component;
//
//
//@RequiredArgsConstructor
//@Component
//public class QuestionIndexer implements ApplicationRunner {
//
//    private final EmbeddingService embeddingService;
//    private final ElasticQuestionService elasticQuestionService;
//
//    @Override
//    public void run(ApplicationArguments args) throws Exception {
//        final String indexName = "questions";
//
//        // 인덱스 자동 생성
//        elasticQuestionService.createQuestionIndexIfNotExists(indexName);
//
//        Path path = Path.of("src/main/resources/data/questions.csv");
//
//        try (BufferedReader reader = Files.newBufferedReader(path)) {
//            String line;
//            boolean skipHeader = true;
//
//            while ((line = reader.readLine()) != null) {
//                if (skipHeader) {
//                    skipHeader = false;
//                    continue;
//                }
//
//                String[] tokens = line.split(",", 2);
//                if (tokens.length < 2) {
//                    continue;
//                }
//
//                String question = tokens[0].trim().replaceAll("^\"|\"$", "");
//                String category = tokens[1].trim().replaceAll("^\"|\"$", "");
//
//                List<Float> embedding = embeddingService.embed(question);
//
//                elasticQuestionService.saveQuestion(
//                        indexName,
//                        UUID.randomUUID().toString(),
//                        question,
//                        category,
//                        embedding
//                );
//
//                Thread.sleep(100); // API rate limit
//            }
//        }
//    }
//}
//
//
