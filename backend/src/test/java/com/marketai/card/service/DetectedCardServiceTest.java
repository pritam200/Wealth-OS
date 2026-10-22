package com.marketai.card.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.entity.DetectedCard;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.repository.DetectedCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DetectedCardServiceTest {

    private DetectedCardRepository detectedRepo;
    private CreditCardRepository cardRepo;
    private CardStatementIngestService ingest;
    private DetectedCardService service;
    private final List<DetectedCard> rows = new ArrayList<>();

    @BeforeEach
    void setup() {
        detectedRepo = mock(DetectedCardRepository.class);
        cardRepo = mock(CreditCardRepository.class);
        ingest = mock(CardStatementIngestService.class);
        service = new DetectedCardService(detectedRepo, cardRepo, ingest, new ObjectMapper().registerModule(new JavaTimeModule()));
        when(detectedRepo.findByUserId(1L)).thenAnswer(i -> rows);
        when(detectedRepo.save(any())).thenAnswer(i -> {
            DetectedCard d = i.getArgument(0);
            if (d.getId() == null) { d.setId((long) rows.size() + 1); rows.add(d); }
            return d;
        });
        when(detectedRepo.findByIdAndUserId(anyLong(), eq(1L))).thenAnswer(i -> rows.stream().filter(d -> d.getId().equals(i.getArgument(0))).findFirst());
        when(cardRepo.save(any())).thenAnswer(i -> { CreditCard c = i.getArgument(0); c.setId(9L); return c; });
    }

    private static CardStatementFacts facts(String last4) {
        return new CardStatementFacts("HDFC Bank", last4, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 4),
            new BigDecimal("5200"), new BigDecimal("260"), null, new BigDecimal("200000"), null, null,
            List.of(new CardStatementFacts.Line("SWIGGY", new BigDecimal("420"), LocalDate.of(2026, 10, 7), "INR", true, "Food", "SWIGGY 420")));
    }

    @Test
    @DisplayName("Statements for an unsaved card are kept, once each, and filed onto the card when it is added")
    void holdThenAdd() {
        service.holdStatement(1L, facts("4286"), "mail-1");
        service.holdStatement(1L, facts("4286"), "mail-1");
        service.holdStatement(1L, facts("4286"), "mail-2");

        assertThat(rows).hasSize(1);
        assertThat(service.pendingStatementCount(rows.get(0))).isEqualTo(2);

        CreditCard card = service.add(1L, rows.get(0).getId(), "HDFC MoneyBack+");

        assertThat(card.getLastFour()).isEqualTo("4286");
        assertThat(card.getIssuer()).isEqualTo("HDFC Bank");
        verify(ingest).file(eq(1L), eq(card), argThat(f -> f.lines().size() == 1 && f.totalDue().compareTo(new BigDecimal("5200")) == 0), eq("mail-1"));
        verify(ingest).file(eq(1L), eq(card), any(), eq("mail-2"));
        assertThat(rows.get(0).getStatus()).isEqualTo(DetectedCard.Status.ADDED);
    }

    @Test
    @DisplayName("Linking fills in a saved card's missing last four digits; a dismissed card is not asked about again")
    void linkAndDismiss() {
        CreditCard saved = CreditCard.builder().id(3L).userId(1L).name("Regalia Gold").issuer("HDFC").build();
        when(cardRepo.findByIdAndUserId(3L, 1L)).thenReturn(Optional.of(saved));
        service.holdStatement(1L, facts("4932"), "mail-3");
        service.link(1L, rows.get(0).getId(), 3L);
        assertThat(saved.getLastFour()).isEqualTo("4932");

        service.holdStatement(1L, facts("1111"), "mail-4");
        DetectedCard other = rows.get(1);
        service.dismiss(1L, other.getId());
        service.holdStatement(1L, facts("1111"), "mail-5");
        assertThat(rows).hasSize(2);
        assertThat(other.getStatus()).isEqualTo(DetectedCard.Status.DISMISSED);
    }
}
