package com.marketai.reconciliation.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.reconciliation.service.DataAuditService.Classification;
import com.marketai.reconciliation.service.DataAuditService.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the audit offers to remove is exactly what the rebuild will remove. */
class DataAuditRemovalRuleTest {

    private static Transaction trade(String symbol, Transaction.TransactionType type) {
        Transaction t = new Transaction();
        t.setHolding(Holding.builder().symbol(symbol).build());
        t.setType(type);
        return t;
    }

    @Test
    @DisplayName("duplicate or misread cash rows and purchases can go; a duplicated sale or any fund sale can't")
    void rule() {
        assertThat(DataAuditService.removalRefusal(Entity.EXPENSE, Classification.DUPLICATE, null)).isNull();
        assertThat(DataAuditService.removalRefusal(Entity.INCOME, Classification.MISREAD, null)).isNull();
        assertThat(DataAuditService.removalRefusal(Entity.TRANSACTION, Classification.DUPLICATE, trade("HAL.NS", Transaction.TransactionType.BUY))).isNull();
        assertThat(DataAuditService.removalRefusal(Entity.TRANSACTION, Classification.MISREAD, trade("HAL.NS", Transaction.TransactionType.SELL))).isNull();
        assertThat(DataAuditService.removalRefusal(Entity.TRANSACTION, Classification.MISREAD, trade("HAL.NS", Transaction.TransactionType.SPLIT))).isNull();

        assertThat(DataAuditService.removalRefusal(Entity.TRANSACTION, Classification.DUPLICATE, trade("HAL.NS", Transaction.TransactionType.SELL))).isNotNull();
        assertThat(DataAuditService.removalRefusal(Entity.TRANSACTION, Classification.MISREAD, trade("ICICIMC.MF", Transaction.TransactionType.SELL))).isNotNull();
        assertThat(DataAuditService.removalRefusal(Entity.HOLDING, Classification.DUPLICATE, null)).isNotNull();
    }
}
