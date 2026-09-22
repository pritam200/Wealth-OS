package com.marketai.schema;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Holding and Portfolio reference each other. If both included the other side in
 * {@code equals}/{@code hashCode}, hashing either one would recurse until the stack ran out —
 * and it would do so from inside anything that puts an entity in a {@code HashSet} or {@code Map},
 * which is most of the portfolio code. The class comment on {@code Portfolio} records that the
 * loop is broken deliberately; this is the test that keeps it broken.
 *
 * <p>Replaces a scratch probe that printed its result to stdout, asserted nothing, and — being
 * named {@code ProveRecursion} rather than {@code *Test} — was never run by Surefire at all.
 */
class BidirectionalEqualityTest {

    private static Portfolio linkedPair() {
        Portfolio p = new Portfolio();
        p.setId(1L);
        Holding h = new Holding();
        h.setId(1L);
        h.setPortfolio(p);
        p.setHoldings(List.of(h));
        return p;
    }

    @Test
    void hashingAHoldingDoesNotRecurseThroughItsPortfolio() {
        Holding h = linkedPair().getHoldings().get(0);
        assertThatCode(h::hashCode).doesNotThrowAnyException();
        assertThatCode(h::toString).doesNotThrowAnyException();
    }

    @Test
    void hashingAPortfolioDoesNotRecurseThroughItsHoldings() {
        Portfolio p = linkedPair();
        assertThatCode(p::hashCode).doesNotThrowAnyException();
        assertThatCode(p::toString).doesNotThrowAnyException();
    }

    @Test
    void anEntityCanLiveInAHashSet() {
        // The real-world trigger: any code that collects entities into a set or map key.
        Portfolio p = linkedPair();
        assertThatCode(() -> java.util.Set.of(p, p.getHoldings().get(0))).doesNotThrowAnyException();
    }
}
