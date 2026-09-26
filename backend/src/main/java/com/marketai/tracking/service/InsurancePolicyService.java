package com.marketai.tracking.service;

import com.marketai.tracking.dto.InsurancePolicyRequest;
import com.marketai.tracking.dto.InsurancePolicyResponse;
import com.marketai.tracking.entity.InsurancePolicy;
import com.marketai.tracking.repository.InsurancePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD for insurance policies (term/health/motor/other). Sum assured is intentionally never
 * folded into net worth here — a policyholder's sum assured is a contingent payout, not a
 * currently-held asset, and OtherAsset already has a live "insurance" category for anyone
 * tracking a policy's surrender/cash value; adding sum assured on top would double-count.
 * Premium-due dates feed ReminderService the same way FD maturities and RD installments do.
 */
@Service
@RequiredArgsConstructor
public class InsurancePolicyService {

    private final InsurancePolicyRepository repo;

    @Transactional
    public InsurancePolicyResponse addPolicy(Long userId, InsurancePolicyRequest req, com.marketai.auth.entity.User user) {
        InsurancePolicy policy = InsurancePolicy.builder()
            .user(user)
            .policyType(req.getPolicyType())
            .insurer(req.getInsurer())
            .policyNumber(req.getPolicyNumber())
            .sumAssured(req.getSumAssured())
            .premiumAmount(req.getPremiumAmount())
            .premiumFrequency(req.getPremiumFrequency() != null ? req.getPremiumFrequency() : InsurancePolicy.PremiumFrequency.ANNUAL)
            .nextPremiumDueDate(req.getNextPremiumDueDate())
            .startDate(req.getStartDate())
            .endDate(req.getEndDate())
            .notes(req.getNotes())
            .build();
        return toResponse(repo.save(policy));
    }

    public List<InsurancePolicyResponse> listPolicies(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public InsurancePolicyResponse updatePolicy(Long id, Long userId, InsurancePolicyRequest req) {
        InsurancePolicy policy = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.getPolicyType() != null) policy.setPolicyType(req.getPolicyType());
        if (req.getInsurer() != null) policy.setInsurer(req.getInsurer());
        if (req.getPolicyNumber() != null) policy.setPolicyNumber(req.getPolicyNumber());
        if (req.getSumAssured() != null) policy.setSumAssured(req.getSumAssured());
        if (req.getPremiumAmount() != null) policy.setPremiumAmount(req.getPremiumAmount());
        if (req.getPremiumFrequency() != null) policy.setPremiumFrequency(req.getPremiumFrequency());
        if (req.getNextPremiumDueDate() != null) policy.setNextPremiumDueDate(req.getNextPremiumDueDate());
        if (req.getStartDate() != null) policy.setStartDate(req.getStartDate());
        if (req.getEndDate() != null) policy.setEndDate(req.getEndDate());
        if (req.getNotes() != null) policy.setNotes(req.getNotes());
        return toResponse(repo.save(policy));
    }

    @Transactional
    public void deletePolicy(Long id, Long userId) {
        if (!repo.existsByIdAndUserId(id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        repo.deleteById(id);
    }

    private InsurancePolicyResponse toResponse(InsurancePolicy p) {
        Long days = p.getNextPremiumDueDate() != null
            ? ChronoUnit.DAYS.between(LocalDate.now(), p.getNextPremiumDueDate()) : null;
        return InsurancePolicyResponse.builder()
            .id(p.getId())
            .policyType(p.getPolicyType())
            .insurer(p.getInsurer())
            .policyNumber(p.getPolicyNumber())
            .sumAssured(p.getSumAssured())
            .premiumAmount(p.getPremiumAmount())
            .premiumFrequency(p.getPremiumFrequency())
            .nextPremiumDueDate(p.getNextPremiumDueDate())
            .startDate(p.getStartDate())
            .endDate(p.getEndDate())
            .notes(p.getNotes())
            .status(p.getStatus())
            .daysToNextPremium(days)
            .build();
    }
}
