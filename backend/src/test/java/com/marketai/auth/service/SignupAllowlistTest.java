package com.marketai.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignupAllowlistTest {

    @Test
    void invitedFamilyCanSignUpCaseInsensitively() {
        SignupAllowlist list = new SignupAllowlist(" me@example.com, Mom@Example.com ,dad@example.com", false);
        assertThat(list.permits("mom@example.com")).isTrue();
        assertThat(list.permits("ME@EXAMPLE.COM ")).isTrue();
        assertThat(list.permits("dad@example.com")).isTrue();
    }

    @Test
    void anyoneElseIsRefusedWithAClearMessage() {
        SignupAllowlist list = new SignupAllowlist("me@example.com", false);
        assertThat(list.permits("stranger@example.com")).isFalse();
        assertThatThrownBy(() -> list.check("stranger@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invitation only");
    }

    @Test
    void anEmptyListFailsClosedInProduction() {
        assertThat(new SignupAllowlist("", false).permits("me@example.com")).isFalse();
        assertThat(new SignupAllowlist(null, false).permits("me@example.com")).isFalse();
    }

    @Test
    void anEmptyListStaysOpenForLocalDev() {
        assertThat(new SignupAllowlist("", true).permits("anyone@example.com")).isTrue();
    }

    @Test
    void aListIsEnforcedEvenWhereEmptyWouldBeOpen() {
        assertThat(new SignupAllowlist("me@example.com", true).permits("stranger@example.com")).isFalse();
    }
}
