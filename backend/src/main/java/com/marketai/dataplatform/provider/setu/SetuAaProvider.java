package com.marketai.dataplatform.provider.setu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.dataplatform.domain.ConsentStatus;
import com.marketai.dataplatform.domain.ProviderMode;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.provider.ConsentCapableProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Account Aggregator access through Setu's gateway. In {@code SANDBOX} mode (the default) every
 * record it produces is stored as TEST and the UI shows it as a sandbox connection; it only becomes
 * LIVE with {@code mode=LIVE}, which belongs to a production-approved product.
 *
 * <p>What Setu does for us: it is the AA-facing FIU plumbing (request signing, key exchange,
 * decryption), so this class speaks plain JSON — create a consent, the user approves it in Setu's
 * hosted consent screen, then create a data session and read the decrypted data.
 *
 * <p>What it can't do: make this app a regulated FIU. Production access needs a regulated FIU of
 * record (see the AA partner research report). Consent shape — purpose, FI types, fetch frequency,
 * data life — is configured on the Setu Bridge product, not sent per request in v2.
 *
 * <p>Continuity: AA has no push of new data. The sync service calls {@link #fetch} on a schedule;
 * each call opens a session over {@code [from - overlap, to]} and the pipeline's idempotent record
 * ids drop whatever was already imported.
 */
@Component
@ConditionalOnProperty(name = "wealthos.data.providers.setu.enabled", havingValue = "true")
@Slf4j
public class SetuAaProvider implements ConsentCapableProvider {

    public static final String ID = "setu-aa";
    private static final Pattern MOBILE = Pattern.compile("[6-9]\\d{9}");

    private final ObjectMapper mapper;
    private final WebClient.Builder webClient;

    @Value("${wealthos.data.providers.setu.base-url:https://fiu-sandbox.setu.co/v2}") private String baseUrl;
    @Value("${wealthos.data.providers.setu.token-url:https://uat.setu.co/api/v2/auth/token}") private String tokenUrl;
    @Value("${wealthos.data.providers.setu.client-id:}") private String clientId;
    @Value("${wealthos.data.providers.setu.client-secret:}") private String clientSecret;
    @Value("${wealthos.data.providers.setu.product-instance-id:}") private String productInstanceId;
    /** SANDBOX or LIVE. */
    @Value("${wealthos.data.providers.setu.mode:SANDBOX}") private String modeName;
    /** The part after '@' in the user's AA address (mobile@handle). */
    @Value("${wealthos.data.providers.setu.vua-handle:setu}") private String vuaHandle;
    /** Sandbox convenience: used when no mobile is given, so a developer can test with Setu's mock FIP. */
    @Value("${wealthos.data.providers.setu.test-mobile:}") private String testMobile;
    @Value("${wealthos.data.providers.setu.consent-months:12}") private int consentMonths;
    @Value("${wealthos.data.providers.setu.history-months:12}") private int historyMonths;
    @Value("${wealthos.data.providers.setu.overlap-days:2}") private int overlapDays;
    @Value("${wealthos.data.providers.setu.session-timeout-seconds:90}") private int sessionTimeoutSeconds;
    @Value("${wealthos.data.providers.setu.poll-interval-ms:2000}") private long pollIntervalMs;
    /** Optional: if set, a callback must carry this in the x-webhook-secret header (configure it on the Bridge notification). */
    @Value("${wealthos.data.providers.setu.webhook-secret:}") private String webhookSecret;

    private volatile SetuClient client;

    public SetuAaProvider(ObjectMapper mapper, WebClient.Builder webClient) {
        this.mapper = mapper;
        this.webClient = webClient;
    }

    @PostConstruct
    void init() {
        if (clientId.isBlank() || clientSecret.isBlank() || productInstanceId.isBlank())
            log.warn("Setu AA is enabled but client-id, client-secret or product-instance-id is missing; connecting will fail until they are set");
    }

    /** Created on first use so the properties are all injected, and replaceable in tests. */
    SetuClient client() {
        SetuClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) client = new SetuClient(webClient, mapper, baseUrl, tokenUrl, clientId, clientSecret,
                    productInstanceId, Duration.ofSeconds(30));
                c = client;
            }
        }
        return c;
    }

    @Override public String providerId() { return ID; }
    @Override public String displayName() { return "Setu Account Aggregator" + (live() ? "" : " (sandbox)"); }
    @Override public SourceType sourceType() { return SourceType.ACCOUNT_AGGREGATOR; }
    @Override public ProviderMode mode() { return live() ? ProviderMode.LIVE : ProviderMode.TEST; }
    private boolean live() { return "LIVE".equalsIgnoreCase(modeName); }

    // ------------------------------------------------------------------ consent

    @Override
    public ConsentInitiation createConsent(ConsentRequest request) {
        String mobile = request.subject() != null && !request.subject().isBlank() ? request.subject().trim() : testMobile;
        if (mobile == null || !MOBILE.matcher(mobile).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the 10-digit mobile number linked to your bank accounts.");

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ObjectNode body = mapper.createObjectNode();
        body.putObject("consentDuration").put("unit", "MONTH").put("value", String.valueOf(consentMonths));
        body.put("vua", mobile + "@" + vuaHandle);
        // The window of history this consent may ever return. Its end is in the future so later
        // incremental fetches stay inside it for the life of the consent.
        ObjectNode range = body.putObject("dataRange");
        range.put("from", now.minusMonths(historyMonths).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        range.put("to", now.plusMonths(consentMonths).toInstant().toString());
        body.putArray("context");
        body.putObject("additionalParams").putArray("tags").add("wealthos").add("user-" + request.userId());

        JsonNode res = call(() -> client().post("/consents", body), "create the consent");
        String id = res.path("id").asText(null);
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Setu did not return a consent id.");
        return new ConsentInitiation(id, res.path("url").asText(null), toStatus(res.path("status").asText("PENDING")));
    }

    @Override
    public ConsentStatus consentStatus(String handle) {
        try {
            return toStatus(client().get("/consents/" + safe(handle)).path("status").asText(null));
        } catch (SetuClient.SetuException e) {
            log.warn("Setu consent status check failed: {}", e.getMessage());
            return ConsentStatus.FAILED;
        }
    }

    @Override
    public void revokeConsent(String handle) {
        try {
            client().post("/consents/" + safe(handle) + "/revoke", mapper.createObjectNode());
        } catch (SetuClient.SetuException e) {
            // Revoking locally still stops us fetching; say so rather than pretend Setu agreed.
            log.warn("Setu did not confirm the revoke: {}", e.getMessage());
        }
    }

    /**
     * Setu's notification carries no documented signature. So the body is never trusted for the
     * outcome: it only names a consent, and the real status is read back from Setu with our own
     * credentials. A forged call can therefore at worst cost one status lookup. When a webhook
     * secret is configured it must also be present in the {@code x-webhook-secret} header.
     */
    @Override
    public Optional<ConsentEvent> parseCallback(Map<String, String> headers, String body) {
        try {
            if (!webhookSecret.isBlank()) {
                String got = headers.get("x-webhook-secret");
                if (got == null || !MessageDigest.isEqual(webhookSecret.getBytes(), got.getBytes())) return Optional.empty();
            }
            if (body == null) return Optional.empty();
            JsonNode n = mapper.readTree(body);
            if (!"CONSENT_STATUS_UPDATE".equals(n.path("type").asText())) return Optional.empty();
            String consentId = n.path("consentId").asText(null);
            if (consentId == null || !consentId.matches("[A-Za-z0-9-]{8,64}")) return Optional.empty();
            JsonNode actual = client().get("/consents/" + consentId);
            if (actual.path("status").isMissingNode()) return Optional.empty();
            return Optional.of(new ConsentEvent(consentId, toStatus(actual.path("status").asText()), "setu notification"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ data

    @Override
    public FetchResult fetch(FetchRequest request) {
        if (request.consentHandle() == null || !consentStatus(request.consentHandle()).usable())
            throw new IllegalStateException("consent is not approved");

        LocalDate to = request.to() != null ? request.to() : LocalDate.now();
        LocalDate from = request.from() != null ? request.from().minusDays(overlapDays) : to.minusMonths(historyMonths);

        ObjectNode body = mapper.createObjectNode();
        body.put("consentId", request.consentHandle());
        ObjectNode range = body.putObject("dataRange");
        range.put("from", from.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        range.put("to", to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        body.put("format", "json");

        JsonNode created = call(() -> client().post("/sessions", body), "start the data fetch");
        String sessionId = created.path("id").asText(null);
        if (sessionId == null) throw new IllegalStateException("Setu did not return a data session id");

        JsonNode session = awaitSession(sessionId);
        SetuFiMapper.Result mapped = SetuFiMapper.map(session, mapper, LocalDate.now());
        log.info("Setu fetch: {} account(s) read, {} skipped, {} record(s)", mapped.accountsRead, mapped.accountsSkipped, mapped.records.size());
        // No coverage is asserted: a partial session must not make the ledger flag "missing" entries.
        return new FetchResult(mapped.records, to.toString(), null, null);
    }

    /** Waits for the session to finish. COMPLETED and PARTIAL both carry usable data; the rest is an error. */
    private JsonNode awaitSession(String sessionId) {
        long deadline = System.currentTimeMillis() + sessionTimeoutSeconds * 1000L;
        while (true) {
            JsonNode s = client().get("/sessions/" + safe(sessionId));
            String status = s.path("status").asText("");
            if (status.equals("COMPLETED") || status.equals("PARTIAL")) return s;
            if (status.equals("FAILED") || status.equals("EXPIRED"))
                throw new IllegalStateException("Setu data session " + status.toLowerCase());
            if (System.currentTimeMillis() > deadline)
                throw new IllegalStateException("Setu data session did not finish in " + sessionTimeoutSeconds + "s (status " + status + ")");
            try { Thread.sleep(pollIntervalMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
        }
    }

    // ------------------------------------------------------------------ helpers

    static ConsentStatus toStatus(String s) {
        if (s == null) return ConsentStatus.FAILED;
        return switch (s.toUpperCase()) {
            case "ACTIVE" -> ConsentStatus.APPROVED;
            case "PENDING" -> ConsentStatus.PENDING_APPROVAL;
            case "REJECTED" -> ConsentStatus.REJECTED;
            case "REVOKED" -> ConsentStatus.REVOKED;
            case "PAUSED" -> ConsentStatus.PAUSED;
            case "EXPIRED" -> ConsentStatus.EXPIRED;
            default -> ConsentStatus.FAILED;
        };
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> call, String doing) {
        try {
            return call.get();
        } catch (SetuClient.SetuException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not " + doing + ": " + e.getMessage());
        }
    }

    /** Ids go into URL paths: only the characters a Setu id can contain. */
    private static String safe(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{8,64}")) throw new IllegalArgumentException("invalid id");
        return id;
    }
}
