package com.marketai.gmail.service;

import com.marketai.gmail.entity.DepositoryMovement;
import com.marketai.gmail.parser.DepositoryMovementParser;
import com.marketai.gmail.repository.DepositoryMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Keeps the rows of the depository's movement notices for the reconciliation to check trades against. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepositoryMovementService {

    private final DepositoryMovementRepository repo;

    /** Records a notice's rows; returns how many it had, or -1 when the text isn't a notice this reads. Never throws. */
    public int record(Long userId, String gmailMessageId, String text) {
        DepositoryMovementParser.Notice notice = DepositoryMovementParser.parse(text);
        if (notice == null || userId == null || gmailMessageId == null) return -1;
        try {
            for (DepositoryMovementParser.Movement m : notice.movements()) {
                if (repo.existsByUserIdAndGmailMessageIdAndRowNo(userId, gmailMessageId, m.rowNo())) continue;
                repo.save(DepositoryMovement.builder().userId(userId).gmailMessageId(gmailMessageId).rowNo(m.rowNo())
                    .accountSuffix(notice.accountSuffix()).companyName(m.companyName()).isin(m.isin())
                    .quantity(m.quantity()).credit(m.credit()).movedAt(m.movedAt()).build());
            }
        } catch (Exception e) {
            log.warn("Depository notice {} not recorded: {}", gmailMessageId, e.getMessage());
        }
        return notice.movements().size();
    }

    /** Whether a notice's rows are already kept; a notice read before they were is read again. */
    public boolean recorded(Long userId, String gmailMessageId) {
        return repo.existsByUserIdAndGmailMessageId(userId, gmailMessageId);
    }
}
