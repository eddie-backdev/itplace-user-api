package com.itplace.userapi.ai.rag.service;

import java.util.List;

public interface EmbeddingService {
    List<Float> embed(String text);

    default List<Float> embedQuery(String text) {
        return embed(text);
    }

    default List<List<Float>> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
