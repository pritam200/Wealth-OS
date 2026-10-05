package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Completes the figures a source left out, from the ones it stated. Never overwrites a stated figure. */
public final class AmountMath {
    private AmountMath() {}

    public static NormalizedTransaction complete(NormalizedTransaction t) {
        BigDecimal gross = t.getGrossAmount();
        BigDecimal qty = t.getQuantity();
        BigDecimal price = t.getUnitPrice();
        if (gross == null && qty != null && price != null) gross = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        if (price == null && gross != null && qty != null && qty.signum() > 0) price = gross.divide(qty, 6, RoundingMode.HALF_UP);
        BigDecimal net = t.getNetAmount();
        if (net == null && gross != null) {
            BigDecimal load = nz(t.getFees()).add(nz(t.getTaxes()));
            // Money out on a purchase includes charges; money in from a sale is after them.
            net = t.getType() != null && t.getType().unitEffect() < 0 ? gross.subtract(load) : gross.add(load);
        }
        return t.toBuilder().grossAmount(gross).unitPrice(price).netAmount(net).build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
