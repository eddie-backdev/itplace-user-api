package com.itplace.userapi.benefit.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.io.Serializable;

/** Immutable membership grade code. Known constants are conveniences, not the catalog. */
public final class Grade implements Serializable {
    public static final Grade BASIC = of("BASIC"), VIP = of("VIP"), VVIP = of("VVIP"), DIAMOND = of("DIAMOND"), VIP콕 = of("VIP콕");
    public static final Grade SKT_LITE = of("SKT_LITE"), SKT_SILVER = of("SKT_SILVER"), SKT_GOLD = of("SKT_GOLD"), SKT_VIP = of("SKT_VIP");
    public static final Grade KT_GENERAL = of("KT_GENERAL"), KT_WHITE = of("KT_WHITE"), KT_SILVER = of("KT_SILVER"), KT_GOLD = of("KT_GOLD"), KT_VIP = of("KT_VIP"), KT_VVIP = of("KT_VVIP");

    private final String code;

    private Grade(String code) { this.code = code; }

    @JsonCreator
    public static Grade of(String code) {
        if (code == null) return null;
        String normalized = code.trim();
        if (normalized.isEmpty() || normalized.length() > 30) throw new IllegalArgumentException("Invalid grade code");
        return new Grade(normalized);
    }

    @JsonValue
    public String getCode() { return code; }

    /** Kept for source compatibility with former enum call sites. */
    public String name() { return code; }

    @Override public boolean equals(Object other) { return other instanceof Grade grade && code.equals(grade.code); }
    @Override public int hashCode() { return Objects.hash(code); }
    @Override public String toString() { return code; }
}
