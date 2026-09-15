package com.marketai.document.classify;

/** The raw material a classifier stage inspects. Immutable; stages must not mutate it. */
public record ClassificationCandidate(String from, String subject, String bodyText,
                                      String attachmentFilename, String attachmentMimeType,
                                      String pdfProducer) {

    public static ClassificationCandidate email(String from, String subject, String bodyText) {
        return new ClassificationCandidate(from, subject, bodyText, null, null, null);
    }

    /**
     * Lower-cased domain of the actual envelope address, or null when absent or malformed.
     *
     * <p>Deliberately parses the address inside angle brackets when present. A From header is
     * {@code "Display Name" <user@host>}, and the display name is attacker-controlled: matching
     * anywhere in the raw header lets {@code "Zerodha Alerts" <noreply@evil.example>} pass as
     * Zerodha. Only the part after the last {@code @} of the bracketed address is authority.
     */
    public String senderDomain() {
        if (from == null) return null;
        String addr = from.trim();

        int open = addr.lastIndexOf('<');
        int close = addr.lastIndexOf('>');
        if (open >= 0 && close > open) {
            addr = addr.substring(open + 1, close).trim();
        }

        int at = addr.lastIndexOf('@');
        if (at < 0 || at == addr.length() - 1) return null;

        String domain = addr.substring(at + 1).trim().toLowerCase();
        // Strip anything that cannot be part of a hostname, so a trailing comma or quote in a
        // malformed header cannot produce a domain that never matches anything.
        int cut = domain.length();
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-')) { cut = i; break; }
        }
        domain = domain.substring(0, cut);
        return domain.isBlank() || !domain.contains(".") ? null : domain;
    }

    public String subjectOrEmpty() { return subject == null ? "" : subject; }
    public String bodyOrEmpty()    { return bodyText == null ? "" : bodyText; }
}
