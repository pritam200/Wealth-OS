package com.marketai.mf.repository;

import com.marketai.mf.entity.CasBalanceSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real persistence context so the entity mapping (nullable scheme_code, precision/scale on
 * stated_units) and the "most recent per folio/scheme" queries are checked against an actual
 * schema, not a mocked repository.
 */
@DataJpaTest
class CasBalanceSnapshotRepositoryTest {

    @Autowired
    private CasBalanceSnapshotRepository repo;

    private CasBalanceSnapshot saved(Long userId, String folio, String schemeCode, LocalDate asOfDate, String units) {
        return repo.saveAndFlush(CasBalanceSnapshot.builder()
            .userId(userId).folio(folio).schemeCode(schemeCode).asOfDate(asOfDate)
            .statedUnits(new BigDecimal(units)).sourceEmailId("msg-1").build());
    }

    @Test
    @DisplayName("a snapshot with no scheme code (unresolved AMFI match) still persists — matchable by folio alone")
    void persistsWithNullSchemeCode() {
        CasBalanceSnapshot row = saved(1L, "F1", null, LocalDate.of(2026, 1, 31), "1234.5670");

        assertThat(row.getId()).isNotNull();
        assertThat(row.getSchemeCode()).isNull();
        assertThat(row.getStatedUnits()).isEqualByComparingTo("1234.5670");
    }

    @Test
    @DisplayName("findTopByUserIdAndFolioOrderByAsOfDateDesc returns the most recent statement for that folio")
    void findsMostRecentByFolio() {
        saved(1L, "F1", "HDFC001", LocalDate.of(2026, 1, 31), "1000.0000");
        saved(1L, "F1", "HDFC001", LocalDate.of(2026, 2, 28), "1050.0000");
        saved(1L, "F2", "ICICI002", LocalDate.of(2026, 2, 28), "500.0000");

        Optional<CasBalanceSnapshot> latest = repo.findTopByUserIdAndFolioOrderByAsOfDateDesc(1L, "F1");

        assertThat(latest).isPresent();
        assertThat(latest.get().getAsOfDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(latest.get().getStatedUnits()).isEqualByComparingTo("1050.0000");
    }

    @Test
    @DisplayName("findByUserIdOrderByAsOfDateDesc is scoped to the user and ordered newest first")
    void findsAllOrderedByDateDescScopedToUser() {
        saved(1L, "F1", "HDFC001", LocalDate.of(2026, 1, 31), "1000.0000");
        saved(1L, "F1", "HDFC001", LocalDate.of(2026, 2, 28), "1050.0000");
        saved(2L, "F9", "OTHER001", LocalDate.of(2026, 3, 15), "999.0000");

        var rows = repo.findByUserIdOrderByAsOfDateDesc(1L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getAsOfDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(rows.get(1).getAsOfDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }
}
