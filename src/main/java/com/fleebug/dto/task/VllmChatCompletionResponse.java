package com.fleebug.dto.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Response returned by POST {endpointUrl}/v1/chat/completions.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VllmChatCompletionResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("id")      private String id;
    @JsonProperty("object")  private String object;
    @JsonProperty("model")   private String model;
    @JsonProperty("choices") private List<Map<String, Object>> choices;
    @JsonProperty("usage")   private Map<String, Object> usage;

    /** Get prompt_tokens from usage, or 0 if missing. */
    public int getPromptTokens() {
        return getUsageInt("prompt_tokens");
    }

    /** Get completion_tokens from usage, or 0 if missing. */
    public int getCompletionTokens() {
        return getUsageInt("completion_tokens");
    }

    /** Get total_tokens from usage, or 0 if missing. */
    public int getTotalTokens() {
        return getUsageInt("total_tokens");
    }

    private int getUsageInt(String key) {
        if (usage == null || !usage.containsKey(key)) return 0;
        Object val = usage.get(key);
        return val instanceof Number ? ((Number) val).intValue() : 0;
    }

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    public static VllmChatCompletionResponse fromJson(String json) throws IOException {
        return MAPPER.readValue(json, VllmChatCompletionResponse.class);
    }
}
