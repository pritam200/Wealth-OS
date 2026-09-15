package com.marketai.actions.repository;

import com.marketai.actions.entity.ActionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ActionItemRepository extends JpaRepository<ActionItem, Long> {

    Optional<ActionItem> findByUser_IdAndActionTypeAndSymbolAndActionDate(
        Long userId, String actionType, String symbol, LocalDate actionDate);

    List<ActionItem> findByUser_IdAndActionDate(Long userId, LocalDate actionDate);

    Optional<ActionItem> findByIdAndUser_Id(Long id, Long userId);

    List<ActionItem> findByUser_IdOrderByUpdatedAtDesc(Long userId);
}
