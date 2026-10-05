package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Structural checks on a normalised record. A record that fails is rejected with the reasons, not repaired. */
@Component
public class RecordValidator {

    public List<String> validate(NormalizedTransaction t, LocalDate today) {
        List<String> errors = new ArrayList<>();
        if (t.getType() == null) errors.add("transaction type missing");
        if (t.getTransactionDate() == null) errors.add("transaction date missing");
        else if (t.getTransactionDate().isAfter(today.plusDays(1))) errors.add("transaction date is in the future");
        if (t.getAccount() == null || t.getAccount().institution() == null || t.getAccount().institution().isBlank())
            errors.add("institution missing");
        if (t.getSourceType() == null) errors.add("source type missing");
        if (t.getType() != null) {
            boolean ca = t.getType() == TransactionType.SPLIT || t.getType() == TransactionType.MERGER;
            boolean hasFigure = t.getQuantity() != null || t.getGrossAmount() != null || t.getNetAmount() != null
                || (ca && t.getRatioFrom() != null && t.getRatioTo() != null);
            if (!hasFigure) errors.add("no quantity or amount stated");
            if (t.getType().movesUnits() && !isCash(t) && (t.getAsset() == null || !t.getAsset().identified()))
                errors.add("asset not identified");
        }
        for (BigDecimal v : new BigDecimal[]{t.getQuantity(), t.getUnitPrice(), t.getGrossAmount(), t.getFees(), t.getTaxes(), t.getNetAmount()})
            if (v != null && v.signum() < 0) { errors.add("negative figure (direction is carried by the type)"); break; }
        return errors;
    }

    public List<String> validate(NormalizedHolding h) {
        List<String> errors = new ArrayList<>();
        if (h.getAccount() == null || h.getAccount().institution() == null) errors.add("institution missing");
        if (h.getAsset() == null || !h.getAsset().identified()) errors.add("asset not identified");
        if (h.getQuantity() == null || h.getQuantity().signum() < 0) errors.add("quantity missing or negative");
        if (h.getSourceType() == null) errors.add("source type missing");
        return errors;
    }

    private static boolean isCash(NormalizedTransaction t) { return t.getAsset() == null; }
}
