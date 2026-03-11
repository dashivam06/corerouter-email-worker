package com.fleebug.dto.task.vllm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VllmChatCompletionRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("model")
    private String model;
    @JsonProperty("prompt")
    private String prompt;
    @JsonProperty("temperature")
    private Double temperature;
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }
}
