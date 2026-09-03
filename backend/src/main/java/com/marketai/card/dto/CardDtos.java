package com.marketai.card.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class CardDtos {

    @Data
    public static class CardRequest {
        private String name;
        private String issuer;
        private String network;
        private String lastFour;
        private BigDecimal annualFee;
        private Integer pointsBalance;
        private BigDecimal pointValue;
        private Integer billingDay;
        private Integer dueDay;
        private String benefits;
        private String bestFor;
        private Map<String, BigDecimal> rewardRates;
        /** if set, pre-fill everything from the built-in catalog */
        private String catalogName;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CardResponse {
        private Long id;
        private String name, issuer, network, lastFour;
        private BigDecimal annualFee, pointValue;
        private Integer pointsBalance, billingDay, dueDay;
        private String benefits, bestFor;
        private Map<String, BigDecimal> rewardRates;
        private BigDecimal pointsCashValue; // pointsBalance * pointValue
        private BigDecimal currentDue;
        private java.time.LocalDate currentDueDate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CatalogEntry {
        private String name, issuer, network, bestFor;
        private BigDecimal annualFee, pointValue;
        private List<String> benefits;
        private Map<String, BigDecimal> rewardRates;
    }

    @Data
    public static class RecommendRequest {
        private String category;
        private BigDecimal amount;
        private String merchant; // optional free text
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendResult {
        private Long cardId;               // null when suggestion is from the catalog
        private String cardName;
        private String issuer;
        private String network;
        private BigDecimal rewardRate;     // %
        private BigDecimal expectedReward; // ₹
        private String reason;
        private boolean best;
        private boolean fromCatalog;       // true = a card you could get, not one you own
        private boolean eligible;          // false when card cannot be used for this payment method
        private String ineligibilityNote;  // why the card is ineligible (null when eligible)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PointsTip {
        private Long cardId;
        private String cardName;
        private Integer pointsBalance;
        private BigDecimal cashValue;
        private BigDecimal bestValue;      // best possible redemption value
        private String recommendation;
    }
}
