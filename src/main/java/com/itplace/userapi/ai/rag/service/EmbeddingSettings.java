package com.itplace.userapi.ai.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Locale;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EmbeddingSettings {
    public static final String KURE_MODEL = "nlpai-lab/KURE-v1@8b418a58414668e75532ed045c22d9ca018ae2b2";
    public static final int KURE_DIMENSIONS = 1024;
    private final String provider;
    private final String model;
    private final int dimensions;
    private final String baseUrl;
    private final Set<String> trustedHosts;

    public EmbeddingSettings(String provider, String model, int dimensions, String baseUrl) {
        this(provider, model, dimensions, baseUrl, "");
    }

    @Autowired
    public EmbeddingSettings(
            @Value("${app.ai.embedding.provider:${EMBEDDING_PROVIDER:openai}}") String provider,
            @Value("${app.ai.embedding.model:${EMBEDDING_MODEL:${spring.ai.openai.embedding.model:text-embedding-3-small}}}") String model,
            @Value("${app.ai.embedding.dimensions:${EMBEDDING_DIMENSIONS:1536}}") int dimensions,
            @Value("${app.ai.embedding.base-url:${EMBEDDING_BASE_URL:http://127.0.0.1:11434}}") String baseUrl,
            @Value("${app.ai.embedding.trusted-hosts:${EMBEDDING_TRUSTED_HOSTS:}}") String trustedHosts) {
        this.provider = provider.trim().toLowerCase();
        if (!this.provider.equals("openai") && !this.provider.equals("ollama") && !this.provider.equals("local")) {
            throw new IllegalStateException("Unsupported embedding provider: " + provider);
        }
        if (model == null || model.isBlank()) throw new IllegalStateException("Embedding model must not be blank");
        if (dimensions < 1) throw new IllegalStateException("Embedding dimensions must be positive");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("Embedding base URL must not be blank");
        this.model = model.trim();
        this.dimensions = dimensions;
        this.baseUrl = baseUrl.trim();
        this.trustedHosts = parseTrustedHosts(trustedHosts);
        if (this.provider.equals("local")) {
            if (!KURE_MODEL.equals(this.model)) throw new IllegalStateException("Local embedding requires the pinned KURE model revision");
            if (dimensions != KURE_DIMENSIONS) throw new IllegalStateException("Local KURE embedding requires 1024 dimensions");
            requireTrustedHttp(this.baseUrl, this.trustedHosts);
        }
    }

    public String provider() { return provider; }
    public String model() { return model; }
    public String modelIdentity() { return provider.equals("ollama") || provider.equals("local") ? provider + ":" + model : model; }
    public int dimensions() { return dimensions; }
    public String baseUrl() { return baseUrl; }
    public boolean isTrustedHost(String host) {
        return host != null && trustedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseTrustedHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim).filter(host -> !host.isEmpty())
                    .map(host -> {
                        if (host.contains("*") || host.contains(":") || host.contains("/") || host.contains("@"))
                            throw new IllegalStateException("Trusted embedding hosts must be exact hostnames");
                        URI uri = URI.create("http://" + host);
                        if (uri.getHost() == null || uri.getPort() != -1 || uri.getRawAuthority().contains("%")
                                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                                || !(uri.getRawPath().isEmpty()) || !host.equalsIgnoreCase(uri.getHost()))
                            throw new IllegalStateException("Trusted embedding hosts must be exact hostnames");
                        return uri.getHost().toLowerCase(Locale.ROOT);
                    }).collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Trusted embedding hosts must be exact hostnames");
        }
    }

    private static void requireTrustedHttp(String value, Set<String> trustedHosts) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host != null) host = host.replace("[", "").replace("]", "");
            boolean loopback = host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
            boolean trusted = host != null && trustedHosts.contains(host.toLowerCase(Locale.ROOT));
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null || (!loopback && !trusted)
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                    || uri.getRawAuthority().endsWith(":")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalStateException("Local embedding requires an HTTP loopback or explicitly trusted host base URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Local embedding requires a valid HTTP loopback or trusted host base URL");
        }
    }
}
