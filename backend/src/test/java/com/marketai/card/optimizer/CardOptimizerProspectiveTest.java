package com.marketai.card.optimizer;

import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CreditCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Should I get this card?" must be answered with the same net-value rigor as "should I keep
 * this card?" — computed against the user's own spend, not a catalog headline rate.
 */
class CardOptimizerProspectiveTest {

    private CreditCardRepository cardRepository;
    private SpendAggregator spendAggregator;
    private CardOptimizerService optimizer;

    @BeforeEach
    void setup() {
        cardRepository = mock(CreditCardRepository.class);
        spendAggregator = mock(SpendAggregator.class);
        optimizer = new CardOptimizerService(cardRepository, spendAggregator);
    }

    private SpendAggregator.SpendProfile trustworthySpend(String category, String projectedAnnual) {
        SpendAggregator.CategorySpend cs = SpendAggregator.CategorySpend.builder()
            .category(category)
            .observedAmount(new BigDecimal("10000"))
            .projectedAnnual(new BigDecimal(projectedAnnual))
            .txnCount(20).inferred(true).build();
        return SpendAggregator.SpendProfile.builder()
            .days(90).observedSpend(new BigDecimal("10000"))
            .projectedAnnualSpend(new BigDecimal(projectedAnnual))
            .annualizationBasis("test").coverage(new BigDecimal("0.90"))
            .byCategory(List.of(cs)).note("test profile").build();
    }

    @Test
    void unknownCatalogCardNameIsRejected() {
        when(spendAggregator.aggregate(1L)).thenReturn(trustworthySpend("Dining", "40000"));
        assertThatThrownBy(() -> optimizer.analyseCatalogCard(1L, "Not A Real Card"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void prospectiveVerdictHasNoCardIdSinceTheUserDoesNotOwnItYet() {
        when(spendAggregator.aggregate(1L)).thenReturn(trustworthySpend("Dining", "40000"));
        CardOptimizerService.ProspectiveResult result = optimizer.analyseCatalogCard(1L, "HDFC Millennia");

        assertThat(result.getVerdict().getCardId()).isNull();
        assertThat(result.getVerdict().getCardName()).isEqualTo("HDFC Millennia");
        // HDFC Millennia earns 5% on Dining with no cap relevant here at this spend level.
        assertThat(result.getVerdict().getProjectedAnnualRewards())
            .isEqualByComparingTo(new BigDecimal("2000.00")); // 5% of 40000
    }

    @Test
    void catalogRankingExcludesCardsTheUserAlreadyOwns() {
        when(spendAggregator.aggregate(1L)).thenReturn(trustworthySpend("Dining", "40000"));
        CreditCard owned = CreditCard.builder().id(9L).userId(1L).name("HDFC Millennia")
            .annualFee(BigDecimal.ZERO).rewardRates(new LinkedHashMap<>()).build();
        when(cardRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(owned));

        CardOptimizerService.CatalogRanking ranking = optimizer.analyseAllCatalogCards(1L);

        assertThat(ranking.getCandidates())
            .extracting(CardOptimizerService.CardVerdict::getCardName)
            .doesNotContain("HDFC Millennia");
        assertThat(ranking.getCandidates()).isNotEmpty();
    }

    @Test
    void catalogRankingSortsByNetValueDescending() {
        when(spendAggregator.aggregate(1L)).thenReturn(trustworthySpend("Dining", "40000"));
        when(cardRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        CardOptimizerService.CatalogRanking ranking = optimizer.analyseAllCatalogCards(1L);
        List<BigDecimal> netValues = ranking.getCandidates().stream()
            .map(CardOptimizerService.CardVerdict::getNetValue).toList();

        for (int i = 1; i < netValues.size(); i++) {
            assertThat(netValues.get(i - 1)).isGreaterThanOrEqualTo(netValues.get(i));
        }
    }

    @Test
    void lowCoverageSpendMakesProspectiveVerdictsAdvisoryOnly() {
        SpendAggregator.SpendProfile weak = SpendAggregator.SpendProfile.builder()
            .days(90).observedSpend(new BigDecimal("500"))
            .projectedAnnualSpend(new BigDecimal("2000"))
            .annualizationBasis("test").coverage(new BigDecimal("0.10"))
            .byCategory(List.of()).note("weak profile").build();
        when(spendAggregator.aggregate(1L)).thenReturn(weak);

        CardOptimizerService.ProspectiveResult result = optimizer.analyseCatalogCard(1L, "HDFC Millennia");
        assertThat(result.isTrustworthy()).isFalse();
        assertThat(result.getVerdict().getVerdict()).isEqualTo(CardOptimizerService.Verdict.UNKNOWN);
    }
}
