package com.marketai.gmail.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailFinancialEventRepository extends JpaRepository<EmailFinancialEvent, Long> {

    Optional<EmailFinancialEvent> findByUserIdAndGmailMessageIdAndEventKey(Long userId, String gmailMessageId, String eventKey);

    List<EmailFinancialEvent> findByUserIdAndGmailMessageIdOrderByIdAsc(Long userId, String gmailMessageId);

    List<EmailFinancialEvent> findByUserIdAndGmailMessageIdAndItemIndex(Long userId, String gmailMessageId, Integer itemIndex);

    List<EmailFinancialEvent> findByUserIdAndStateInOrderByLastSeenAtDesc(Long userId, Collection<EventState> states, Pageable page);

    long countByUserIdAndStateIn(Long userId, Collection<EventState> states);

    @Query("select count(distinct e.gmailMessageId) from EmailFinancialEvent e where e.userId = :userId and e.state in :states")
    long countEmailsByUserIdAndStateIn(@Param("userId") Long userId, @Param("states") Collection<EventState> states);

    @Query("select e.state, count(e) from EmailFinancialEvent e where e.userId = :userId group by e.state")
    List<Object[]> countByState(@Param("userId") Long userId);

    @Query("select e.state, count(e) from EmailFinancialEvent e where e.userId = :userId and e.lastSeenAt >= :since group by e.state")
    List<Object[]> countByStateSeenSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
