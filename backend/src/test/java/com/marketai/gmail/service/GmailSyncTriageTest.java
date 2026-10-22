package com.marketai.gmail.service;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GmailSyncTriageTest {

    @Test
    void socialAndJobMailIsRecognisedWithoutAModel() {
        assertThat(GmailSyncService.isNonFinancialSender("LinkedIn <messages-noreply@linkedin.com>")).isTrue();
        assertThat(GmailSyncService.isNonFinancialSender("Jeevansathi <matchalert@jeevansathi.com>")).isTrue();
        assertThat(GmailSyncService.isNonFinancialSender("NPTEL <onlinecourses@nptel.iitm.ac.in>")).isTrue();
    }

    @Test
    void bankFundAndBrokerMailStillGoesToTheModel() {
        assertThat(GmailSyncService.isNonFinancialSender("SBI Mutual Fund <enq_sbimf@camsonline.com>")).isFalse();
        assertThat(GmailSyncService.isNonFinancialSender("Axis Bank Alerts <alerts@axis.bank.in>")).isFalse();
        assertThat(GmailSyncService.isNonFinancialSender("someone@notlinkedin.com")).isFalse();
        assertThat(GmailSyncService.isNonFinancialSender(null)).isFalse();
    }

    @Test
    void aWrappedGmail404MeansTheMessageWasDeleted() {
        HttpResponseException notFound = new HttpResponseException.Builder(404, "Not Found", new HttpHeaders()).build();
        assertThat(GmailSyncService.isGoneFromGmail(new RuntimeException("fetch failed", notFound))).isTrue();
        HttpResponseException serverError = new HttpResponseException.Builder(500, "Error", new HttpHeaders()).build();
        assertThat(GmailSyncService.isGoneFromGmail(new RuntimeException(serverError))).isFalse();
    }
}
