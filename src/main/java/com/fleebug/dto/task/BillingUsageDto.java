package com.fleebug.dto.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

/**
 * Body sent to POST /api/v1/admin/billing/usage.
 *
 * Example:
 *   { "taskId": "abc-123", "usageUnitType": "INPUT_TOKENS", "quantity": 45 }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingUsageDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("taskId")        private String taskId;
    @JsonProperty("usageUnitType") private String usageUnitType;
    @JsonProperty("quantity")      private int quantity;

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }
}
