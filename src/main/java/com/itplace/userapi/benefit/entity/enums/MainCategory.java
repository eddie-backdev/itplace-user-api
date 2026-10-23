package com.itplace.userapi.benefit.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/** Immutable reference code; persisted and exposed as a stable code. */
public final class MainCategory {
    public static final MainCategory VIP_COCK = of("VIP_COCK");
    public static final MainCategory BASIC_BENEFIT = of("BASIC_BENEFIT");

    private final String code;
    private MainCategory(String code) { this.code = code; }

    @JsonCreator
    public static MainCategory of(String code) {
        if (code == null) return null;
        String normalized = code.trim();
        if (normalized.isEmpty() || normalized.length() > 50) throw new IllegalArgumentException("Invalid membership program code");
        return new MainCategory(normalized);
    }

    @JsonValue public String getCode() { return code; }
    public String name() { return code; }
    /** Compatibility for callers that still receive pre-migration labels. */
    public String getLabel() { return switch (code) { case "VIP_COCK" -> "VIP 콕"; case "BASIC_BENEFIT" -> "기본 혜택"; default -> code; }; }
    public static MainCategory fromLabel(String value) {
        if (value == null) return null;
        return switch (value) { case "VIP 콕" -> VIP_COCK; case "기본 혜택" -> BASIC_BENEFIT; default -> of(value); };
    }
    @Override public boolean equals(Object other) { return other instanceof MainCategory c && code.equals(c.code); }
    @Override public int hashCode() { return Objects.hash(code); }
    @Override public String toString() { return code; }
}
