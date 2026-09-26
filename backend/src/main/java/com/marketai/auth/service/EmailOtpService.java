package com.marketai.auth.service;

import com.marketai.auth.entity.EmailOtp;
import com.marketai.auth.repository.EmailOtpRepository;
import com.marketai.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Registration email verification: a 6-digit code, sent by SMTP, that the caller must prove
 * they received before {@code AuthService.register} will create the account.
 *
 * The code is hashed with the same {@link PasswordEncoder} bean used for account passwords —
 * never persisted or logged in plaintext, same reasoning as a password. Verifying it consumes
 * the row and mints a short-lived {@code verificationToken}; that token, not the code itself,
 * is what {@code /register} checks, so the code can't be replayed and a stale request can't
 * complete registration after the caller has moved on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailOtpService {

    private final EmailOtpRepository emailOtpRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // ObjectProvider, not a direct JavaMailSender, because the bean only exists when
    // spring.mail.host is configured (see MAIL_HOST in application.yml) — an unconfigured
    // relay must give a clear "mail isn't set up" error at send time, not stop the whole
    // app from starting.
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SignupAllowlist signupAllowlist;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.otp.from:MarketAI <no-reply@marketai.local>}")
    private String fromAddress;

    @Value("${app.otp.expiry-minutes:10}")
    private long expiryMinutes;

    @Value("${app.otp.resend-cooldown-seconds:45}")
    private long resendCooldownSeconds;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.otp.verification-token-ttl-minutes:20}")
    private long verificationTokenTtlMinutes;

    @Transactional
    public void requestOtp(String rawEmail) {
        String email = normalize(rawEmail);
        // Checked before anything is stored or sent, so an uninvited address can't use this
        // endpoint to make the server email arbitrary people.
        signupAllowlist.check(email);

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists. Try signing in instead.");
        }

        emailOtpRepository.findTopByEmailAndConsumedFalseOrderByCreatedAtDesc(email).ifPresent(previous -> {
            Duration since = Duration.between(previous.getCreatedAt(), Instant.now());
            if (since.getSeconds() < resendCooldownSeconds) {
                throw new IllegalArgumentException(
                        "Please wait a few seconds before requesting another code.");
            }
            // Invalidate the old code so only the most recently sent one can ever verify.
            previous.setConsumed(true);
            emailOtpRepository.save(previous);
        });

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        EmailOtp otp = EmailOtp.builder()
                .email(email)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(Instant.now().plus(Duration.ofMinutes(expiryMinutes)))
                .build();
        emailOtpRepository.save(otp);

        sendCodeEmail(email, code);
        log.info("Sent registration OTP to {}", email);
    }

    @Transactional
    public String verifyOtp(String rawEmail, String code) {
        String email = normalize(rawEmail);

        EmailOtp otp = emailOtpRepository.findTopByEmailAndConsumedFalseOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No verification code was requested for this email — request one first."));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This code has expired. Request a new one.");
        }
        if (otp.getAttempts() >= maxAttempts) {
            throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
        }

        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            emailOtpRepository.save(otp);
            throw new IllegalArgumentException("Incorrect code.");
        }

        otp.setConsumed(true);
        otp.setVerificationToken(UUID.randomUUID().toString());
        otp.setVerificationTokenExpiresAt(Instant.now().plus(Duration.ofMinutes(verificationTokenTtlMinutes)));
        emailOtpRepository.save(otp);

        return otp.getVerificationToken();
    }

    /** Called from {@code AuthService.register} — throws if the token is missing, unknown,
     *  expired, or was minted for a different email than the one being registered. */
    public void assertEmailVerified(String rawEmail, String verificationToken) {
        String email = normalize(rawEmail);
        EmailOtp otp = emailOtpRepository.findByVerificationTokenAndConsumedTrue(verificationToken)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Email verification is missing or invalid. Please verify your email again."));

        if (!otp.getEmail().equals(email)) {
            throw new IllegalArgumentException("Verification token does not match this email address.");
        }
        if (otp.getVerificationTokenExpiresAt() == null || otp.getVerificationTokenExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Email verification has expired. Please verify your email again.");
        }
    }

    private void sendCodeEmail(String email, String code) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.error("Cannot send OTP to {} — spring.mail.host is not configured (set MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD).", email);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email sending isn't configured on this server yet. Ask an administrator to set MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD.");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Your MarketAI verification code");
        message.setText(
                "Your verification code is " + code + ".\n\n" +
                "It expires in " + expiryMinutes + " minutes. If you didn't request this, you can ignore this email.");
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // Never surfaces the SMTP host/credentials in the response — just tells the
            // caller mail delivery itself is the problem, not their input.
            log.error("Failed to send OTP email to {}: {}", email, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not send the verification email right now. Please try again shortly.");
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
