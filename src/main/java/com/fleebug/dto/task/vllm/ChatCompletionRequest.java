package com.fleebug.dto.task.vllm;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified request DTO that captures the intent of a chat completion task.
 * This class handles the parsing of the raw payload from Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {

    @JsonProperty("messages")
    private List<Map<String, String>> messages = new ArrayList<>();

    @JsonProperty("model")
    private String model;

    // Not sent directly to vLLM in standard mode (processed into messages), unless vLLM supports it.
    // If set to null manually before serialization, it will be skipped.
    @JsonProperty("system_prompt")
    private String systemPrompt;

    @JsonProperty("temperature")
    private Float temperature;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("top_p")
    private Float topP;

    @JsonProperty("top_k")
    private Integer topK;

    @JsonProperty("frequency_penalty")
    private Float frequencyPenalty;

    @JsonProperty("presence_penalty")
    private Float presencePenalty;

    @JsonProperty("stop")
    private List<String> stop;

    // Captures any other fields in the JSON payload automatically
    private Map<String, Object> additionalParameters = new HashMap<>();

    @JsonAnySetter
    public void addAdditionalParameter(String key, Object value) {
        this.additionalParameters.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalParameters() {
        return additionalParameters;
    }
}
