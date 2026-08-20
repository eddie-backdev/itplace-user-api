package com.itplace.userapi.recommend.service;

public interface RecommendationGenerationLock {

    Lease acquire(Long userId);

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
