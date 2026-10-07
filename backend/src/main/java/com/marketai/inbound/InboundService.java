package com.marketai.inbound;

import com.marketai.cas.CasImportService;
import com.marketai.gmail.security.PasswordCipher;
import com.marketai.onboarding.OnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forwarding inbox: a statement emailed to a user's private address is read and imported
 * without the user opening the app. Only CAS PDFs are read from mail today. A locked PDF with no
 * saved password waits (still encrypted) for the user to type one; nothing is ever imported on a
 * guess, and nothing is imported for a recipient address that doesn't resolve to a user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboundService {

    static final int MAX_ATTACHMENTS = 10;
    static final long MAX_BYTES = 15_000_000;
    private static final Pattern LOCAL = Pattern.compile("import-([a-z0-9]{12})@([^>\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InboundAddressRepository addresses;
    private final InboundItemRepository items;
    private final OnboardingService onboarding;
    private final CasImportService casImport;
    private final PasswordCipher cipher;

    @Value("${app.inbound.domain:}")
    private String domain;

    public record Attachment(String filename, byte[] bytes) {}

    public record ItemView(Long id, LocalDateTime receivedAt, String from, String subject, String filename, String status, String note) {}

    public record View(boolean enabled, String address, boolean hasCasPassword, List<ItemView> items) {}

    public boolean enabled() { return domain != null && !domain.isBlank(); }

    // ------------------------------------------------------------------ the user's side

    @Transactional
    public View view(Long userId) {
        List<ItemView> list = items.findTop20ByUserIdOrderByReceivedAtDesc(userId).stream().map(InboundService::view).toList();
        if (!enabled()) return new View(false, null, false, list);
        InboundAddress a = getOrCreate(userId);
        return new View(true, "import-" + a.getCode() + "@" + domain.trim().toLowerCase(Locale.ROOT),
            a.getCasPasswordEncrypted() != null, list);
    }

    @Transactional
    public View rotate(Long userId) {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Forwarding isn't set up on this server.");
        InboundAddress a = getOrCreate(userId);
        a.setCode(newCode());
        addresses.save(a);
        return view(userId);
    }

    @Transactional
    public void saveCasPassword(Long userId, String password) {
        if (password == null || password.isBlank() || password.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the password you set for your CAS.");
        InboundAddress a = getOrCreate(userId);
        a.setCasPasswordEncrypted(cipher.encrypt(password.trim()));
        addresses.save(a);
    }

    @Transactional
    public void clearCasPassword(Long userId) {
        addresses.findByUserId(userId).ifPresent(a -> { a.setCasPasswordEncrypted(null); addresses.save(a); });
    }

    /** Opens a waiting PDF with the password the user has now typed. A wrong password leaves it waiting. */
    @Transactional
    public ItemView unlock(Long userId, Long itemId, String password, boolean remember) {
        InboundItem item = items.findByIdAndUserId(itemId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        if (item.getStatus() != InboundItem.Status.NEEDS_PASSWORD || item.getPdfBytes() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item isn't waiting for a password.");
        CasImportService.Summary s = onboarding.importForwardedCas(userId, item.getPdfBytes(), password);
        item.setStatus(InboundItem.Status.IMPORTED);
        item.setNote(describe(s));
        item.setPdfBytes(null);
        items.save(item);
        if (remember) saveCasPassword(userId, password);
        return view(item);
    }

    @Transactional
    public void dismiss(Long userId, Long itemId) {
        items.delete(items.findByIdAndUserId(itemId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")));
    }

    // ------------------------------------------------------------------ the mail's side

    /** Handles one delivered email. Never throws for bad input: a delivery that can't be acted on is dropped and logged. */
    public void receive(String recipient, String sender, String subject, List<Attachment> attachments) {
        if (!enabled()) return;
        Long userId = resolveUser(recipient);
        if (userId == null) {
            log.warn("Inbound mail for an unknown or foreign address ignored");
            return;
        }
        int handled = 0;
        for (Attachment a : attachments) {
            if (a.bytes() == null || a.bytes().length == 0) continue;
            if (++handled > MAX_ATTACHMENTS) break;
            try {
                handleAttachment(userId, sender, subject, a);
            } catch (RuntimeException e) {
                log.error("Inbound attachment failed for user {}: {}", userId, e.getMessage());
                record(userId, sender, subject, a, InboundItem.Status.FAILED, "Unexpected error while reading this file.", null, null);
            }
        }
    }

    private void handleAttachment(Long userId, String sender, String subject, Attachment a) {
        String name = a.filename() == null ? "" : a.filename().toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(png|jpe?g|gif|bmp|ics|vcf|htm|html)$")) return; // signature images and the like
        if (!name.endsWith(".pdf")) {
            record(userId, sender, subject, a, InboundItem.Status.IGNORED,
                "Only CAS PDFs are read from forwarded mail. Upload CSV/Excel files under their source on the checklist.", null, null);
            return;
        }
        if (a.bytes().length > MAX_BYTES) {
            record(userId, sender, subject, a, InboundItem.Status.FAILED, "The file is too large (limit 15 MB).", null, null);
            return;
        }
        String sha = java.util.HexFormat.of().formatHex(digest(a.bytes()));
        if (items.existsByUserIdAndSha256AndStatus(userId, sha, InboundItem.Status.IMPORTED)) {
            log.info("Inbound duplicate attachment skipped for user {}", userId);
            return;
        }
        String saved = savedPassword(userId);
        if (casImport.needsPassword(a.bytes()) && saved == null) {
            record(userId, sender, subject, a, InboundItem.Status.NEEDS_PASSWORD,
                "Locked PDF. Enter its password to import it (or save your CAS password to skip this next time).", sha, a.bytes());
            return;
        }
        try {
            CasImportService.Summary s = onboarding.importForwardedCas(userId, a.bytes(), saved);
            record(userId, sender, subject, a, InboundItem.Status.IMPORTED, describe(s), sha, null);
        } catch (ResponseStatusException e) {
            String reason = e.getReason() == null ? "Could not read this statement." : e.getReason();
            boolean wrongPassword = reason.contains("didn't open");
            record(userId, sender, subject, a, wrongPassword ? InboundItem.Status.NEEDS_PASSWORD : InboundItem.Status.FAILED,
                wrongPassword ? "Your saved CAS password didn't open this PDF. Enter the right one." : reason,
                sha, wrongPassword ? a.bytes() : null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] digest(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Long resolveUser(String recipient) {
        if (recipient == null) return null;
        Matcher m = LOCAL.matcher(recipient);
        while (m.find()) {
            if (!m.group(2).replaceAll("[>,;]+$", "").equalsIgnoreCase(domain.trim())) continue;
            var found = addresses.findByCode(m.group(1).toLowerCase(Locale.ROOT));
            if (found.isPresent()) return found.get().getUserId();
        }
        return null;
    }

    private String savedPassword(Long userId) {
        return addresses.findByUserId(userId).map(InboundAddress::getCasPasswordEncrypted).map(enc -> {
            try { return cipher.decrypt(enc); } catch (Exception e) { return null; }
        }).orElse(null);
    }

    private InboundAddress getOrCreate(Long userId) {
        return addresses.findByUserId(userId).orElseGet(() -> addresses.save(InboundAddress.builder()
            .userId(userId).code(newCode()).createdAt(LocalDateTime.now()).build()));
    }

    private void record(Long userId, String sender, String subject, Attachment a, InboundItem.Status status,
                        String note, String sha, byte[] bytes) {
        items.save(InboundItem.builder().userId(userId).receivedAt(LocalDateTime.now())
            .fromAddress(cut(sender, 200)).subject(cut(subject, 200)).filename(cut(a.filename(), 200))
            .sha256(sha).status(status).note(cut(note, 500)).pdfBytes(bytes).build());
    }

    private static String describe(CasImportService.Summary s) {
        return (s.kind() == CasImportService.Kind.DEMAT ? "Demat CAS: " + s.schemes() + " holdings"
            : "Mutual-fund CAS: " + s.schemes() + " schemes") + ", " + s.created() + " new, " + s.duplicated()
            + " already present" + (s.warnings().isEmpty() ? "" : " · " + s.warnings().size() + " warning(s)");
    }

    private static ItemView view(InboundItem i) {
        return new ItemView(i.getId(), i.getReceivedAt(), i.getFromAddress(), i.getSubject(), i.getFilename(),
            i.getStatus().name(), i.getNote());
    }

    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    private static String newCode() {
        StringBuilder b = new StringBuilder(12);
        for (int i = 0; i < 12; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }

    /** A PDF that was never unlocked doesn't sit in the database forever. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void expireWaiting() {
        int n = items.expireWaiting(LocalDateTime.now().minusDays(14));
        if (n > 0) log.info("Expired {} forwarded statement(s) still waiting for a password", n);
    }
}
