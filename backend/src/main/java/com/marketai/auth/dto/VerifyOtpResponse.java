package com.marketai.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** The one-time credential {@code /register} must present as
 *  {@link RegisterRequest#getOtpVerificationToken()} — proof that this email was verified,
 *  without which registration is rejected. */
@Data
@AllArgsConstructor
public class VerifyOtpResponse {
    private String verificationToken;
}
