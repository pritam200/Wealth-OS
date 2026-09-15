package com.marketai.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the single most important correctness property in this codebase:
 *
 *   Holding.quantity and Holding.averageCost are DERIVED, never asserted.
 *
 * They are written in exactly one place — PortfolioService.recomputeFromLedger — which replays
 * the full BUY/SELL transaction ledger for the holding. That is what makes manual entry and
 * Gmail import structurally incapable of disagreeing about a position: both paths funnel
 * through the same replay.
 *
 * Before this test existed the rule was enforced by convention and code review only, so nothing
 * failed if a second writer appeared. The pattern it prevents had already shipped once: three
 * hand-rolled weighted-average-cost calculations duplicated across addHolding/sellHolding/merge,
 * which could silently drift from what the ledger actually implied.
 *
 * Bytecode analysis rather than source grepping is deliberate — `setQuantity` is also declared
 * on ActionItem and on several request DTOs, so matching on the method name in source text
 * produces false positives. ArchUnit resolves the receiver's actual type.
 *
 * If this test fails, the fix is almost never to add the offending class to an exception list.
 * It is to route the change through a Transaction and let the replay derive the new state.
 */
class HoldingLedgerInvariantTest {

    private static final String HOLDING = "com.marketai.portfolio.entity.Holding";
    private static final String PORTFOLIO_SERVICE = "com.marketai.portfolio.service.PortfolioService";

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.marketai");
    }

    @Test
    @DisplayName("Only PortfolioService may write Holding.quantity")
    void onlyPortfolioServiceWritesQuantity() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(PORTFOLIO_SERVICE)
                .and().doNotHaveFullyQualifiedName(HOLDING)
                .should().callMethod(HOLDING, "setQuantity", "java.math.BigDecimal")
                .because("""
                        Holding.quantity is derived from the transaction ledger by \
                        PortfolioService.recomputeFromLedger. Setting it directly makes stored \
                        state disagree with the ledger that is supposed to explain it. Record a \
                        Transaction instead and let the replay derive the quantity.""");

        rule.check(productionClasses());
    }

    @Test
    @DisplayName("Only PortfolioService may write Holding.averageCost")
    void onlyPortfolioServiceWritesAverageCost() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(PORTFOLIO_SERVICE)
                .and().doNotHaveFullyQualifiedName(HOLDING)
                .should().callMethod(HOLDING, "setAverageCost", "java.math.BigDecimal")
                .because("""
                        Holding.averageCost is derived by weighted-average-cost replay over the \
                        full BUY/SELL ledger. Setting it directly reintroduces the duplicated \
                        cost-basis maths this codebase already removed once.""");

        rule.check(productionClasses());
    }
}
