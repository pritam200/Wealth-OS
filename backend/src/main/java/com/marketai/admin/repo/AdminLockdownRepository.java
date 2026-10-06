package com.marketai.admin.repo;

import com.marketai.admin.domain.AdminLockdown;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminLockdownRepository extends JpaRepository<AdminLockdown, Long> {}
