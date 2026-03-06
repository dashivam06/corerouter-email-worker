package com.fleebug.dto.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Request body sent to POST {endpointUrl}/v1/chat/completions.
 * Null fields are omitted so vLLM uses its own defaults.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VllmChatCompletionRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("model")      private String model;
    @JsonProperty("messages")   private List<Map<String, Object>> messages;
    @JsonProperty("temperature") private Double temperature;
    @JsonProperty("max_tokens") private Integer maxTokens;

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }
}
