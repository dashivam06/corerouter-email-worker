package com.fleebug.dto.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Map;

/**
 * Billing configuration returned by GET /api/v1/admin/billing/configs/model/{modelId}.
 *
 * The exact shape depends on the Spring API; we store whatever it returns
 * and use it to decide whether billing is enabled.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillingConfigDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Map<String, Object> data;

    public boolean isPresent() {
        return data != null && !data.isEmpty();
    }

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(data);
    }

    @SuppressWarnings("unchecked")
    public static BillingConfigDto fromJson(String json) throws IOException {
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        return new BillingConfigDto(map);
    }
}
