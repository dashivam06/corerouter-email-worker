package com.fleebug.dto.task.vllm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Map;

/**
 * Represents a task read from the Redis stream {@code stream:tasks}.
 *
 * Stream fields are flat strings:
 *   taskId, modelId, apiKeyId, payload (JSON string), timestamp
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VllmTaskDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("taskId")   private String taskId;
    @JsonProperty("modelId")  private String modelId;
    @JsonProperty("apiKeyId") private String apiKeyId;
    @JsonProperty("payload")  private ChatCompletionRequest payload;
    @JsonProperty("timestamp") private String timestamp;

    /**
     * Build a VllmTaskDto directly from the flat stream fields.
     * The "payload" field arrives as a JSON string and must be parsed into a Map.
     */
    @SuppressWarnings("unchecked")
    public static VllmTaskDto fromStreamFields(Map<String, String> fields) throws IOException {
        VllmTaskDto dto = new VllmTaskDto();
        dto.taskId = fields.get("taskId");
        dto.modelId = fields.get("modelId");
        dto.apiKeyId = fields.get("apiKeyId");
        dto.timestamp = fields.get("timestamp");

        String payloadJson = fields.get("payload");
        if (payloadJson != null && !payloadJson.isBlank()) {
            dto.payload = MAPPER.readValue(payloadJson, ChatCompletionRequest.class);
        }
        return dto;
    }

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    public static VllmTaskDto fromJson(String json) throws IOException {
        return MAPPER.readValue(json, VllmTaskDto.class);
    }
}
