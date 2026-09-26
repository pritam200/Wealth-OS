package com.marketai.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may create an account. A hosted copy of this app holds PAN, date of birth, Gmail access
 * and a household's full finances, so sign-up is by invitation: only emails listed in
 * {@code app.signup.allowed-emails} (SIGNUP_ALLOWED_EMAILS, comma-separated) can register.
 *
 * <p>An empty list means open sign-up only where {@code app.signup.open-when-empty} is true —
 * the local dev default. The prod profile sets it false, so a deployment that forgets the list
 * fails closed (nobody can register) rather than open to the internet.
 */
@Component
public class SignupAllowlist {

    static final String NOT_INVITED =
        "Sign-up is by invitation only. Ask the account owner to add your email address.";

    private final Set<String> allowed;
    private final boolean openWhenEmpty;

    public SignupAllowlist(@Value("${app.signup.allowed-emails:}") String allowedEmails,
                           @Value("${app.signup.open-when-empty:true}") boolean openWhenEmpty) {
        this.allowed = Arrays.stream(allowedEmails == null ? new String[0] : allowedEmails.split(","))
            .map(e -> e.trim().toLowerCase(Locale.ROOT))
            .filter(e -> !e.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        this.openWhenEmpty = openWhenEmpty;
    }

    public boolean permits(String email) {
        if (allowed.isEmpty()) return openWhenEmpty;
        return email != null && allowed.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    /** @throws IllegalArgumentException (surfaced to the client as a 400 with the message). */
    public void check(String email) {
        if (!permits(email)) throw new IllegalArgumentException(NOT_INVITED);
    }
}
