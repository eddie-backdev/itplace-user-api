package com.itplace.userapi.ai.rag.service;

import java.util.List;

public interface EmbeddingService {
    List<Float> embed(String text);
}
