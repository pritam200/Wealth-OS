package com.marketai.gmail.service;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.gmail.ledger.FinancialEventLedger;
import com.marketai.gmail.parser.ParsedEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HeldItemRecheckTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmailReviewItemRepository items = mock(EmailReviewItemRepository.class);
    private final EmailLLMParserService parser = mock(EmailLLMParserService.class);
    private final ParsedEmailImporter importer = mock(ParsedEmailImporter.class);
    private final FinancialEventLedger ledger = mock(FinancialEventLedger.class);
    private final UserRepository users = mock(UserRepository.class);
    private final HeldItemRecheck recheck = new HeldItemRecheck(jdbc, items, parser, importer, ledger, users);

    private static EmailReviewItem held(long id, int index) {
        EmailReviewItem i = new EmailReviewItem();
        i.setId(id);
        i.setUserId(2L);
        i.setGmailMessageId("m1");
        i.setItemIndex(index);
        i.setConfidence(new BigDecimal("0.95"));
        i.setExtractedFields("{\"evidence\":\"line " + id + "\"}");
        return i;
    }

    @Test
    @DisplayName("A held line that now passes is booked and closed; one that still fails stays held")
    void booksWhatNowPasses() throws Exception {
        User user = new User();
        user.setId(2L);
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
            .thenReturn(List.of("From: Bank\nSubject: Statement\n\nline 1 179.00 DR\nline 2 99.00 DR"));
        EmailReviewItem passes = held(1, 1_000_001), fails = held(2, 1_000_002);
        ParsedEmail pe = ParsedEmail.builder().type(ParsedEmail.Type.EXPENSE).amount(new BigDecimal("179.00")).build();
        when(parser.recheckHeld(eq(passes.getExtractedFields()), contains("line 1"), any(), eq(true), eq(0.95), anyMap())).thenReturn(pe);

        int booked = recheck.recheck(new ArrayList<>(List.of(fails, passes)));

        assertThat(booked).isEqualTo(1);
        verify(importer).importParsedEmail(2L, user, pe, "m1", null);
        assertThat(passes.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
        assertThat(fails.getStatus()).isEqualTo(ReviewStatus.PENDING);
        verify(ledger).onReviewDecision(2L, "m1", 1_000_001, true, HeldItemRecheck.NOTE);
    }
}
