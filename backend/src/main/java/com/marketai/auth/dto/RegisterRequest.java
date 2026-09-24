package com.marketai.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    // Proof this email address was actually verified via the request-otp/verify-otp pair —
    // see EmailOtpService. Required so registration can't be completed for an email nobody
    // has proven they control.
    @NotBlank(message = "Email verification is required — request and verify a code first")
    private String otpVerificationToken;
}
