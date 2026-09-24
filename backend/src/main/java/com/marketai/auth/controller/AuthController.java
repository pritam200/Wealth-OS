package com.marketai.auth.controller;

import com.marketai.auth.dto.AuthResponse;
import com.marketai.auth.dto.LoginRequest;
import com.marketai.auth.dto.RefreshTokenRequest;
import com.marketai.auth.dto.RegisterRequest;
import com.marketai.auth.dto.RequestOtpRequest;
import com.marketai.auth.dto.VerifyOtpRequest;
import com.marketai.auth.dto.VerifyOtpResponse;
import com.marketai.auth.service.AuthService;
import com.marketai.auth.service.EmailOtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login, and token management")
public class AuthController {

    private final AuthService authService;
    private final EmailOtpService emailOtpService;

    @PostMapping("/register/otp/request")
    @Operation(summary = "Send a 6-digit verification code to an email address before registering")
    public ResponseEntity<Void> requestRegistrationOtp(@Valid @RequestBody RequestOtpRequest request) {
        emailOtpService.requestOtp(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register/otp/verify")
    @Operation(summary = "Verify a registration code and receive the token /register requires")
    public ResponseEntity<VerifyOtpResponse> verifyRegistrationOtp(@Valid @RequestBody VerifyOtpRequest request) {
        String token = emailOtpService.verifyOtp(request.getEmail(), request.getCode());
        return ResponseEntity.ok(new VerifyOtpResponse(token));
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user (requires a verified-email token from /register/otp/verify)")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email and password")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout and revoke refresh tokens")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserDetails userDetails) {
        authService.logout(userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
