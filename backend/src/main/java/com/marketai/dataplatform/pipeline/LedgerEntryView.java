package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.domain.TransactionType;
import com.marketai.dataplatform.domain.TxnStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the matcher needs to know about an existing canonical transaction. */
public record LedgerEntryView(Long id, Long accountId, boolean accountSpecific, Long assetId, TransactionType type,
                              TxnStatus status, LocalDate date, BigDecimal quantity, BigDecimal grossAmount,
                              BigDecimal netAmount, BigDecimal ratioFrom, BigDecimal ratioTo, List<Source> sources) {

    /** One source that has reported the entry. */
    public record Source(SourceType type, String provider, String reference) {}
}
