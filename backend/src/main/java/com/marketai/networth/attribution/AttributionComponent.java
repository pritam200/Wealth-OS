package com.marketai.networth.attribution;

import java.math.BigDecimal;

/**
 * One signed contribution to a change in net worth.
 *
 * <p>{@code sourceRef} points back to the ledger event that produced it. Without it,
 * "₹12,400 came from market movement" is an assertion the user cannot check — and this system's
 * standing rule is that every financial number is traceable.
 *
 * @param amount    signed: positive increases net worth, negative decreases it
 * @param sourceRef ledger event, holding, or transaction this derives from
 */
public record AttributionComponent(AttributionKind kind, BigDecimal amount,
                                   String label, String sourceRef) {

    public AttributionComponent {
        if (amount == null) {
            throw new IllegalArgumentException(
                "An attribution component must carry an amount — an unquantified explanation "
                    + "cannot be reconciled against the change it claims to explain");
        }
    }

    public static AttributionComponent of(AttributionKind kind, BigDecimal signedAmount,
                                          String label, String sourceRef) {
        return new AttributionComponent(kind, signedAmount, label, sourceRef);
    }
}
