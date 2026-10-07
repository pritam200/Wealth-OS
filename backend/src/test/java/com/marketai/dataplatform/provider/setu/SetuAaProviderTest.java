package com.marketai.dataplatform.provider.setu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.ConsentStatus;
import com.marketai.dataplatform.domain.ProviderMode;
import com.marketai.dataplatform.domain.RecordKind;
import com.marketai.dataplatform.provider.ConsentCapableProvider;
import com.marketai.dataplatform.provider.FinancialDataProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the adapter against a local stand-in for Setu, so the whole HTTP flow is exercised without credentials. */
class SetuAaProviderTest {

    static final ObjectMapper M = new ObjectMapper();

    HttpServer server;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile String consentStatus = "PENDING";
    volatile String sessionStatus = "COMPLETED";
    volatile int tokenIssued = 0;
    volatile boolean rejectFirstToken401 = false;
    SetuAaProvider provider;

    static final String SESSION = """
        {"id":"11111111-aaaa-bbbb-cccc-222222222222","status":"COMPLETED","fips":[
          {"fipID":"setu-fip","accounts":[
            {"maskedAccNumber":"XXXX6053","linkRefNumber":"link-1","data":{"account":{"type":"deposit",
              "summary":{"currentBalance":"1500.00"},
              "transactions":{"transaction":[
                {"txnId":"T1","type":"CREDIT","amount":"1000.00","valueDate":"2024-03-02","transactionTimestamp":"2024-03-02T09:00:00.000Z","narration":"Salary"},
                {"txnId":"T2","type":"DEBIT","amount":"200.50","valueDate":"2024-03-03","transactionTimestamp":"2024-03-03T10:00:00.000Z","narration":"UPI"}]}}}},
            {"maskedAccNumber":"XXXX7777","data":{"account":{"type":"term_deposit",
              "summary":{"principalAmount":"100000","currentValue":"104500","interestRate":"7.1","maturityDate":"2025-03-01"}}}},
            {"maskedAccNumber":"XXXX1","data":{"account":{"type":"mutual_funds",
              "summary":{"investment":{"holdings":{"holding":[
                {"isin":"INF179K01YZ4","isinDescription":"HDFC Top 100 Fund","closingUnits":"120.456","nav":"99.50","navDate":"2024-03-28","folioNo":"12345678"}]}}},
              "transactions":{"transaction":[
                {"isin":"INF179K01YZ4","type":"BUY","units":"10.000","nav":"95.00","amount":"950.00","txnDate":"2024-03-01","folioNo":"12345678"}]}}}},
            {"maskedAccNumber":"XXXX2","data":{"account":{"type":"equities",
              "summary":{"investment":{"holdings":{"holding":[
                {"ISIN":"INE002A01018","issuerName":"RELIANCE","units":"10","lastTradedPrice":"2950"}]}}}}}},
            {"maskedAccNumber":"XXXX3","data":{"account":{"type":"insurance_policies"}}},
            {"maskedAccNumber":"XXXX4","status":"DENIED"}
          ]}]}
        """;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + " auth=" + auth + " pid=" + ex.getRequestHeaders().getFirst("x-product-instance-id"));
            bodies.add(body);
            int code = 200;
            String out;
            if (path.equals("/token")) {
                tokenIssued++;
                out = "{\"success\":true,\"data\":{\"token\":\"tok-" + tokenIssued + "\",\"expiresIn\":1800}}";
            } else if (rejectFirstToken401 && "Bearer tok-1".equals(auth)) {
                code = 401; out = "{\"message\":\"expired\"}";
            } else if (path.equals("/v2/consents") && method.equals("POST")) {
                code = 201;
                out = "{\"id\":\"c16cc10e-eae3-4277-96e1-64c0ed21c53e\",\"url\":\"https://fiu.setu.co/v2/consents/webview/c16cc10e\",\"status\":\"PENDING\"}";
            } else if (path.matches("/v2/consents/[^/]+/revoke")) {
                out = "{\"status\":\"REVOKED\"}";
            } else if (path.startsWith("/v2/consents/")) {
                out = "{\"id\":\"c16cc10e-eae3-4277-96e1-64c0ed21c53e\",\"status\":\"" + consentStatus + "\"}";
            } else if (path.equals("/v2/sessions") && method.equals("POST")) {
                code = 201; out = "{\"id\":\"11111111-aaaa-bbbb-cccc-222222222222\",\"status\":\"PENDING\"}";
            } else if (path.startsWith("/v2/sessions/")) {
                out = sessionStatus.equals("COMPLETED") ? SESSION : "{\"status\":\"" + sessionStatus + "\"}";
            } else { code = 404; out = "{}"; }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new SetuAaProvider(M, WebClient.builder());
        ReflectionTestUtils.setField(provider, "baseUrl", base + "/v2");
        ReflectionTestUtils.setField(provider, "tokenUrl", base + "/token");
        ReflectionTestUtils.setField(provider, "clientId", "cid");
        ReflectionTestUtils.setField(provider, "clientSecret", "secret");
        ReflectionTestUtils.setField(provider, "productInstanceId", "pid-1");
        ReflectionTestUtils.setField(provider, "modeName", "SANDBOX");
        ReflectionTestUtils.setField(provider, "vuaHandle", "setu");
        ReflectionTestUtils.setField(provider, "testMobile", "");
        ReflectionTestUtils.setField(provider, "consentMonths", 12);
        ReflectionTestUtils.setField(provider, "historyMonths", 12);
        ReflectionTestUtils.setField(provider, "overlapDays", 2);
        ReflectionTestUtils.setField(provider, "sessionTimeoutSeconds", 3);
        ReflectionTestUtils.setField(provider, "pollIntervalMs", 20L);
        ReflectionTestUtils.setField(provider, "webhookSecret", "");
    }

    @AfterEach
    void stop() { server.stop(0); }

    private ConsentCapableProvider.ConsentRequest request(String mobile) {
        return new ConsentCapableProvider.ConsentRequest(7L, null, "p", List.of("DEPOSIT"), LocalDate.now().minusYears(1), LocalDate.now(), mobile);
    }

    @Test
    void sandboxIsLabelledTestNeverLive() {
        assertThat(provider.mode()).isEqualTo(ProviderMode.TEST);
        assertThat(provider.displayName()).contains("sandbox");
        ReflectionTestUtils.setField(provider, "modeName", "LIVE");
        assertThat(provider.mode()).isEqualTo(ProviderMode.LIVE);
    }

    @Test
    void createsAConsentWithTheUsersAaAddressAndAFutureDataRange() throws Exception {
        ConsentCapableProvider.ConsentInitiation init = provider.createConsent(request("9999999999"));
        assertThat(init.consentHandle()).isEqualTo("c16cc10e-eae3-4277-96e1-64c0ed21c53e");
        assertThat(init.redirectUrl()).startsWith("https://fiu.setu.co/");
        assertThat(init.status()).isEqualTo(ConsentStatus.PENDING_APPROVAL);

        assertThat(calls).anyMatch(c -> c.startsWith("POST /token"));
        assertThat(calls).anyMatch(c -> c.equals("POST /v2/consents auth=Bearer tok-1 pid=pid-1"));
        JsonNode sent = M.readTree(bodies.get(bodies.size() - 1));
        assertThat(sent.path("vua").asText()).isEqualTo("9999999999@setu");
        assertThat(sent.path("consentDuration").path("value").asText()).isEqualTo("12");
        assertThat(java.time.Instant.parse(sent.path("dataRange").path("to").asText())).isAfter(java.time.Instant.now().plusSeconds(86400L * 300));
    }

    @Test
    void anInvalidOrMissingMobileIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> provider.createConsent(request("12345"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> provider.createConsent(request(null))).isInstanceOf(ResponseStatusException.class);
        assertThat(calls).isEmpty();
        ReflectionTestUtils.setField(provider, "testMobile", "9999999999");
        assertThat(provider.createConsent(request(null)).consentHandle()).isNotNull();
    }

    @Test
    void mapsSetuConsentStatuses() {
        for (var e : Map.of("ACTIVE", ConsentStatus.APPROVED, "PENDING", ConsentStatus.PENDING_APPROVAL, "REJECTED", ConsentStatus.REJECTED,
            "REVOKED", ConsentStatus.REVOKED, "PAUSED", ConsentStatus.PAUSED, "EXPIRED", ConsentStatus.EXPIRED).entrySet()) {
            consentStatus = e.getKey();
            assertThat(provider.consentStatus("c16cc10e-eae3-4277-96e1-64c0ed21c53e")).isEqualTo(e.getValue());
        }
    }

    @Test
    void fetchReadsDepositsFundsEquitiesAndFixedDepositsAndSkipsWhatItCannotUnderstand() {
        consentStatus = "ACTIVE";
        var result = provider.fetch(new FinancialDataProvider.FetchRequest(7L, 1L, "c16cc10e-eae3-4277-96e1-64c0ed21c53e", null,
            LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), com.marketai.dataplatform.domain.SyncKind.INCREMENTAL));
        var recs = result.records();
        assertThat(recs).filteredOn(r -> r.kind() == RecordKind.TRANSACTION).hasSize(3); // 2 bank + 1 fund
        assertThat(recs).filteredOn(r -> r.kind() == RecordKind.HOLDING).hasSize(3);     // FD + fund + equity
        assertThat(recs).extracting(r -> r.schemaVersion()).containsOnly("aa-fi-v1");
        assertThat(result.nextCursor()).isEqualTo("2024-03-31");
        assertThat(result.coverageFrom()).isNull();

        String bank = recs.stream().filter(r -> r.payload().contains("\"T1\"")).findFirst().orElseThrow().payload();
        assertThat(bank).contains("\"fiType\":\"DEPOSIT\"").contains("\"type\":\"CREDIT\"").contains("\"date\":\"2024-03-02\"").contains("XXXX6053");
        String fund = recs.stream().filter(r -> r.kind() == RecordKind.HOLDING && r.payload().contains("INF179K01YZ4")).findFirst().orElseThrow().payload();
        assertThat(fund).contains("\"accountId\":\"12345678\"").contains("\"units\":\"120.456\"");
    }

    @Test
    void theSessionWindowIsWidenedByTheOverlap() throws Exception {
        consentStatus = "ACTIVE";
        provider.fetch(new FinancialDataProvider.FetchRequest(7L, 1L, "c16cc10e-eae3-4277-96e1-64c0ed21c53e", null,
            LocalDate.of(2024, 3, 10), LocalDate.of(2024, 3, 31), com.marketai.dataplatform.domain.SyncKind.INCREMENTAL));
        JsonNode sent = M.readTree(bodies.stream().filter(b -> b.contains("consentId")).findFirst().orElseThrow());
        assertThat(sent.path("dataRange").path("from").asText()).startsWith("2024-03-08");
        assertThat(sent.path("format").asText()).isEqualTo("json");
    }

    @Test
    void refetchingTheSameWindowYieldsTheSameRecordIds() {
        consentStatus = "ACTIVE";
        var req = new FinancialDataProvider.FetchRequest(7L, 1L, "c16cc10e-eae3-4277-96e1-64c0ed21c53e", null,
            LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), com.marketai.dataplatform.domain.SyncKind.INCREMENTAL);
        var a = provider.fetch(req).records().stream().map(r -> r.externalRecordId()).sorted().toList();
        var b = provider.fetch(req).records().stream().map(r -> r.externalRecordId()).sorted().toList();
        assertThat(a).isEqualTo(b).doesNotHaveDuplicates();
    }

    @Test
    void fetchRefusesAConsentThatIsNotApprovedAndFailedSessionsAreErrors() {
        consentStatus = "PENDING";
        var req = new FinancialDataProvider.FetchRequest(7L, 1L, "c16cc10e-eae3-4277-96e1-64c0ed21c53e", null,
            LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31), com.marketai.dataplatform.domain.SyncKind.INCREMENTAL);
        assertThatThrownBy(() -> provider.fetch(req)).hasMessageContaining("not approved");
        consentStatus = "ACTIVE";
        sessionStatus = "FAILED";
        assertThatThrownBy(() -> provider.fetch(req)).hasMessageContaining("failed");
        sessionStatus = "PENDING";
        assertThatThrownBy(() -> provider.fetch(req)).hasMessageContaining("did not finish");
    }

    @Test
    void anExpiredTokenIsRenewedOnceAndTheCallRetried() {
        rejectFirstToken401 = true;
        consentStatus = "ACTIVE";
        assertThat(provider.consentStatus("c16cc10e-eae3-4277-96e1-64c0ed21c53e")).isEqualTo(ConsentStatus.APPROVED);
        assertThat(tokenIssued).isEqualTo(2);
    }

    @Test
    void tokenIsCachedAcrossCalls() {
        consentStatus = "ACTIVE";
        provider.consentStatus("c16cc10e-eae3-4277-96e1-64c0ed21c53e");
        provider.consentStatus("c16cc10e-eae3-4277-96e1-64c0ed21c53e");
        assertThat(tokenIssued).isEqualTo(1);
    }

    @Test
    void aCallbackNeverTrustsItsOwnStatusAndReadsTheRealOneBackFromSetu() {
        consentStatus = "PENDING";
        String forged = "{\"type\":\"CONSENT_STATUS_UPDATE\",\"consentId\":\"c16cc10e-eae3-4277-96e1-64c0ed21c53e\",\"data\":{\"status\":\"ACTIVE\"}}";
        var event = provider.parseCallback(Map.of(), forged);
        assertThat(event).isPresent();
        assertThat(event.get().status()).isEqualTo(ConsentStatus.PENDING_APPROVAL); // Setu says pending, whatever the body claims
    }

    @Test
    void callbacksAreRejectedWhenMalformedWrongTypeOrMissingTheSecret() {
        assertThat(provider.parseCallback(Map.of(), null)).isEmpty();
        assertThat(provider.parseCallback(Map.of(), "not json")).isEmpty();
        assertThat(provider.parseCallback(Map.of(), "{\"type\":\"SESSION_STATUS_UPDATE\",\"consentId\":\"c16cc10e-eae3\"}")).isEmpty();
        assertThat(provider.parseCallback(Map.of(), "{\"type\":\"CONSENT_STATUS_UPDATE\",\"consentId\":\"../../etc\"}")).isEmpty();
        ReflectionTestUtils.setField(provider, "webhookSecret", "s3cret");
        String ok = "{\"type\":\"CONSENT_STATUS_UPDATE\",\"consentId\":\"c16cc10e-eae3-4277-96e1-64c0ed21c53e\"}";
        assertThat(provider.parseCallback(Map.of(), ok)).isEmpty();
        assertThat(provider.parseCallback(Map.of("x-webhook-secret", "wrong"), ok)).isEmpty();
        assertThat(provider.parseCallback(Map.of("x-webhook-secret", "s3cret"), ok)).isPresent();
    }

    @Test
    void revokeCallsSetuAndNeverThrowsIfSetuIsUnreachable() {
        provider.revokeConsent("c16cc10e-eae3-4277-96e1-64c0ed21c53e");
        assertThat(calls).anyMatch(c -> c.contains("/v2/consents/c16cc10e-eae3-4277-96e1-64c0ed21c53e/revoke"));
        server.stop(0);
        provider.revokeConsent("c16cc10e-eae3-4277-96e1-64c0ed21c53e"); // no exception
    }
}
