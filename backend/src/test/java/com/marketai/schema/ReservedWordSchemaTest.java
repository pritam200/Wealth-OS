package com.marketai.schema;

import com.marketai.auth.entity.User;
import com.marketai.market.entity.MarketIndex;
import com.marketai.market.repository.MarketIndexRepository;
import com.marketai.tracking.entity.OtherAsset;
import com.marketai.tracking.repository.OtherAssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards two tables that map a column literally named {@code value} — a reserved word in H2.
 *
 * Before the identifiers were quoted, Hibernate's DDL for {@code market_indices} and
 * {@code other_assets} failed with "Syntax error ... expected identifier". It surfaced only as a
 * WARN and the run continued, so no test failed — but the tables did not exist, and any JPA
 * slice test touching either entity would have failed with "table not found". PostgreSQL accepts
 * the unquoted name, so production was never affected and the problem was invisible there.
 *
 * These tests fail loudly if the quoting is ever removed.
 */
@DataJpaTest
class ReservedWordSchemaTest {

    @Autowired private TestEntityManager em;
    @Autowired private OtherAssetRepository otherAssetRepo;
    @Autowired private MarketIndexRepository marketIndexRepo;

    @Test
    @DisplayName("other_assets creates and round-trips its `value` column")
    void otherAssetPersists() {
        User user = new User();
        user.setName("Test");
        user.setEmail("schema-test@example.com");
        user.setPassword("x");
        em.persistAndFlush(user);

        OtherAsset asset = new OtherAsset();
        asset.setUser(user);
        asset.setCategory("ppf");
        asset.setName("PPF Account");
        asset.setValue(new BigDecimal("125000.00"));

        OtherAsset saved = otherAssetRepo.saveAndFlush(asset);

        assertThat(otherAssetRepo.findById(saved.getId()))
            .get().extracting(OtherAsset::getValue)
            .isEqualTo(new BigDecimal("125000.00"));
    }

    @Test
    @DisplayName("market_indices creates and round-trips its `value` column")
    void marketIndexPersists() {
        MarketIndex index = new MarketIndex();
        index.setSymbol("NIFTY50");
        index.setName("Nifty 50");
        index.setValue(new BigDecimal("24500.75"));

        MarketIndex saved = marketIndexRepo.saveAndFlush(index);

        assertThat(marketIndexRepo.findById(saved.getId()))
            .get().extracting(MarketIndex::getValue)
            .isEqualTo(new BigDecimal("24500.75"));
    }
}
