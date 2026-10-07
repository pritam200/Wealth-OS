package com.marketai.inbound;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Where an inbound-mail service posts a delivered email (Mailgun route "forward to URL", SendGrid
 * Inbound Parse): multipart form data with the sender, recipient, subject and attachments.
 *
 * <p>The endpoint is unauthenticated by necessity, so it fails closed: unless a webhook secret is
 * configured and equals the one in the URL, nothing is read. It answers 200 either way so the
 * provider doesn't retry a delivery that will never be accepted. When a Mailgun signing key is
 * configured, each delivery's signature is verified too.
 */
@RestController
@RequestMapping("/api/inbound/email")
@Slf4j
public class InboundWebhookController {

    private final InboundService service;

    @Value("${app.inbound.webhook-secret:}")
    private String webhookSecret;

    @Value("${app.inbound.mailgun-signing-key:}")
    private String mailgunKey;

    public InboundWebhookController(InboundService service) { this.service = service; }

    @PostMapping(value = "/{secret}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> receive(@PathVariable String secret, MultipartHttpServletRequest req) throws IOException {
        if (!service.enabled() || webhookSecret == null || webhookSecret.isBlank()) return ResponseEntity.ok().build();
        if (!MessageDigest.isEqual(webhookSecret.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Inbound mail webhook called with a wrong secret");
            return ResponseEntity.ok().build();
        }
        if (mailgunKey != null && !mailgunKey.isBlank() && !validMailgunSignature(req)) {
            log.warn("Inbound mail rejected: bad Mailgun signature");
            return ResponseEntity.ok().build();
        }
        // Mailgun: recipient/sender; SendGrid: to/from.
        String recipient = first(req, "recipient", "to");
        String sender = first(req, "sender", "from");
        String subject = first(req, "subject");

        List<InboundService.Attachment> attachments = new ArrayList<>();
        for (MultipartFile f : req.getFileMap().values()) attachments.add(new InboundService.Attachment(f.getOriginalFilename(), f.getBytes()));
        service.receive(recipient, sender, subject, attachments);
        return ResponseEntity.ok().build();
    }

    private static String first(MultipartHttpServletRequest req, String... names) {
        for (String n : names) {
            String v = req.getParameter(n);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private boolean validMailgunSignature(MultipartHttpServletRequest req) {
        String ts = req.getParameter("timestamp"), token = req.getParameter("token"), sig = req.getParameter("signature");
        if (ts == null || token == null || sig == null) return false;
        try {
            if (Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(ts)) > 900) return false; // replayed or stale
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mailgunKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal((ts + token).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
