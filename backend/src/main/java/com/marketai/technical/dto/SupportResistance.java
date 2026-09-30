package com.marketai.technical.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nearest and next levels either side of the price. A side with no level meeting the rules is
 * reported as NO_RELIABLE_LEVEL with the level fields null — never filled with a guess.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupportResistance {
    public static final String OK = "OK";
    public static final String NO_RELIABLE_LEVEL = "NO_RELIABLE_LEVEL";

    private PriceLevel nearestSupport;
    private PriceLevel nextSupport;
    private PriceLevel nearestResistance;
    private PriceLevel nextResistance;
    private String supportStatus;
    private String resistanceStatus;
    private String method;
}
