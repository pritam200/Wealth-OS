package com.marketai.gmail.service;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
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
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
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

    /** Message ids in a sync window, oldest first, and whether the safety cap cut the list short. */
    public record MessageIdList(List<String> ids, boolean truncated) {}

    /**
     * Upper bound on ids listed in one sync — far above any realistic personal mailbox window,
     * present only so a runaway pagination bug cannot loop forever. Hitting it is reported, never
     * silent.
     */
    public static final int MESSAGE_ID_SAFETY_CAP = 50_000;

    /**
     * Every message id in the window — not just the newest few hundred. The previous
     * {@link #fetchRecentMessages} stopped at 300, so a 365-day backfill of a normal inbox read
     * only the last few weeks and silently never saw the rest.
     *
     * <p>Only spam and trash are excluded. The old category filter also dropped mail in the
     * Forums tab and anything Gmail left uncategorised, where bank and broker mail does land.
     *
     * <p>Oldest first, so an FD is opened before its renewal is read and a fund is bought before
     * its redemption is — otherwise the later event arrives with nothing to attach to.
     */
    public MessageIdList listMessageIds(Gmail gmail, String lookbackPeriod) throws Exception {
        String query = "newer_than:" + lookbackPeriod + " -in:spam -in:trash";
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        boolean truncated = false;
        do {
            ListMessagesResponse resp = gmail.users().messages().list("me")
                    .setQ(query)
                    .setMaxResults(500L)
                    .setPageToken(pageToken)
                    .execute();
            if (resp.getMessages() != null) {
                for (Message m : resp.getMessages()) ids.add(m.getId());
            }
            pageToken = resp.getNextPageToken();
            if (ids.size() >= MESSAGE_ID_SAFETY_CAP && pageToken != null) {
                truncated = true;
                break;
            }
        } while (pageToken != null);
        Collections.reverse(ids); // Gmail lists newest first
        return new MessageIdList(ids, truncated);
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

    /**
     * Reads the readable text of every part, once. A multipart/alternative holds the SAME
     * content in several forms (plain and HTML); appending both used to hand the model every
     * transaction twice, and an identical second line is numbered as a second occurrence — a
     * second transaction. Only the richest alternative is read: HTML (tables survive as rows),
     * else plain text.
     */
    private void extractText(MessagePart part, StringBuilder sb) {
        if (part == null) return;
        String mimeType = part.getMimeType() == null ? "" : part.getMimeType().toLowerCase();
        if ("multipart/alternative".equals(mimeType) && part.getParts() != null && !part.getParts().isEmpty()) {
            MessagePart chosen = null;
            for (MessagePart child : part.getParts()) {
                if (contains(child, "text/html")) { chosen = child; break; }
            }
            if (chosen == null) {
                for (MessagePart child : part.getParts()) {
                    if (contains(child, "text/plain")) { chosen = child; break; }
                }
            }
            extractText(chosen != null ? chosen : part.getParts().get(0), sb);
            return;
        }
        if ("text/plain".equals(mimeType) || "text/html".equals(mimeType)) {
            if (part.getBody() != null && part.getBody().getData() != null) {
                byte[] decoded = Base64.getUrlDecoder().decode(part.getBody().getData());
                String text = new String(decoded, StandardCharsets.UTF_8);
                if ("text/html".equals(mimeType)) text = htmlToText(text);
                sb.append(text).append("\n");
            }
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                extractText(child, sb);
            }
        }
    }

    private static boolean contains(MessagePart part, String mimeType) {
        if (part == null) return false;
        if (mimeType.equalsIgnoreCase(part.getMimeType())) return true;
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) if (contains(child, mimeType)) return true;
        }
        return false;
    }

    /**
     * HTML to readable text that keeps table structure: a row becomes a line and cells are
     * separated by " | ", so "Date | Description | Amount" rows stay attributable. Style and
     * script blocks are dropped, and the entities banks actually use are decoded (₹ included).
     */
    static String htmlToText(String html) {
        if (html == null) return "";
        String t = html
            .replaceAll("(?is)<(style|script|head)[^>]*>.*?</\\1>", " ")
            .replaceAll("(?is)<!--.*?-->", " ")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(p|div|tr|li|h[1-6]|table)>", "\n")
            .replaceAll("(?i)</t[dh]>", " | ")
            .replaceAll("<[^>]+>", " ")
            .replace("&nbsp;", " ").replace("&#160;", " ")
            .replace("&#8377;", "₹").replace("&#x20B9;", "₹").replace("&#x20b9;", "₹")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
             .replaceAll("(\\s*\\|\\s*)+\n", "\n")
             .replaceAll(" *\n[\\s]*", "\n");
        return t.trim();
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

    /** Below this an image attachment is a logo or signature, not a document. */
    static final int MIN_IMAGE_ATTACHMENT_BYTES = 30_000;

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
        // A photographed receipt or a scanned statement sent as an image. Only named attachments
        // of a real size — inline logos and tracking pixels are neither.
        boolean namedImage = com.marketai.document.ocr.ScanTranscriber.isImageFile(filename)
            && part.getMimeType() != null && part.getMimeType().toLowerCase().startsWith("image/");
        if (namedImage && part.getBody() != null && part.getBody().getAttachmentId() != null
                && part.getBody().getSize() != null && part.getBody().getSize() >= MIN_IMAGE_ATTACHMENT_BYTES) {
            out.add(new PdfAttachmentRef(part.getBody().getAttachmentId(), filename));
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) collectPdfParts(child, out);
        }
    }

    /** How an attachment is handled. Every attachment gets one, so none is passed over unrecorded. */
    public enum AttachmentKind {
        /** PDF or a real-size image: read through the statement pipeline (unlock, OCR, extraction). */
        DOCUMENT,
        /** CSV, TXT, HTML, XML: downloaded and read as text. */
        TEXT,
        /** Already part of the body text (a small text part sent inline). */
        INLINE_TEXT,
        /** A format that is not read automatically (spreadsheet, word document, archive, attached email). */
        UNSUPPORTED,
        /** Calendar invites, contact cards, signatures, logos — never financial documents. */
        NOT_A_DOCUMENT
    }

    public record AttachmentInfo(String attachmentId, String filename, String mimeType, Integer size, AttachmentKind kind) {
        /** "report.xlsx (spreadsheet)" — for the manifest. */
        public String label() {
            return filename + (kind == AttachmentKind.UNSUPPORTED ? " (" + formatName(filename, mimeType) + ")" : "");
        }
    }

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of("csv", "txt", "html", "htm", "xml", "tsv");
    private static final java.util.Set<String> NOISE_EXTENSIONS = java.util.Set.of("ics", "vcs", "vcf", "p7s", "asc", "sig", "gpg", "pgp");

    /** Every named attachment in the message, each with how it is handled. */
    public List<AttachmentInfo> listAttachments(Message message) {
        List<AttachmentInfo> out = new ArrayList<>();
        if (message.getPayload() != null) collectAttachments(message.getPayload(), out, true);
        return out;
    }

    private void collectAttachments(MessagePart part, List<AttachmentInfo> out, boolean root) {
        if (part == null) return;
        String filename = part.getFilename();
        String mime = part.getMimeType() == null ? "" : part.getMimeType().toLowerCase();
        boolean named = filename != null && !filename.isBlank();
        String attachmentId = part.getBody() != null ? part.getBody().getAttachmentId() : null;
        Integer size = part.getBody() != null ? part.getBody().getSize() : null;
        // The unnamed root part is the body itself; a named root part is a message that is only an attachment.
        if (!mime.startsWith("multipart/") && (named || (!root && attachmentId != null))) {
            String name = named ? filename : "(unnamed " + (mime.isEmpty() ? "attachment" : mime) + ")";
            String ext = extension(name);
            AttachmentKind kind;
            boolean pdf = "application/pdf".equals(mime) || "pdf".equals(ext);
            boolean image = mime.startsWith("image/");
            if (pdf && attachmentId != null) {
                kind = AttachmentKind.DOCUMENT;
            } else if (image) {
                kind = com.marketai.document.ocr.ScanTranscriber.isImageFile(filename) && attachmentId != null
                    && size != null && size >= MIN_IMAGE_ATTACHMENT_BYTES ? AttachmentKind.DOCUMENT : AttachmentKind.NOT_A_DOCUMENT;
            } else if (NOISE_EXTENSIONS.contains(ext) || mime.equals("text/calendar") || mime.equals("text/vcard")
                    || mime.contains("pkcs7-signature") || mime.contains("pgp-signature")) {
                kind = AttachmentKind.NOT_A_DOCUMENT;
            } else if (TEXT_EXTENSIONS.contains(ext) || mime.equals("text/csv") || mime.equals("text/plain")
                    || mime.equals("text/html") || mime.equals("text/xml") || mime.equals("application/xml")) {
                // A small text part arrives inline and extractText() has already read it with the body.
                kind = attachmentId != null ? AttachmentKind.TEXT
                    : part.getBody() != null && part.getBody().getData() != null ? AttachmentKind.INLINE_TEXT
                    : AttachmentKind.UNSUPPORTED;
            } else if ("message/rfc822".equals(mime) && attachmentId == null) {
                kind = AttachmentKind.INLINE_TEXT; // a forwarded email: its parts are read with the body
            } else {
                kind = AttachmentKind.UNSUPPORTED;
            }
            out.add(new AttachmentInfo(attachmentId, name, mime, size, kind));
            if (kind != AttachmentKind.INLINE_TEXT || !"message/rfc822".equals(mime)) return;
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) collectAttachments(child, out, false);
        }
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    static String formatName(String filename, String mime) {
        String ext = extension(filename);
        return switch (ext) {
            case "xls", "xlsx", "ods", "numbers" -> "spreadsheet";
            case "doc", "docx", "odt", "rtf" -> "word document";
            case "zip", "rar", "7z", "gz" -> "archive";
            case "eml", "msg" -> "attached email";
            default -> mime != null && !mime.isBlank() ? mime : "unknown format";
        };
    }

    /** An attachment's text, for the kinds read as text; HTML keeps its table rows. */
    public static String attachmentText(byte[] bytes, String filename, String mimeType) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        String ext = extension(filename);
        boolean html = "html".equals(ext) || "htm".equals(ext) || (mimeType != null && mimeType.contains("html"));
        return html ? htmlToText(text) : text;
    }

    /** Downloads attachment bytes on-demand — only called when the user actually clicks
     *  "Unlock & import" on a specific locked statement, not during the bulk sync pass. */
    public byte[] downloadAttachment(Gmail gmail, String messageId, String attachmentId) throws Exception {
        String data = gmail.users().messages().attachments().get("me", messageId, attachmentId)
            .execute().getData();
        return Base64.getUrlDecoder().decode(data);
    }
}
