package com.marketai.document.route;

import com.marketai.document.classify.DocumentClassification;
import com.marketai.gmail.parser.EmailParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Orders the parser candidates for a document using its classification.
 *
 * <p>The legacy selection asks all 18 parsers {@code canParse(from, subject)} in bean order and
 * takes the first that says yes. Two problems with that: it is influenced by the display name
 * (see {@code SenderTrustEvaluator}), and the winner depends on injection order, so adding a
 * parser can silently shadow an existing one.
 *
 * <p>Routing puts the issuer's own parser first when the sending domain identifies one, then the
 * cross-issuer generics.
 *
 * <p><b>The critical property is that routing never narrows coverage.</b> When the issuer is
 * unknown — an unregistered bank, a new broker — the full legacy candidate list is returned
 * unchanged. Routing reorders; it does not exclude. Anything the old path could parse, the new
 * path can still parse.
 */
@Component
public class ParserRouter {

    /**
     * Candidates in the order they should be tried.
     *
     * @param classification may be null, meaning nothing was recognised
     * @param allParsers     the full injected list, in bean order — the legacy sequence
     */
    public List<EmailParser> candidatesFor(DocumentClassification classification,
                                           List<EmailParser> allParsers) {
        if (classification == null || classification.issuer() == null) {
            // No issuer identified. Return the legacy order untouched rather than guessing:
            // narrowing here is how a routing change silently stops importing a real sender.
            return List.copyOf(allParsers);
        }

        String preferred = ExtractorRegistry.parserFor(classification.issuer()).orElse(null);

        List<EmailParser> ordered = new ArrayList<>(allParsers.size());
        List<EmailParser> generics = new ArrayList<>();
        List<EmailParser> others = new ArrayList<>();

        for (EmailParser p : allParsers) {
            String name = p.getClass().getSimpleName();
            if (preferred != null && name.equals(preferred)) {
                ordered.add(p);                 // the issuer's own parser goes first
            } else if (ExtractorRegistry.isGeneric(name)) {
                generics.add(p);
            } else {
                others.add(p);
            }
        }

        ordered.addAll(generics);
        // Other issuers' parsers stay in the list as a last resort. They should not match — a
        // Zerodha email is not an Axis email — but keeping them means a registry mistake costs
        // efficiency, not a dropped transaction.
        ordered.addAll(others);
        return ordered;
    }

    /** The parser that would be preferred, for logging and comparison. Null when none applies. */
    public String preferredParserName(DocumentClassification classification) {
        return classification == null ? null
            : ExtractorRegistry.parserFor(classification.issuer()).orElse(null);
    }
}
