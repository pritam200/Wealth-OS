package com.marketai.gmail.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Best-effort guess at a locked statement's password format. First tries to read it
 * straight out of the email body/subject text (most banks/brokers state it plainly, e.g.
 * "password is your PAN in uppercase"). Many statements never spell this out in the body at
 * all — the instruction lives only inside the PDF itself, or nowhere — so this also falls
 * back to well-known, industry-standard conventions for common Indian RTAs/brokers/exchanges
 * (CAMS, mStock, NSE all use PAN-in-uppercase as their password scheme). Returns null only
 * when neither the text nor a known-provider default applies, which the frontend shows as
 * "format unknown" rather than guessing wrong.
 */
@Component
public class PasswordHintExtractor {

    private static final Pattern PAN_AND_DOB = Pattern.compile("PAN.{0,40}(DOB|date of birth)|date of birth.{0,40}PAN", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN_FIRST4_DOB = Pattern.compile("first (four|4|5|five).{0,20}(letters?|characters?)?.{0,20}PAN.{0,60}(DOB|date of birth)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN_ONLY = Pattern.compile("PAN", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUSTOMER_ID = Pattern.compile("customer\\s*id|client\\s*id|client\\s*code", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLIO = Pattern.compile("folio\\s*(number|no\\.?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOB_ONLY = Pattern.compile("date of birth|DOB", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPPERCASE_HINT = Pattern.compile("upper\\s*case|capital\\s*letters?", Pattern.CASE_INSENSITIVE);

    // Sender domains whose password convention is public/well-documented and doesn't vary
    // per email — used only when the email body itself gives no explicit instruction.
    private static final Map<String, String> KNOWN_PROVIDER_DEFAULTS = new HashMap<>();
    static {
        KNOWN_PROVIDER_DEFAULTS.put("camsonline.com", "PAN (uppercase) — CAMS statement default");
        KNOWN_PROVIDER_DEFAULTS.put("mstock.com", "PAN (uppercase)");
        KNOWN_PROVIDER_DEFAULTS.put("nse.co.in", "PAN (uppercase) — NSE contract note default");
        KNOWN_PROVIDER_DEFAULTS.put("kfintech.com", "PAN (uppercase) — KFin/RTA statement default");
    }

    public String extract(String subject, String body) {
        return extract(subject, body, null);
    }

    public String extract(String subject, String body, String providerKey) {
        String text = ((subject == null ? "" : subject) + " " + (body == null ? "" : body));
        String fromText = fromText(text);
        if (fromText != null) return fromText;
        return providerKey == null ? null : KNOWN_PROVIDER_DEFAULTS.get(providerKey.toLowerCase());
    }

    private String fromText(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        // PAN is checked ahead of Folio/Customer-ID on purpose: CAMS-style emails ("password
        // is your PAN... in case PAN is not registered, use your Folio No.") mention Folio
        // only as a fallback, so matching Folio first (as this used to) reported the wrong
        // primary method. When both are mentioned, the hint says so explicitly.
        if (PAN_FIRST4_DOB.matcher(text).find()) return "First few letters of PAN + Date of Birth (DDMMYYYY)";
        if (PAN_AND_DOB.matcher(text).find()) return "PAN + Date of Birth (DDMMYYYY)";
        if (PAN_ONLY.matcher(text).find()) {
            boolean upper = UPPERCASE_HINT.matcher(text).find();
            String pan = upper ? "PAN (uppercase)" : "PAN";
            if (FOLIO.matcher(text).find()) return pan + " — or Folio No. if PAN isn't registered on the folio";
            return pan;
        }
        if (CUSTOMER_ID.matcher(text).find()) return "Customer ID";
        if (FOLIO.matcher(text).find()) return "Folio number";
        if (DOB_ONLY.matcher(text).find()) return "Date of Birth (DDMMYYYY)";
        return null;
    }
}
