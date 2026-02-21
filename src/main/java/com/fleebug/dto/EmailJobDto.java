package com.fleebug.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

public class EmailJobDto {

    @JsonProperty("email")
    private String email;

    @JsonProperty("otp")
    private String otp;

    @JsonProperty("type")
    private String type;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("otpTtlMinutes")
    private long otpTtlMinutes; 

    public EmailJobDto() {}

    public EmailJobDto(String email, String otp, String type, long timestamp, long otpTtlMinutes) {
        this.email = email;
        this.otp = otp;
        this.type = type;
        this.timestamp = timestamp;
        this.otpTtlMinutes = otpTtlMinutes;
    }


    public String getEmail() { return email; }
    public String getOtp() { return otp; }
    public String getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public long getOtpTtlMinutes() { return otpTtlMinutes; }


    // Convert the object to JSON
    public String toJson() throws IOException {
        return new ObjectMapper().writeValueAsString(this);
    }

    // Create object from JSON String
    public static EmailJobDto fromJson(String json) throws IOException {
        return new ObjectMapper().readValue(json, EmailJobDto.class);
    }
}