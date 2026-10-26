package com.marketai.ai.review.service;

import com.marketai.ai.review.dto.ReviewDecisionRequest;
import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewRule;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.ai.review.repository.ReviewRuleRepository;
import com.marketai.auth.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewRuleServiceTest {

    private static final Long USER_ID = 7L;
    private ReviewRuleRepository ruleRepo;
    private EmailReviewItemRepository itemRepo;
    private EmailReviewService reviewService;
    private ReviewRuleService service;
    private User user;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(ReviewRuleRepository.class);
        itemRepo = mock(EmailReviewItemRepository.class);
        reviewService = mock(EmailReviewService.class);
        service = new ReviewRuleService(ruleRepo, itemRepo, reviewService);
        user = new User();
        user.setId(USER_ID);
        when(ruleRepo.findByUserIdAndKindKey(any(), any())).thenReturn(Optional.empty());
    }

    private static EmailReviewItem item(long id, String sender, String subject, String reason) {
        EmailReviewItem i = EmailReviewItem.builder().id(id).userId(USER_ID).sender(sender).subject(subject)
            .proposedType("STOCK_BUY").reviewReason(reason).build();
        i.setStatus(ReviewStatus.PENDING);
        return i;
    }

    @Test
    @DisplayName("the same question about each week's copy of an email is one kind; another kind of email is not")
    void kindIgnoresDatesAndReferences() {
        String reason = "The stock symbol, quantity or price could not be confirmed.";
        EmailReviewItem oct13 = item(1, "\"m.Stock\" <info@mstock.com>", "Contract Note as on Oct 13 2026 Ref No.:(3986423) of MA74685", reason);
        EmailReviewItem oct19 = item(2, "info@mstock.com", "Contract Note as on Oct 19 2026 Ref No.:(4092663) of MA74685", reason);
        EmailReviewItem rof = item(3, "info@mstock.com", "Weekly Register of Funds (ROF) dated Oct 17 2026 Ref No.:(20)", reason);

        assertThat(ReviewRuleService.kindKey(oct13)).isEqualTo(ReviewRuleService.kindKey(oct19));
        assertThat(ReviewRuleService.kindKey(oct13)).isNotEqualTo(ReviewRuleService.kindKey(rof));
        assertThat(ReviewRuleService.kindKey(item(4, "info@mstock.com", oct13.getSubject(), "No amount could be read.")))
            .isNotEqualTo(ReviewRuleService.kindKey(oct13));
    }

    @Test
    @DisplayName("a reject is remembered and applied to the other pending items of its kind only")
    void rejectAppliesToSameKind() {
        String reason = "No amount could be read for this transaction.";
        EmailReviewItem decided = item(1, "services@cdslindia.co.in", "Corporate action on 12-10-2026", reason);
        EmailReviewItem same = item(2, "services@cdslindia.co.in", "Corporate action on 19-10-2026", reason);
        EmailReviewItem other = item(3, "alerts@hdfcbank.net", "Corporate action on 19-10-2026", reason);
        when(itemRepo.findByUserIdAndStatusOrderByCreatedAtDesc(USER_ID, ReviewStatus.PENDING)).thenReturn(List.of(same, other));
        when(ruleRepo.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenAnswer(inv -> List.of(ReviewRule.builder()
            .userId(USER_ID).kindKey(ReviewRuleService.kindKey(decided)).decision("REJECT").build()));

        int also = service.remember(user, decided, "REJECT");

        assertThat(also).isEqualTo(1);
        ArgumentCaptor<ReviewDecisionRequest> req = ArgumentCaptor.forClass(ReviewDecisionRequest.class);
        verify(reviewService).decide(eq(user), eq(2L), req.capture());
        verify(reviewService, never()).decide(any(), eq(3L), any());
        assertThat(req.getValue().getDecision()).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("an item the remembered accept can't book stays in the queue without stopping the rest")
    void failedApplyLeavesItemPending() {
        String reason = "Sender could not be verified";
        EmailReviewItem a = item(1, "x@broker.in", "Trade", reason), b = item(2, "x@broker.in", "Trade", reason);
        when(itemRepo.findByUserIdAndStatusOrderByCreatedAtDesc(USER_ID, ReviewStatus.PENDING)).thenReturn(List.of(a, b));
        when(ruleRepo.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(ReviewRule.builder()
            .userId(USER_ID).kindKey(ReviewRuleService.kindKey(a)).decision("ACCEPT").build()));
        when(reviewService.decide(any(), eq(1L), any())).thenThrow(new IllegalStateException("no figures"));

        assertThat(service.apply(user)).isEqualTo(1);
        verify(reviewService).decide(any(), eq(2L), any());
    }
}
