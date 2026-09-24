package com.marketai.auth.repository;

import com.marketai.auth.entity.EmailOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    /** The active (not yet consumed) code for an email — at most one at a time, since
     *  requesting a new code consumes any prior unconsumed row for that address. */
    Optional<EmailOtp> findTopByEmailAndConsumedFalseOrderByCreatedAtDesc(String email);

    /** The row a successful verify-otp call produced, looked up by the one-time token the
     *  actual /register call must present. */
    Optional<EmailOtp> findByVerificationTokenAndConsumedTrue(String verificationToken);
}
