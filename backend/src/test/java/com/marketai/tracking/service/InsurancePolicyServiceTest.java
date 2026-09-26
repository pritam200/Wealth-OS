package com.marketai.tracking.service;

import com.marketai.auth.entity.User;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.reminder.dto.ReminderResponse;
import com.marketai.reminder.service.ReminderService;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import com.marketai.tracking.dto.InsurancePolicyRequest;
import com.marketai.tracking.dto.InsurancePolicyResponse;
import com.marketai.tracking.entity.InsurancePolicy;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.InsurancePolicyRepository;
import com.marketai.tracking.repository.LoanRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CRUD + reminder-wiring coverage for InsurancePolicy, matching FdRenewalTest's style: a mocked
 * repository behind the service, plus a check that ReminderService (the same derive-from-data
 * infrastructure FD/RD maturities use) surfaces a premium due soon.
 */
class InsurancePolicyServiceTest {

    private InsurancePolicyRepository repo;
    private InsurancePolicyService service;

    @BeforeEach
    void setup() {
        repo = mock(InsurancePolicyRepository.class);
        service = new InsurancePolicyService(repo);
        when(repo.save(any(InsurancePolicy.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private InsurancePolicyRequest req() {
        InsurancePolicyRequest r = new InsurancePolicyRequest();
        r.setPolicyType(InsurancePolicy.PolicyType.TERM);
        r.setInsurer("HDFC Life");
        r.setPolicyNumber("POL123");
        r.setSumAssured(new BigDecimal("5000000"));
        r.setPremiumAmount(new BigDecimal("12000"));
        r.setPremiumFrequency(InsurancePolicy.PremiumFrequency.ANNUAL);
        r.setNextPremiumDueDate(LocalDate.now().plusDays(5));
        r.setStartDate(LocalDate.now().minusYears(1));
        r.setEndDate(LocalDate.now().plusYears(19));
        r.setNotes("Primary term cover");
        return r;
    }

    @Test
    @DisplayName("addPolicy persists and returns a response with computed daysToNextPremium")
    void addPolicyPersists() {
        InsurancePolicyResponse resp = service.addPolicy(1L, req(), mock(User.class));
        assertThat(resp.getInsurer()).isEqualTo("HDFC Life");
        assertThat(resp.getPolicyType()).isEqualTo(InsurancePolicy.PolicyType.TERM);
        assertThat(resp.getPremiumAmount()).isEqualByComparingTo("12000");
        assertThat(resp.getDaysToNextPremium()).isEqualTo(5L);
        assertThat(resp.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("listPolicies maps every stored row")
    void listPolicies() {
        InsurancePolicy p = InsurancePolicy.builder()
            .id(10L).policyType(InsurancePolicy.PolicyType.HEALTH).insurer("Star Health")
            .premiumAmount(new BigDecimal("8000")).premiumFrequency(InsurancePolicy.PremiumFrequency.ANNUAL)
            .status("ACTIVE").build();
        when(repo.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Arrays.asList(p));

        List<InsurancePolicyResponse> result = service.listPolicies(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getInsurer()).isEqualTo("Star Health");
    }

    @Test
    @DisplayName("updatePolicy only overwrites supplied fields")
    void updatePolicyPartial() {
        InsurancePolicy existing = InsurancePolicy.builder()
            .id(20L).policyType(InsurancePolicy.PolicyType.MOTOR).insurer("ICICI Lombard")
            .premiumAmount(new BigDecimal("3000")).premiumFrequency(InsurancePolicy.PremiumFrequency.ANNUAL)
            .status("ACTIVE").build();
        when(repo.findByIdAndUserId(20L, 1L)).thenReturn(Optional.of(existing));

        InsurancePolicyRequest partial = new InsurancePolicyRequest();
        partial.setPremiumAmount(new BigDecimal("3300"));

        InsurancePolicyResponse resp = service.updatePolicy(20L, 1L, partial);

        assertThat(resp.getPremiumAmount()).isEqualByComparingTo("3300");
        assertThat(resp.getInsurer()).isEqualTo("ICICI Lombard"); // untouched
    }

    @Test
    @DisplayName("deletePolicy 404s when the policy isn't the caller's")
    void deleteMissingThrows() {
        when(repo.existsByIdAndUserId(99L, 1L)).thenReturn(false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> service.deletePolicy(99L, 1L));
    }

    @Test
    @DisplayName("ReminderService surfaces a premium due within 15 days")
    void reminderServiceSurfacesPremiumDueSoon() {
        InsurancePolicy dueSoon = InsurancePolicy.builder()
            .id(1L).policyType(InsurancePolicy.PolicyType.TERM).insurer("HDFC Life")
            .premiumAmount(new BigDecimal("12000")).premiumFrequency(InsurancePolicy.PremiumFrequency.ANNUAL)
            .nextPremiumDueDate(LocalDate.now().plusDays(3)).status("ACTIVE").build();
        InsurancePolicy farOut = InsurancePolicy.builder()
            .id(2L).policyType(InsurancePolicy.PolicyType.HEALTH).insurer("Star Health")
            .premiumAmount(new BigDecimal("8000")).premiumFrequency(InsurancePolicy.PremiumFrequency.ANNUAL)
            .nextPremiumDueDate(LocalDate.now().plusDays(90)).status("ACTIVE").build();

        InsurancePolicyRepository insuranceRepoMock = mock(InsurancePolicyRepository.class);
        when(insuranceRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Arrays.asList(dueSoon, farOut));

        FixedDepositRepository fdRepoMock = mock(FixedDepositRepository.class);
        RecurringDepositRepository rdRepoMock = mock(RecurringDepositRepository.class);
        CreditCardRepository cardRepoMock = mock(CreditCardRepository.class);
        LoanRepository loanRepoMock = mock(LoanRepository.class);
        RecurringInvestmentRepository riRepoMock = mock(RecurringInvestmentRepository.class);
        when(fdRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(rdRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(cardRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(loanRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(riRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());

        ReminderService reminderService = new ReminderService(fdRepoMock, rdRepoMock, cardRepoMock,
            loanRepoMock, riRepoMock, insuranceRepoMock);

        List<ReminderResponse> reminders = reminderService.getReminders(1L);

        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).getType()).isEqualTo("INSURANCE_PREMIUM");
        assertThat(reminders.get(0).getDaysUntil()).isEqualTo(3L);
    }
}
