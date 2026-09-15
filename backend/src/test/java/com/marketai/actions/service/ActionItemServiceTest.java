package com.marketai.actions.service;

import com.marketai.actions.dto.ActionItemDto;
import com.marketai.actions.dto.ActionUpdateRequest;
import com.marketai.actions.entity.ActionItem;
import com.marketai.actions.entity.ActionStatus;
import com.marketai.actions.repository.ActionItemRepository;
import com.marketai.auth.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionItemServiceTest {

    private static final Long USER_ID = 7L;

    private ActionItemRepository repo;
    private ActionItemService service;
    private User user;

    @BeforeEach
    void setup() {
        repo = mock(ActionItemRepository.class);
        service = new ActionItemService(repo);
        user = new User();
        user.setId(USER_ID);
        when(repo.save(any(ActionItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ActionUpdateRequest req(String type, String symbol, ActionStatus status) {
        ActionUpdateRequest r = new ActionUpdateRequest();
        r.setActionType(type);
        r.setSymbol(symbol);
        r.setStatus(status);
        return r;
    }

    @Test
    void executingAnAction_recordsStatusOnly_neverTouchesAnyHolding() {
        // The whole point of this service: ActionItemService is constructed with the action
        // repository alone. There is no holding/portfolio/transaction collaborator it could
        // write through, so advice cannot mutate the ledger even by accident.
        when(repo.findByUser_IdAndActionTypeAndSymbolAndActionDate(eq(USER_ID), eq("BUY"), eq("HDFCBANK"), any()))
            .thenReturn(Optional.empty());

        ActionUpdateRequest r = req("BUY", "HDFCBANK", ActionStatus.EXECUTED);
        r.setAmount(new BigDecimal("25000"));
        ActionItemDto dto = service.record(user, r);

        assertThat(dto.getStatus()).isEqualTo(ActionStatus.EXECUTED);
        assertThat(dto.getSymbol()).isEqualTo("HDFCBANK");
        assertThat(dto.getAmount()).isEqualByComparingTo("25000");

        ArgumentCaptor<ActionItem> captor = ArgumentCaptor.forClass(ActionItem.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUser().getId()).isEqualTo(USER_ID);
        verifyNoMoreInteractions(ignoreStubs(repo));
    }

    @Test
    void actingTwiceOnTheSameRecommendation_updatesOneRowRatherThanDuplicating() {
        ActionItem existing = ActionItem.builder()
            .id(3L).user(user).actionType("BUY").symbol("TITAN")
            .actionDate(LocalDate.now()).status(ActionStatus.PENDING).build();
        when(repo.findByUser_IdAndActionTypeAndSymbolAndActionDate(eq(USER_ID), eq("BUY"), eq("TITAN"), any()))
            .thenReturn(Optional.of(existing));

        service.record(user, req("BUY", "TITAN", ActionStatus.SKIPPED));

        ArgumentCaptor<ActionItem> captor = ArgumentCaptor.forClass(ActionItem.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(3L);   // same row, not a new one
        assertThat(captor.getValue().getStatus()).isEqualTo(ActionStatus.SKIPPED);
    }

    @Test
    void snoozeRequiresADate_soAnActionCannotVanishIndefinitely() {
        assertThatThrownBy(() -> service.record(user, req("BUY", "INFY", ActionStatus.SNOOZED)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("snoozedUntil");
    }

    @Test
    void movingOffSnooze_clearsTheSnoozeDate() {
        ActionItem existing = ActionItem.builder()
            .id(9L).user(user).actionType("REDUCE").symbol("WIPRO")
            .actionDate(LocalDate.now()).status(ActionStatus.SNOOZED)
            .snoozedUntil(LocalDate.now().plusDays(30)).build();
        when(repo.findByUser_IdAndActionTypeAndSymbolAndActionDate(eq(USER_ID), eq("REDUCE"), eq("WIPRO"), any()))
            .thenReturn(Optional.of(existing));

        service.record(user, req("REDUCE", "WIPRO", ActionStatus.EXECUTED));

        ArgumentCaptor<ActionItem> captor = ArgumentCaptor.forClass(ActionItem.class);
        verify(repo).save(captor.capture());
        // A leftover future snooze date would otherwise keep hiding an executed action.
        assertThat(captor.getValue().getSnoozedUntil()).isNull();
    }

    @Test
    void symbolAndStatusAreRequired() {
        assertThatThrownBy(() -> service.record(user, req("BUY", "  ", ActionStatus.EXECUTED)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.record(user, req("BUY", "INFY", null)))
            .isInstanceOf(ResponseStatusException.class);
    }
}
