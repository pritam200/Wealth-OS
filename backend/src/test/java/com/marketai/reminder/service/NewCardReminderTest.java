package com.marketai.reminder.service;

import com.marketai.card.entity.DetectedCard;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.service.DetectedCardService;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.InsurancePolicyRepository;
import com.marketai.tracking.repository.LoanRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewCardReminderTest {

    @Test
    @DisplayName("a card found in email but not saved yet is a reminder to add it")
    void detectedCardIsAReminder() {
        ReminderService service = new ReminderService(mock(FixedDepositRepository.class), mock(RecurringDepositRepository.class),
            mock(CreditCardRepository.class), mock(LoanRepository.class), mock(RecurringInvestmentRepository.class),
            mock(InsurancePolicyRepository.class));
        DetectedCardService detected = mock(DetectedCardService.class);
        DetectedCard d = DetectedCard.builder().id(7L).userId(2L).issuer("IndusInd Bank").lastFour("1234")
            .status(DetectedCard.Status.PENDING).build();
        when(detected.pendingFor(2L)).thenReturn(List.of(d));
        when(detected.pendingStatementCount(d)).thenReturn(2);
        service.setDetectedCards(detected);

        assertThat(service.getReminders(2L)).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("NEW_CARD");
            assertThat(r.getTitle()).contains("IndusInd Bank ••1234").contains("Add it");
            assertThat(r.getSubtitle()).startsWith("2 statement(s) waiting");
        });
    }
}
