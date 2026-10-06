package com.marketai.admin.repo;

import com.marketai.admin.domain.AdminFeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminFeatureFlagRepository extends JpaRepository<AdminFeatureFlag, String> {}
