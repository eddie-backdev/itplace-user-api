package com.itplace.userapi.ai.rag.service;

import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public float[] embed(String text) {
        Objects.requireNonNull(text, "text");
        EmbeddingResponse res = embeddingModel.embedForResponse(List.of(text));

        if (res == null || res.getResults() == null || res.getResults().isEmpty()) {
            throw new IllegalStateException("Embedding failed or empty");
        }

        float[] vec = res.getResults().get(0).getOutput();

        if (vec == null || vec.length == 0) {
            throw new IllegalStateException("Embedding vector is empty");
        }

        return vec;
    }

}

