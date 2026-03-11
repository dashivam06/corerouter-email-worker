package com.fleebug.dto.task.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Map;

/**
 * Model configuration returned by GET /api/v1/admin/models/{modelId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("modelId")     private String modelId;
    @JsonProperty("fullname")    private String fullname;
    @JsonProperty("username")    private String username;
    @JsonProperty("endpointUrl") private String endpointUrl;
    @JsonProperty("provider")    private String provider;
    @JsonProperty("type")        private String type;
    @JsonProperty("status")      private String status;
    @JsonProperty("metadata")    private Map<String, Object> metadata;

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    public static ModelDto fromJson(String json) throws IOException {
        return MAPPER.readValue(json, ModelDto.class);
    }
}
