package com.itplace.userapi.benefit.dto;

import java.util.List;

public record ChannelDecision(String version, String reason, List<String> evidence, List<String> exclusions) {
    public ChannelDecision {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }
}
