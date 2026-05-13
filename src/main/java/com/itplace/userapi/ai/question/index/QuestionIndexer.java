package com.itplace.userapi.ai.question.index;

import com.itplace.userapi.ai.question.service.ElasticQuestionService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
public class QuestionIndexer implements ApplicationRunner {
    private static final String INDEX_NAME = "questions";
    private static final List<String> DEFAULT_CSV_LOCATIONS = List.of(
            "file:../questions.csv",
            "classpath:data/questions.csv"
    );

    private final EmbeddingService embeddingService;
    private final ElasticQuestionService elasticQuestionService;
    private final ResourceLoader resourceLoader;

    // Offline/evaluation seed only. Default false keeps runtime recommendation routing off the questions index.
    @Value("${app.ai.questions.seed.enabled:false}")
    private boolean seedEnabled;

    @Value("${app.ai.questions.seed.csv-location:}")
    private String configuredCsvLocation;

    @Value("${app.ai.questions.seed.delay-ms:100}")
    private long delayMs;

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("질문 ES 초기 색인이 비활성화되어 있습니다.");
            return;
        }

        try {
            elasticQuestionService.createQuestionIndexIfNotExists(INDEX_NAME);
            long existingDocuments = elasticQuestionService.countDocuments(INDEX_NAME);
            if (existingDocuments > 0) {
                log.info("질문 ES 초기 색인을 건너뜁니다. existingDocuments={}", existingDocuments);
                return;
            }

            Resource csv = resolveCsvResource();
            if (csv == null) {
                log.warn("질문 초기 색인 CSV를 찾을 수 없습니다. checkedLocations={}", candidateLocations());
                return;
            }

            int indexed = seedQuestions(csv);
            log.info("질문 ES 초기 색인 완료: indexed={}, source={}", indexed, csv.getDescription());
        } catch (Exception e) {
            log.warn("질문 ES 초기 색인 실패 (ES/OpenAI 설정이 비활성 상태일 수 있음): {}", e.getMessage());
        }
    }

    private Resource resolveCsvResource() {
        for (String location : candidateLocations()) {
            Resource resource = resourceLoader.getResource(location);
            try {
                if (resource.exists() && resource.isReadable()) {
                    return resource;
                }
            } catch (Exception e) {
                log.debug("질문 CSV 후보를 읽을 수 없습니다: location={}", location, e);
            }
        }
        return null;
    }

    private List<String> candidateLocations() {
        if (configuredCsvLocation != null && !configuredCsvLocation.isBlank()) {
            return List.of(configuredCsvLocation);
        }
        return DEFAULT_CSV_LOCATIONS;
    }

    private int seedQuestions(Resource csv) throws IOException, InterruptedException {
        int indexed = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csv.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean skipHeader = true;
            while ((line = reader.readLine()) != null) {
                if (skipHeader) {
                    skipHeader = false;
                    continue;
                }

                List<String> columns = parseCsvLine(line);
                if (columns.size() < 2) {
                    continue;
                }

                String question = columns.get(0).trim();
                String category = columns.get(1).trim();
                if (question.isBlank() || category.isBlank()) {
                    continue;
                }

                String id = deterministicId(question, category);
                if (elasticQuestionService.existsById(INDEX_NAME, id)) {
                    continue;
                }

                List<Float> embedding = embeddingService.embed(question);
                elasticQuestionService.saveQuestion(INDEX_NAME, id, question, category, embedding);
                indexed++;

                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            }
        }
        return indexed;
    }

    static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private String deterministicId(String question, String category) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((question + "|" + category).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
