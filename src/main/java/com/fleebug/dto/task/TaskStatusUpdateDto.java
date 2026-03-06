package com.fleebug.dto.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

/**
 * Body sent to PATCH /v1/tasks/status.
 *
 * Examples:
 *   { "taskId": "...", "status": "PROCESSING" }
 *   { "taskId": "...", "status": "COMPLETED", "result": { ... } }
 *   { "taskId": "...", "status": "FAILED",    "result": { "error": "...", "details": "..." } }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskStatusUpdateDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("taskId") private String taskId;
    @JsonProperty("status") private String status;
    @JsonProperty("result") private Object result;

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    public static TaskStatusUpdateDto fromJson(String json) throws IOException {
        return MAPPER.readValue(json, TaskStatusUpdateDto.class);
    }
}
