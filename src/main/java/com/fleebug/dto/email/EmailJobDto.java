package com.fleebug.dto.email;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailJobDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("email")         private String email;
    @JsonProperty("otp")           private String otp;
    @JsonProperty("type")          private String type;
    @JsonProperty("timestamp")     private long timestamp;
    @JsonProperty("otpTtlMinutes") private long otpTtlMinutes;

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    public static EmailJobDto fromJson(String json) throws IOException {
        return MAPPER.readValue(json, EmailJobDto.class);
    }
}