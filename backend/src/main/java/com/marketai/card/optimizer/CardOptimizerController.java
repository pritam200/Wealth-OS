package com.marketai.card.optimizer;

import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cards/optimizer")
@RequiredArgsConstructor
public class CardOptimizerController {

    private final CardOptimizerService optimizer;
    private final SpendAggregator spendAggregator;

    /** Net-value verdict per card, against the user's own 90-day spend. */
    @GetMapping
    public ResponseEntity<CardOptimizerService.OptimizerResult> analyse(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(optimizer.analyse(user.getId()));
    }

    /** The spend profile on its own, including how much of it could be categorized. */
    @GetMapping("/spend-profile")
    public ResponseEntity<SpendAggregator.SpendProfile> spendProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(spendAggregator.aggregate(user.getId()));
    }

    /** Net-value verdict for one catalog card the user doesn't own yet, against their own spend. */
    @GetMapping("/catalog/{name}")
    public ResponseEntity<CardOptimizerService.ProspectiveResult> analyseCatalogCard(
            @AuthenticationPrincipal User user, @PathVariable String name) {
        return ResponseEntity.ok(optimizer.analyseCatalogCard(user.getId(), name));
    }

    /** Every catalog card not already owned, ranked by projected net value against the user's
     *  own spend — "which new card is actually worth getting". */
    @GetMapping("/catalog-all")
    public ResponseEntity<CardOptimizerService.CatalogRanking> analyseAllCatalogCards(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(optimizer.analyseAllCatalogCards(user.getId()));
    }
}
