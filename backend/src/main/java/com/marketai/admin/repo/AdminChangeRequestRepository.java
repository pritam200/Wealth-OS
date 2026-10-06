package com.marketai.admin.repo;

import com.marketai.admin.domain.AdminChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminChangeRequestRepository extends JpaRepository<AdminChangeRequest, Long> {
    List<AdminChangeRequest> findTop100ByOrderByIdDesc();
}
