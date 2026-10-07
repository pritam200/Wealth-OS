package com.marketai.inbound;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InboundAddressRepository extends JpaRepository<InboundAddress, Long> {
    Optional<InboundAddress> findByUserId(Long userId);
    Optional<InboundAddress> findByCode(String code);
}
