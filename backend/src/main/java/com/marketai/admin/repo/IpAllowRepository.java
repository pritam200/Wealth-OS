package com.marketai.admin.repo;

import com.marketai.admin.domain.AdminEnvironment;
import com.marketai.admin.domain.IpAllowEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IpAllowRepository extends JpaRepository<IpAllowEntry, Long> {
    List<IpAllowEntry> findByEnvironmentOrderByIdAsc(AdminEnvironment environment);
    boolean existsByEnvironmentAndCidr(AdminEnvironment environment, String cidr);
}
