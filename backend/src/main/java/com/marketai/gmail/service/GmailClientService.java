package com.marketai.gmail.service;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class GmailClientService {

    private static final Logger log = LoggerFactory.getLogger(GmailClientService.class);
    private static final JacksonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final String APP_NAME = "MarketAI Portfolio Tracker";

    @Value("${gmail.client-id:}")
    private String clientId;

    @Value("${gmail.client-secret:}")
    private String clientSecret;

    @Value("${gmail.redirect-uri:http://localhost:8080/api/gmail/callback}")
    private String redirectUri;

    // Fallback so credentials survive backend restarts without re-setting IntelliJ
    // env vars each time: if GMAIL_CLIENT_ID/SECRET weren't in the OS environment,
    // read them straight from gitignored gmail.env in the working directory.
    @PostConstruct
    private void loadFromEnvFileIfMissing() {
        if (isConfigured()) return;
        File envFile = new File("gmail.env");
        if (!envFile.exists()) envFile = new File("backend/gmail.env");
        if (!envFile.exists()) return;
        try {
            for (String line : Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                if ("GMAIL_CLIENT_ID".equals(key) && (clientId == null || clientId.isEmpty())) clientId = value;
                if ("GMAIL_CLIENT_SECRET".equals(key) && (clientSecret == null || clientSecret.isEmpty())) clientSecret = value;
            }
            if (isConfigured()) log.info("Loaded Gmail OAuth credentials from gmail.env");
        } catch (Exception e) {
            log.warn("Could not read gmail.env: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
    }

    private GoogleAuthorizationCodeFlow buildFlow() throws Exception {
        String secretJson = String.format(
            "{\"web\":{\"client_id\":\"%s\",\"client_secret\":\"%s\",\"redirect_uris\":[\"%s\"],\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\",\"token_uri\":\"https://oauth2.googleapis.com/token\"}}",
            clientId, clientSecret, redirectUri);
        GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(secretJson));
        return new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, secrets,
                Collections.singletonList(GmailScopes.GMAIL_READONLY))
                .setAccessType("offline")
                .build();
    }

    public String getAuthorizationUrl(Long userId) throws Exception {
        String signedState = signState(userId);
        return buildFlow()
                .newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(signedState)
                .set("prompt", "consent")
                .build();
    }

    public Long verifyAndExtractUserId(String state) {
        if (state == null || !state.contains(".")) return null;
        int dot = state.lastIndexOf('.');
        String userIdStr = state.substring(0, dot);
        String providedSig = state.substring(dot + 1);
        try {
            Long userId = Long.parseLong(userIdStr);
            String expectedSig = hmacSha256(userIdStr);
            if (expectedSig.equals(providedSig)) return userId;
            log.warn("OAuth state signature mismatch for userId={}", userIdStr);
            return null;
        } catch (Exception e) {
            log.warn("Failed to verify OAuth state: {}", e.getMessage());
            return null;
        }
    }

    private String signState(Long userId) throws Exception {
        String payload = String.valueOf(userId);
        return payload + "." + hmacSha256(payload);
    }

    private String hmacSha256(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    public GoogleTokenResponse exchangeCode(String code) throws Exception {
        return buildFlow()
                .newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();
    }

    public Gmail buildGmailService(String accessToken, String refreshToken) throws Exception {
        return buildGmailService(accessToken, refreshToken, null);
    }

    /**
     * @param onTokenRefreshed callback invoked when Google auto-refreshes the access token,
     *                         so the caller can persist the new token to the database.
     */
    public Gmail buildGmailService(String accessToken, String refreshToken, TokenRefreshCallback onTokenRefreshed) throws Exception {
        Credential.Builder builder = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(HTTP_TRANSPORT)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new com.google.api.client.http.GenericUrl("https://oauth2.googleapis.com/token"))
                .setClientAuthentication(new com.google.api.client.auth.oauth2.ClientParametersAuthentication(clientId, clientSecret));

        if (onTokenRefreshed != null) {
            builder.addRefreshListener(new com.google.api.client.auth.oauth2.CredentialRefreshListener() {
                @Override
                public void onTokenResponse(Credential credential, com.google.api.client.auth.oauth2.TokenResponse tokenResponse) {
                    try {
                        onTokenRefreshed.onRefreshed(tokenResponse.getAccessToken(),
                            tokenResponse.getRefreshToken(),
                            tokenResponse.getExpiresInSeconds());
                    } catch (Exception e) {
                        log.warn("Failed to persist refreshed token: {}", e.getMessage());
                    }
                }
                @Override
                public void onTokenErrorResponse(Credential credential, com.google.api.client.auth.oauth2.TokenErrorResponse tokenErrorResponse) {
                    log.error("Token refresh failed: {}", tokenErrorResponse.getError());
                }
            });
        }

        Credential credential = builder.build()
                .setAccessToken(accessToken)
                .setRefreshToken(refreshToken);

        return new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APP_NAME)
                .build();
    }

    @FunctionalInterface
    public interface TokenRefreshCallback {
        void onRefreshed(String newAccessToken, String newRefreshToken, Long expiresInSeconds);
    }

    public List<Message> fetchRecentMessages(Gmail gmail, int maxResults) throws Exception {
        return fetchRecentMessages(gmail, maxResults, "14d");
    }

    public List<Message> fetchRecentMessages(Gmail gmail, int maxResults, String lookbackPeriod) throws Exception {
        String query = "newer_than:" + lookbackPeriod + " (category:primary OR category:updates OR category:promotions OR category:social)";

        List<com.google.api.services.gmail.model.Message> messageRefs = new ArrayList<>();
        String pageToken = null;
        // Safety cap so a very large mailbox can't turn one sync into an unbounded Gmail-API loop.
        int maxPages = 20;
        for (int page = 0; page < maxPages && messageRefs.size() < maxResults; page++) {
            ListMessagesResponse resp = gmail.users().messages().list("me")
                    .setQ(query)
                    .setMaxResults(Math.min(500L, (long) (maxResults - messageRefs.size())))
                    .setPageToken(pageToken)
                    .execute();
            if (resp.getMessages() != null) messageRefs.addAll(resp.getMessages());
            pageToken = resp.getNextPageToken();
            if (pageToken == null) break;
        }
        if (messageRefs.isEmpty()) return Collections.emptyList();

        List<Message> full = new ArrayList<>();
        for (com.google.api.services.gmail.model.Message m : messageRefs) {
            try {
                full.add(gmail.users().messages().get("me", m.getId())
                        .setFormat("full")
                        .execute());
            } catch (Exception e) {
                log.warn("Failed to fetch message {}: {}", m.getId(), e.getMessage());
            }
        }
        return full;
    }

    // Fetches a single message by id — used to re-read a locked statement's body text
    // (e.g. to backfill a password-format hint for PendingPdf rows queued before that
    // feature existed, which never had the body text persisted).
    public Message getMessage(Gmail gmail, String messageId) throws Exception {
        return gmail.users().messages().get("me", messageId).setFormat("full").execute();
    }

    public String getFrom(Message message) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) return "";
        return message.getPayload().getHeaders().stream()
                .filter(h -> "From".equalsIgnoreCase(h.getName()))
                .map(h -> h.getValue())
                .findFirst().orElse("");
    }

    public String getSubject(Message message) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) return "";
        return message.getPayload().getHeaders().stream()
                .filter(h -> "Subject".equalsIgnoreCase(h.getName()))
                .map(h -> h.getValue())
                .findFirst().orElse("");
    }

    public String getBodyText(Message message) {
        if (message.getPayload() == null) return "";
        StringBuilder sb = new StringBuilder();
        extractText(message.getPayload(), sb);
        return sb.toString();
    }

    private void extractText(MessagePart part, StringBuilder sb) {
        if (part == null) return;
        String mimeType = part.getMimeType();
        if ("text/plain".equalsIgnoreCase(mimeType) || "text/html".equalsIgnoreCase(mimeType)) {
            if (part.getBody() != null && part.getBody().getData() != null) {
                byte[] decoded = Base64.getUrlDecoder().decode(part.getBody().getData());
                String text = new String(decoded, StandardCharsets.UTF_8);
                // Strip basic HTML tags for text/html
                if ("text/html".equalsIgnoreCase(mimeType)) {
                    text = text.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ");
                }
                sb.append(text).append("\n");
            }
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                extractText(child, sb);
            }
        }
    }

    public String getUserEmail(Gmail gmail) {
        try {
            return gmail.users().getProfile("me").execute().getEmailAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** A financial email with a PDF attachment (contract note, margin/account statement) but
     *  no usable transaction text in the body — extractText() only reads text/plain and
     *  text/html parts and has never looked at attachments at all. This is the reference
     *  captured during sync; the bytes themselves are only downloaded on-demand when the
     *  user unlocks it (see PdfImportService), not during the bulk sync pass. */
    public static class PdfAttachmentRef {
        public final String attachmentId;
        public final String filename;
        public PdfAttachmentRef(String attachmentId, String filename) { this.attachmentId = attachmentId; this.filename = filename; }
    }

    public List<PdfAttachmentRef> findPdfAttachments(Message message) {
        List<PdfAttachmentRef> out = new ArrayList<>();
        if (message.getPayload() != null) collectPdfParts(message.getPayload(), out);
        return out;
    }

    private void collectPdfParts(MessagePart part, List<PdfAttachmentRef> out) {
        if (part == null) return;
        String filename = part.getFilename();
        boolean looksLikePdf = "application/pdf".equalsIgnoreCase(part.getMimeType())
            || (filename != null && filename.toLowerCase().endsWith(".pdf"));
        if (looksLikePdf && part.getBody() != null && part.getBody().getAttachmentId() != null) {
            out.add(new PdfAttachmentRef(part.getBody().getAttachmentId(), filename != null ? filename : "statement.pdf"));
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) collectPdfParts(child, out);
        }
    }

    /** Downloads attachment bytes on-demand — only called when the user actually clicks
     *  "Unlock & import" on a specific locked statement, not during the bulk sync pass. */
    public byte[] downloadAttachment(Gmail gmail, String messageId, String attachmentId) throws Exception {
        String data = gmail.users().messages().attachments().get("me", messageId, attachmentId)
            .execute().getData();
        return Base64.getUrlDecoder().decode(data);
    }
}
