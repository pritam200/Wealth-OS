package com.marketai.actions.service;

import com.marketai.actions.dto.ActionItemDto;
import com.marketai.actions.dto.ActionUpdateRequest;
import com.marketai.actions.entity.ActionItem;
import com.marketai.actions.entity.ActionStatus;
import com.marketai.actions.repository.ActionItemRepository;
import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the user's disposition of recommended actions.
 *
 * This service deliberately has no dependency on PortfolioService, HoldingRepository or any
 * other write path into the portfolio. Marking an action EXECUTED asserts that the user placed
 * the trade with their broker — the holding only changes when the resulting Transaction is
 * booked through the portfolio endpoints (or imported from the broker's email). Wiring these
 * together would let advice silently mutate the ledger, which is exactly what the ledger being
 * the source of truth is meant to prevent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActionItemService {

    private final ActionItemRepository repo;

    /** Upsert on (user, actionType, symbol, actionDate) — the queue is recomputed on every
     *  load, so the first interaction with a recommendation creates its row. */
    @Transactional
    public ActionItemDto record(User user, ActionUpdateRequest req) {
        if (req.getSymbol() == null || req.getSymbol().trim().isEmpty()
                || req.getActionType() == null || req.getActionType().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actionType and symbol are required");
        }
        if (req.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        if (req.getStatus() == ActionStatus.SNOOZED && req.getSnoozedUntil() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "snoozedUntil is required when snoozing");
        }

        LocalDate date = req.getActionDate() != null ? req.getActionDate() : LocalDate.now();
        String symbol = req.getSymbol().trim();
        String type = req.getActionType().trim();

        ActionItem item = repo.findByUser_IdAndActionTypeAndSymbolAndActionDate(user.getId(), type, symbol, date)
            .orElseGet(() -> ActionItem.builder()
                .user(user).actionType(type).symbol(symbol).actionDate(date)
                .build());

        item.setName(req.getName() != null ? req.getName() : item.getName());
        item.setAssetType(req.getAssetType() != null ? req.getAssetType() : item.getAssetType());
        item.setAmount(req.getAmount() != null ? req.getAmount() : item.getAmount());
        item.setQuantity(req.getQuantity() != null ? req.getQuantity() : item.getQuantity());
        item.setStatus(req.getStatus());
        if (req.getNote() != null) item.setNote(req.getNote());
        // Clearing on any non-snooze status keeps a stale future date from hiding an action
        // the user has since executed or skipped.
        item.setSnoozedUntil(req.getStatus() == ActionStatus.SNOOZED ? req.getSnoozedUntil() : null);

        return ActionItemDto.from(repo.save(item));
    }

    @Transactional
    public ActionItemDto updateNote(User user, Long id, String note) {
        ActionItem item = repo.findByIdAndUser_Id(id, user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found"));
        item.setNote(note);
        return ActionItemDto.from(repo.save(item));
    }

    /** Every action the user has touched today, plus any still-active snooze from an earlier
     *  day, so the UI can mark today's recomputed queue with what's already been dealt with. */
    public List<ActionItemDto> listForToday(Long userId) {
        LocalDate today = LocalDate.now();
        List<ActionItemDto> out = new ArrayList<>();
        for (ActionItem a : repo.findByUser_IdAndActionDate(userId, today)) {
            out.add(ActionItemDto.from(a));
        }
        for (ActionItem a : repo.findByUser_IdOrderByUpdatedAtDesc(userId)) {
            if (a.getStatus() == ActionStatus.SNOOZED
                    && a.getSnoozedUntil() != null && !a.getSnoozedUntil().isBefore(today)
                    && !today.equals(a.getActionDate())) {
                out.add(ActionItemDto.from(a));
            }
        }
        return out;
    }
}
