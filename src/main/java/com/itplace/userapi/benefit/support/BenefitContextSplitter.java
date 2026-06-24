package com.itplace.userapi.benefit.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class BenefitContextSplitter {

    private static final Pattern CHANNEL_CONTEXT_PATTERN = Pattern.compile("^(온라인|오프라인)\\s*:\\s*(.+)$");

    private BenefitContextSplitter() {
    }

    public static SplitContext split(String context) {
        if (!StringUtils.hasText(context)) {
            return new SplitContext(null, null);
        }

        String onlineContext = null;
        String offlineContext = null;
        for (String segment : context.split("\\s*/\\s*")) {
            Matcher matcher = CHANNEL_CONTEXT_PATTERN.matcher(normalizeWhitespace(segment));
            if (!matcher.matches()) {
                continue;
            }

            String channel = matcher.group(1);
            String value = normalizeWhitespace(matcher.group(2));
            if (!StringUtils.hasText(value)) {
                continue;
            }

            if ("온라인".equals(channel)) {
                onlineContext = appendContext(onlineContext, value);
            } else {
                offlineContext = appendContext(offlineContext, value);
            }
        }

        return new SplitContext(onlineContext, offlineContext);
    }

    private static String appendContext(String current, String value) {
        if (!StringUtils.hasText(current)) {
            return value;
        }
        if (current.contains(value)) {
            return current;
        }
        return current + " / " + value;
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public record SplitContext(String onlineContext, String offlineContext) {
    }
}
