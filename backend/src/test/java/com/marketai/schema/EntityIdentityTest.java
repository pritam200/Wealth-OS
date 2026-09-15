package com.marketai.schema;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lombok's {@code @Data} generates equals, hashCode and toString across every field — JPA
 * relations included. On a bidirectional mapping that recurses without end.
 *
 * Reproduced before the fix: {@code Holding.hashCode()} read its portfolio, which read its
 * holdings list, which read the same holding again, and the thread died with StackOverflowError.
 * {@code toString()} had the identical shape, so a debug log line that interpolated an entity
 * crashed the thread that wrote it.
 *
 * Identity is now the primary key alone, which is what JPA means by "the same row".
 */
class EntityIdentityTest {

    private static Portfolio bidirectionalGraph() {
        Portfolio p = new Portfolio();
        p.setId(1L);

        Holding h = new Holding();
        h.setId(10L);
        h.setSymbol("RELIANCE.NS");
        h.setPortfolio(p);          // child → parent

        Transaction t = new Transaction();
        t.setId(100L);
        t.setHolding(h);            // grandchild → child

        h.setTransactions(List.of(t));
        p.setHoldings(List.of(h));  // parent → child, closing the cycle
        return p;
    }

    @Test
    @DisplayName("hashCode on a bidirectional graph no longer overflows the stack")
    void hashCodeDoesNotRecurse() {
        Portfolio p = bidirectionalGraph();
        Holding h = p.getHoldings().getFirst();

        assertThatCode(() -> {
            p.hashCode();
            h.hashCode();
            h.getTransactions().getFirst().hashCode();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("equals on a bidirectional graph no longer overflows the stack")
    void equalsDoesNotRecurse() {
        Portfolio a = bidirectionalGraph();
        Portfolio b = bidirectionalGraph();

        assertThatCode(() -> a.equals(b)).doesNotThrowAnyException();
        assertThat(a).isEqualTo(b);   // same id = same row
    }

    @Test
    @DisplayName("toString no longer recurses — logging an entity used to crash the thread")
    void toStringDoesNotRecurse() {
        Portfolio p = bidirectionalGraph();

        assertThatCode(() -> {
            String s = p.toString();
            assertThat(s).contains("1");
            p.getHoldings().getFirst().toString();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("entities can be used in hash-based collections")
    void entitiesWorkInHashSets() {
        // Any HashSet, HashMap key, or .contains() call previously hit the recursion.
        Portfolio p = bidirectionalGraph();
        Set<Holding> set = new HashSet<>();

        assertThatCode(() -> set.add(p.getHoldings().getFirst())).doesNotThrowAnyException();
        assertThat(set).hasSize(1);
        assertThat(set.contains(p.getHoldings().getFirst())).isTrue();
    }

    @Test
    @DisplayName("identity is the primary key, not the field contents")
    void identityIsTheId() {
        Holding a = new Holding();
        a.setId(5L);
        a.setSymbol("RELIANCE.NS");

        Holding b = new Holding();
        b.setId(5L);
        b.setSymbol("INFY.NS");     // different contents, same row

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        Holding other = new Holding();
        other.setId(6L);
        assertThat(a).isNotEqualTo(other);
    }
}
