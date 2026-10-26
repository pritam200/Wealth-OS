package com.marketai.gmail.service;

import com.marketai.document.classify.ClassificationCandidate;
import com.marketai.document.classify.IssuerDomainRegistry;
import com.marketai.gmail.entity.FinancialSender;
import com.marketai.gmail.repository.FinancialSenderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which senders a sync reads: the banks, brokers, depositories, registrars and fund houses in
 * {@link IssuerDomainRegistry}, plus any the user adds. Everything else in the inbox (job
 * sites, social networks, shops) is never fetched, so it can't be misread or use up the
 * extractor's daily quota — before this, nine in ten emails sent to the extractor were not
 * financial at all.
 */
@Service
@RequiredArgsConstructor
public class FinancialSenderService {

    private final FinancialSenderRepository repo;

    /** Every domain this user's sync reads, sorted. */
    public Set<String> domains(Long userId) {
        Set<String> out = new TreeSet<>(IssuerDomainRegistry.domains());
        for (FinancialSender s : repo.findByUserIdOrderByCreatedAtDesc(userId)) out.add(s.getDomain());
        return out;
    }

    /** Whether a From header is one of those senders (the domain or a subdomain of it). */
    public boolean allows(Long userId, String fromHeader) {
        String domain = ClassificationCandidate.email(fromHeader, null, null).senderDomain();
        if (domain == null) return false;
        if (IssuerDomainRegistry.isKnown(domain)) return true;
        for (FinancialSender s : repo.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (domain.equals(s.getDomain()) || domain.endsWith("." + s.getDomain())) return true;
        }
        return false;
    }

    /** The Gmail search clause that fetches only those senders: {@code from:(a.com OR b.com …)}. */
    public String gmailFromClause(Long userId) {
        return "from:(" + String.join(" OR ", domains(userId)) + ")";
    }

    public List<FinancialSender> added(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Adds a domain; accepts "aubank.in", "alerts@aubank.in" or "https://aubank.in/x". */
    public FinancialSender add(Long userId, String input) {
        String domain = normalise(input);
        if (domain == null) throw new IllegalArgumentException("Enter a sender domain such as aubank.in");
        if (repo.existsByUserIdAndDomain(userId, domain)) {
            return repo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(s -> s.getDomain().equals(domain)).findFirst().orElseThrow();
        }
        return repo.save(FinancialSender.builder().userId(userId).domain(domain).build());
    }

    public boolean remove(Long userId, Long id) {
        if (!repo.existsByIdAndUserId(id, userId)) return false;
        repo.deleteById(id);
        return true;
    }

    static String normalise(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z]+://", "");
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        s = s.replaceAll("[>\\s]", "");
        // A bare public mail domain would let every personal email through.
        if (Set.of("gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "icloud.com").contains(s)) return null;
        return s.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") ? s : null;
    }
}
