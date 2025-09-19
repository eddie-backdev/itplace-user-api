package com.itplace.userapi.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
public class ChatCompletionResponse {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    public static class Choice {
        private int index;
        private Message message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
