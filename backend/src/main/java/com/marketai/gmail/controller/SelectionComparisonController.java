package com.marketai.gmail.controller;

import com.marketai.document.route.SelectionComparator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the shadow-mode parser-selection tally.
 *
 * <p>This is the cutover gate. Classifier routing replaces the legacy substring scan when this
 * endpoint reports zero regressions over a meaningful volume of real mail — not when its unit
 * tests pass. The counters are process-local and reset on restart, which is adequate for a
 * deliberate evaluation and avoids adding a table for a temporary measurement.
 */
@RestController
@RequestMapping("/api/gmail/selection-comparison")
@RequiredArgsConstructor
public class SelectionComparisonController {

    private final SelectionComparator comparator;

    @GetMapping
    public ResponseEntity<Map<String, Object>> tally() {
        SelectionComparator.Counters c = comparator.counters();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", c.total());
        body.put("agree", c.agree());
        body.put("agreeNeither", c.agreeNeither());
        body.put("routedOnly", c.routedOnly());
        body.put("legacyOnly", c.legacyOnly());
        body.put("disagree", c.disagree());
        body.put("regressions", c.regressions());
        body.put("cleanRate", c.cleanRate());
        body.put("safeToCutOver", c.total() > 0 && c.regressions() == 0);
        body.put("note", c.total() == 0
            ? "No emails compared yet — run a Gmail sync first."
            : c.regressions() == 0
                ? "No regressions observed. Routing selected the same parser as the legacy scan, "
                  + "or found one it missed."
                : c.regressions() + " regression(s): routing would select a different parser, or "
                  + "none, for mail the legacy scan handles today. Do not cut over.");
        return ResponseEntity.ok(body);
    }

    @DeleteMapping
    public ResponseEntity<Void> reset() {
        comparator.reset();
        return ResponseEntity.noContent().build();
    }
}
