package com.marketai.schema;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import org.junit.jupiter.api.Test;
import java.util.List;
class ProveRecursion {
    @Test
    void bidirectionalEqualsRecurses() {
        Portfolio p = new Portfolio();
        p.setId(1L);
        Holding h = new Holding();
        h.setId(1L);
        h.setPortfolio(p);
        p.setHoldings(List.of(h));
        try {
            h.hashCode();
            System.out.println("RESULT: no recursion");
        } catch (StackOverflowError e) {
            System.out.println("RESULT: StackOverflowError — recursion CONFIRMED");
        }
    }
}
