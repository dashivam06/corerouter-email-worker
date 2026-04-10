package com.fleebug.dto.email;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmailJobDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("schemaVersion")   private String schemaVersion;
    @JsonProperty("channel")         private String channel;
    @JsonProperty("category")        private String category;
    @JsonProperty("templateKey")     private String templateKey;

    @JsonProperty("type")            private String type;
    @JsonProperty("purpose")         private String purpose;

    @JsonProperty("email")           private String email;
    @JsonProperty("fullName")        private String fullName;
    @JsonProperty("otp")             private String otp;
    @JsonProperty("otpTtlMinutes")   private Long otpTtlMinutes;

    @JsonProperty("userId")          private Long userId;
    @JsonProperty("apiKeyId")        private Long apiKeyId;
    @JsonProperty("apiKeyName")      private String apiKeyName;
    @JsonProperty("monthlyLimit")    private Long monthlyLimit;
    @JsonProperty("consumed")        private Long consumed;
    @JsonProperty("thresholdPercent") private Integer thresholdPercent;

    @JsonProperty("subject")         private String subject;
    @JsonProperty("message")         private String message;
    @JsonProperty("timestamp")       private Long timestamp;
    @JsonProperty("eventTime")       private String eventTime;
    @JsonProperty("retryCount")      private Integer retryCount;

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    public static EmailJobDto fromJson(String json) throws IOException {
        return MAPPER.readValue(json, EmailJobDto.class);
    }
}