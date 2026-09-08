package com.marketai.tracking.service;

import com.marketai.tracking.dto.*;
import com.marketai.tracking.entity.*;
import com.marketai.tracking.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrackingService {

    private final FixedDepositRepository fdRepo;
    private final RecurringDepositRepository rdRepo;
    private final LoanRepository loanRepo;
    private final EpfAccountRepository epfRepo;
    private final com.marketai.income.repository.IncomeRepository incomeRepo;
    private final OtherAssetRepository otherRepo;

    /* ── Fixed Deposits ──────────────────────────────────────── */

    @Transactional
    public FdResponse addFd(Long userId, FdRequest req, com.marketai.auth.entity.User user) {
        FixedDeposit fd = FixedDeposit.builder()
            .user(user)
            .bank(req.getBank())
            .principal(req.getPrincipal())
            .rate(req.getRate())
            .compounding(req.getCompounding() != null ? req.getCompounding() : "quarterly")
            .autoRenew(req.isAutoRenew())
            .startDate(req.getStartDate())
            .maturityDate(req.getMaturityDate())
            .build();
        fd = fdRepo.save(fd);
        // Renewal detection previously ran only on the Gmail-import path (ParsedEmailImporter)
        // — a manually-entered renewal FD was never linked to its predecessor and could
        // double-count. Every FD creation path must go through the same detection.
        return detectAndLinkRenewal(userId, fd.getId());
    }

    // Renewal-matching tolerances, stated explicitly so the rule is auditable rather than a
    // black box: a bank renewal is booked within days of the old FD's maturity date (weekend/
    // processing lag), and the new principal is close to — but not exactly — the old FD's
    // maturity value, since TDS on the interest (10% absent a Form 15G) is commonly deducted
    // before rollover. 5% covers standard TDS plus rounding without being loose enough to
    // false-match an unrelated new FD opened around the same time for a similar amount.
    private static final int RENEWAL_DATE_WINDOW_DAYS = 10;
    private static final double RENEWAL_AMOUNT_TOLERANCE = 0.05;

    /**
     * Looks for an earlier, still-ACTIVE FD at the same bank whose maturity lines up with this
     * FD's start and whose matured value lines up with this FD's principal, and if found,
     * links them: the old FD is marked MATURED_RENEWED (excluded from net-worth totals from
     * then on — see getSummary) and points at the new one; the new FD records where it came
     * from. Never invents a link when no candidate clears both thresholds — an unmatched FD
     * is simply left as a fresh, unlinked FD, which is the existing (correct) behavior.
     */
    @Transactional
    public FdResponse detectAndLinkRenewal(Long userId, Long newFdId) {
        FixedDeposit newFd = fdRepo.findByIdAndUserId(newFdId, userId).orElse(null);
        if (newFd == null || newFd.getStartDate() == null) return newFd != null ? toFdResponse(newFd) : null;

        // MATURED is included alongside ACTIVE: the nightly maturity check may have already
        // flipped the old FD to MATURED by the time this renewal is recorded, and it's still a
        // valid renewal candidate — only CLOSED/MATURED_RENEWED are already resolved.
        List<FixedDeposit> candidates = fdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(userId, newFd.getBank(), java.util.Arrays.asList("ACTIVE", "MATURED"));

        FixedDeposit best = null;
        long bestDateDiff = Long.MAX_VALUE;
        BigDecimal bestMaturityValue = null;

        for (FixedDeposit candidate : candidates) {
            if (candidate.getId().equals(newFd.getId())) continue;
            if (candidate.getMaturityDate() == null) continue;

            long dateDiff = Math.abs(ChronoUnit.DAYS.between(candidate.getMaturityDate(), newFd.getStartDate()));
            if (dateDiff > RENEWAL_DATE_WINDOW_DAYS) continue;

            BigDecimal maturityValue = computeFdMaturity(candidate.getPrincipal(), candidate.getRate(),
                candidate.getCompounding(), candidate.getStartDate(), candidate.getMaturityDate());
            if (maturityValue.compareTo(BigDecimal.ZERO) <= 0) continue;

            double pctDiff = newFd.getPrincipal().subtract(maturityValue).abs()
                .divide(maturityValue, 6, RoundingMode.HALF_UP).doubleValue();
            if (pctDiff > RENEWAL_AMOUNT_TOLERANCE) continue;

            // Prefer the closest maturity-date match among everything that clears both gates.
            if (dateDiff < bestDateDiff) {
                best = candidate;
                bestDateDiff = dateDiff;
                bestMaturityValue = maturityValue;
            }
        }

        if (best != null) {
            best.setStatus("MATURED_RENEWED");
            best.setMaturityAmount(bestMaturityValue);
            best.setRenewedToId(newFd.getId());
            fdRepo.save(best);

            newFd.setRenewedFromId(best.getId());
            newFd = fdRepo.save(newFd);
        }

        return toFdResponse(newFd);
    }

    public List<FdResponse> listFds(Long userId) {
        return fdRepo.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toFdResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deleteFd(Long id, Long userId) {
        if (!fdRepo.existsByIdAndUserId(id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        fdRepo.deleteById(id);
    }

    @Transactional
    public FdResponse closeFd(Long id, Long userId, BigDecimal actualAmount) {
        FixedDeposit fd = fdRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        BigDecimal received = actualAmount != null ? actualAmount :
            computeFdMaturity(fd.getPrincipal(), fd.getRate(), fd.getCompounding(), fd.getStartDate(), fd.getMaturityDate());
        fd.setStatus("CLOSED");
        fd.setMaturityAmount(received);
        fd.setClosedDate(LocalDate.now());
        fdRepo.save(fd);
        BigDecimal interestEarned = received.subtract(fd.getPrincipal()).max(BigDecimal.ZERO);
        com.marketai.income.entity.Income inc = com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description("FD interest — " + fd.getBank())
            .amount(interestEarned)
            .source("Interest")
            .incomeDate(LocalDate.now())
            .note("FD closed. Principal: ₹" + fd.getPrincipal() + ", Maturity: ₹" + received + ", Rate: " + fd.getRate() + "%")
            .build();
        incomeRepo.save(inc);
        return toFdResponse(fd);
    }

    private FdResponse toFdResponse(FixedDeposit fd) {
        BigDecimal maturityValue = computeFdMaturity(fd.getPrincipal(), fd.getRate(), fd.getCompounding(), fd.getStartDate(), fd.getMaturityDate());
        BigDecimal interest = maturityValue.subtract(fd.getPrincipal()).setScale(2, RoundingMode.HALF_UP);
        Long days = fd.getMaturityDate() != null ? ChronoUnit.DAYS.between(LocalDate.now(), fd.getMaturityDate()) : null;
        return FdResponse.builder()
            .id(fd.getId())
            .bank(fd.getBank())
            .principal(fd.getPrincipal())
            .rate(fd.getRate())
            .compounding(fd.getCompounding())
            .autoRenew(fd.isAutoRenew())
            .startDate(fd.getStartDate())
            .maturityDate(fd.getMaturityDate())
            .maturityValue(maturityValue)
            .currentValue(computeFdCurrentValue(fd.getPrincipal(), fd.getRate(), fd.getCompounding(), fd.getStartDate(), fd.getMaturityDate(), maturityValue))
            .interestEarned(interest)
            .daysToMaturity(days)
            .status(fd.getStatus())
            .actualMaturityAmount(fd.getMaturityAmount())
            .closedDate(fd.getClosedDate())
            .renewedToId(fd.getRenewedToId())
            .renewedFromId(fd.getRenewedFromId())
            .build();
    }

    /**
     * Compound interest: P * (1 + r/n)^(n*t)
     * r = annual rate / 100, n = compounding frequency, t = years
     */
    private BigDecimal computeFdMaturity(BigDecimal principal, BigDecimal rate,
                                         String compounding, LocalDate start, LocalDate maturity) {
        if (start == null || maturity == null) {
            // no dates: return simple 1-year interest
            double annualInterest = principal.doubleValue() * rate.doubleValue() / 100.0;
            return principal.add(BigDecimal.valueOf(annualInterest)).setScale(2, RoundingMode.HALF_UP);
        }
        double years = ChronoUnit.DAYS.between(start, maturity) / 365.25;
        int n = compoundingFrequency(compounding);
        double r = rate.doubleValue() / 100.0;
        double mat = principal.doubleValue() * Math.pow(1 + r / n, n * years);
        return BigDecimal.valueOf(mat).setScale(2, RoundingMode.HALF_UP);
    }

    private int compoundingFrequency(String c) {
        if ("monthly".equalsIgnoreCase(c))   return 12;
        if ("annually".equalsIgnoreCase(c))  return 1;
        return 4; // quarterly default
    }

    /** Value accrued to TODAY (principal + interest so far), capped at maturity value. */
    private BigDecimal computeFdCurrentValue(BigDecimal principal, BigDecimal rate, String compounding,
                                             LocalDate start, LocalDate maturity, BigDecimal maturityValue) {
        if (principal == null) return BigDecimal.ZERO;
        if (start == null) return principal;
        LocalDate today = LocalDate.now();
        if (maturity != null && !today.isBefore(maturity)) return maturityValue; // matured
        double years = Math.max(0, ChronoUnit.DAYS.between(start, today) / 365.25);
        int n = compoundingFrequency(compounding);
        double r = rate.doubleValue() / 100.0;
        double cur = principal.doubleValue() * Math.pow(1 + r / n, n * years);
        return BigDecimal.valueOf(cur).setScale(2, RoundingMode.HALF_UP);
    }

    /* ── Recurring Deposits ──────────────────────────────────── */

    @Transactional
    public RdResponse addRd(Long userId, RdRequest req, com.marketai.auth.entity.User user) {
        RecurringDeposit rd = RecurringDeposit.builder()
            .user(user)
            .bank(req.getBank())
            .monthlyAmount(req.getMonthlyAmount())
            .rate(req.getRate())
            .startDate(req.getStartDate())
            .tenureMonths(req.getTenureMonths())
            .build();
        rd = rdRepo.save(rd);
        return detectAndLinkRdRenewal(userId, rd.getId());
    }

    /**
     * RD equivalent of {@link #detectAndLinkRenewal} — same matching rule (same bank, maturity
     * date within {@link #RENEWAL_DATE_WINDOW_DAYS} of the new RD's start, new monthly amount's
     * implied corpus within {@link #RENEWAL_AMOUNT_TOLERANCE} of the old RD's matured corpus),
     * since RDs are just as commonly rolled over as FDs and had no linkage at all before this.
     */
    @Transactional
    public RdResponse detectAndLinkRdRenewal(Long userId, Long newRdId) {
        RecurringDeposit newRd = rdRepo.findByIdAndUserId(newRdId, userId).orElse(null);
        if (newRd == null || newRd.getStartDate() == null) return newRd != null ? toRdResponse(newRd) : null;

        List<RecurringDeposit> candidates = rdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(userId, newRd.getBank(), java.util.Arrays.asList("ACTIVE", "MATURED"));

        RecurringDeposit best = null;
        long bestDateDiff = Long.MAX_VALUE;
        BigDecimal bestCorpus = null;

        for (RecurringDeposit candidate : candidates) {
            if (candidate.getId().equals(newRd.getId())) continue;
            LocalDate candidateMaturity = candidate.getMaturityDate();
            if (candidateMaturity == null) continue;

            long dateDiff = Math.abs(ChronoUnit.DAYS.between(candidateMaturity, newRd.getStartDate()));
            if (dateDiff > RENEWAL_DATE_WINDOW_DAYS) continue;

            BigDecimal corpus = computeRdCorpus(candidate.getMonthlyAmount(), candidate.getRate(), candidate.getTenureMonths());
            if (corpus.compareTo(BigDecimal.ZERO) <= 0) continue;

            // Compare against the new RD's total committed value (monthly * tenure) rather than
            // its first installment — an RD's "principal" is the whole schedule, not one payment.
            BigDecimal newRdCommitted = newRd.getMonthlyAmount().multiply(BigDecimal.valueOf(newRd.getTenureMonths()));
            double pctDiff = newRdCommitted.subtract(corpus).abs()
                .divide(corpus, 6, RoundingMode.HALF_UP).doubleValue();
            if (pctDiff > RENEWAL_AMOUNT_TOLERANCE) continue;

            if (dateDiff < bestDateDiff) {
                best = candidate;
                bestDateDiff = dateDiff;
                bestCorpus = corpus;
            }
        }

        if (best != null) {
            best.setStatus("MATURED_RENEWED");
            best.setMaturityAmount(bestCorpus);
            best.setRenewedToId(newRd.getId());
            rdRepo.save(best);

            newRd.setRenewedFromId(best.getId());
            newRd = rdRepo.save(newRd);
        }

        return toRdResponse(newRd);
    }

    public List<RdResponse> listRds(Long userId) {
        return rdRepo.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toRdResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deleteRd(Long id, Long userId) {
        if (!rdRepo.existsByIdAndUserId(id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        rdRepo.deleteById(id);
    }

    /** RD equivalent of {@link #closeFd} — books the actual (or projected) maturity amount as
     *  interest income and marks the RD CLOSED so it stops counting toward net worth. */
    @Transactional
    public RdResponse closeRd(Long id, Long userId, BigDecimal actualAmount) {
        RecurringDeposit rd = rdRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        BigDecimal received = actualAmount != null ? actualAmount :
            computeRdCorpus(rd.getMonthlyAmount(), rd.getRate(), rd.getTenureMonths());
        BigDecimal totalDeposited = rd.getMonthlyAmount().multiply(BigDecimal.valueOf(rd.getTenureMonths()));
        rd.setStatus("CLOSED");
        rd.setMaturityAmount(received);
        rd.setClosedDate(LocalDate.now());
        rdRepo.save(rd);
        BigDecimal interestEarned = received.subtract(totalDeposited).max(BigDecimal.ZERO);
        com.marketai.income.entity.Income inc = com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description("RD interest — " + rd.getBank())
            .amount(interestEarned)
            .source("Interest")
            .incomeDate(LocalDate.now())
            .note("RD closed. Total deposited: ₹" + totalDeposited + ", Maturity: ₹" + received + ", Rate: " + rd.getRate() + "%")
            .build();
        incomeRepo.save(inc);
        return toRdResponse(rd);
    }

    private RdResponse toRdResponse(RecurringDeposit rd) {
        int elapsed = monthsElapsed(rd.getStartDate(), rd.getTenureMonths());
        BigDecimal deposited = rd.getMonthlyAmount().multiply(BigDecimal.valueOf(elapsed)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal corpus = computeRdCorpus(rd.getMonthlyAmount(), rd.getRate(), rd.getTenureMonths());
        BigDecimal interest = corpus.subtract(rd.getMonthlyAmount().multiply(BigDecimal.valueOf(rd.getTenureMonths()))).setScale(2, RoundingMode.HALF_UP);
        double progress = rd.getTenureMonths() > 0 ? Math.min(100.0, (elapsed * 100.0) / rd.getTenureMonths()) : 0;
        return RdResponse.builder()
            .id(rd.getId())
            .bank(rd.getBank())
            .monthlyAmount(rd.getMonthlyAmount())
            .rate(rd.getRate())
            .startDate(rd.getStartDate())
            .tenureMonths(rd.getTenureMonths())
            .monthsElapsed(elapsed)
            .totalDeposited(deposited)
            .currentValue(computeRdCorpus(rd.getMonthlyAmount(), rd.getRate(), elapsed))
            .projectedCorpus(corpus)
            .interestEarned(interest.max(BigDecimal.ZERO))
            .progressPercent(progress)
            .maturityDate(rd.getMaturityDate())
            .status(rd.getStatus())
            .actualMaturityAmount(rd.getMaturityAmount())
            .closedDate(rd.getClosedDate())
            .renewedToId(rd.getRenewedToId())
            .renewedFromId(rd.getRenewedFromId())
            .build();
    }

    /**
     * RD maturity formula: M * [(1+r)^n - 1] / r * (1+r)
     * where r = monthly rate, n = tenure in months
     */
    private BigDecimal computeRdCorpus(BigDecimal monthly, BigDecimal annualRate, int months) {
        double r = annualRate.doubleValue() / 100.0 / 12.0;
        double m = monthly.doubleValue();
        double corpus;
        if (r == 0) {
            corpus = m * months;
        } else {
            corpus = m * (Math.pow(1 + r, months) - 1) / r * (1 + r);
        }
        return BigDecimal.valueOf(corpus).setScale(2, RoundingMode.HALF_UP);
    }

    private int monthsElapsed(LocalDate start, int maxMonths) {
        if (start == null) return 0;
        long months = ChronoUnit.MONTHS.between(start, LocalDate.now());
        return (int) Math.max(0, Math.min(months, maxMonths));
    }

    /* ── Loans ───────────────────────────────────────────────── */

    @Transactional
    public LoanResponse addLoan(Long userId, LoanRequest req, com.marketai.auth.entity.User user) {
        Loan loan = Loan.builder()
            .user(user)
            .name(req.getName())
            .type(req.getType() != null ? req.getType() : "Other")
            .emi(req.getEmi())
            .outstanding(req.getOutstanding() != null ? req.getOutstanding() : BigDecimal.ZERO)
            .rate(req.getRate() != null ? req.getRate() : BigDecimal.ZERO)
            .remainingMonths(req.getRemainingMonths())
            .build();
        return toLoanResponse(loanRepo.save(loan));
    }

    public List<LoanResponse> listLoans(Long userId) {
        return loanRepo.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toLoanResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deleteLoan(Long id, Long userId) {
        if (!loanRepo.existsByIdAndUserId(id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        loanRepo.deleteById(id);
    }

    private LoanResponse toLoanResponse(Loan loan) {
        BigDecimal totalPayable = loan.getEmi().multiply(BigDecimal.valueOf(loan.getRemainingMonths())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInterest = totalPayable.subtract(loan.getOutstanding()).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        // repaid % = how much of principal is already paid relative to original loan
        // estimate: original loan ≈ outstanding + already-paid; we only have outstanding so use EMI-based repayment estimate
        double repaid = 0;
        if (loan.getOutstanding().compareTo(BigDecimal.ZERO) > 0 && loan.getRemainingMonths() > 0) {
            // fraction of principal still outstanding vs total payable = rough proxy of what's left
            double fraction = loan.getOutstanding().doubleValue() / totalPayable.doubleValue();
            repaid = Math.max(0, Math.min(100, (1 - fraction) * 100));
        }
        return LoanResponse.builder()
            .id(loan.getId())
            .name(loan.getName())
            .type(loan.getType())
            .emi(loan.getEmi())
            .outstanding(loan.getOutstanding())
            .rate(loan.getRate())
            .remainingMonths(loan.getRemainingMonths())
            .totalPayable(totalPayable)
            .totalInterestPayable(totalInterest)
            .repaidPercent(repaid)
            .build();
    }

    /* ── Other Assets ────────────────────────────────────────── */

    @Transactional
    public OtherAssetResponse addOtherAsset(Long userId, OtherAssetRequest req, com.marketai.auth.entity.User user) {
        OtherAsset asset = OtherAsset.builder()
            .user(user)
            .name(req.getName())
            .category(req.getCategory())
            .value(req.getValue())
            .note(req.getNote())
            .asOf(req.getAsOf() != null ? req.getAsOf() : LocalDate.now())
            .build();
        return toOtherResponse(otherRepo.save(asset));
    }

    @Transactional
    public OtherAssetResponse updateOtherAsset(Long id, Long userId, OtherAssetRequest req) {
        OtherAsset asset = otherRepo.findById(id)
            .filter(a -> a.getUser().getId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        asset.setValue(req.getValue());
        if (req.getNote() != null) asset.setNote(req.getNote());
        if (req.getAsOf() != null) asset.setAsOf(req.getAsOf());
        return toOtherResponse(otherRepo.save(asset));
    }

    public List<OtherAssetResponse> listOtherAssets(Long userId) {
        return otherRepo.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toOtherResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deleteOtherAsset(Long id, Long userId) {
        if (!otherRepo.existsByIdAndUserId(id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        otherRepo.deleteById(id);
    }

    private OtherAssetResponse toOtherResponse(OtherAsset a) {
        return OtherAssetResponse.builder()
            .id(a.getId())
            .name(a.getName())
            .category(a.getCategory())
            .value(a.getValue())
            .note(a.getNote())
            .asOf(a.getAsOf())
            .build();
    }

    /* ── EPF (provident fund) ────────────────────────────────── */

    @Transactional
    public EpfResponse addEpf(Long userId, EpfRequest req, com.marketai.auth.entity.User user) {
        EpfAccount e = EpfAccount.builder()
            .user(user)
            .employer(req.getEmployer())
            .currentBalance(req.getCurrentBalance() != null ? req.getCurrentBalance() : BigDecimal.ZERO)
            .monthlyContribution(req.getMonthlyContribution() != null ? req.getMonthlyContribution() : BigDecimal.ZERO)
            .rate(req.getRate() != null ? req.getRate() : new BigDecimal("8.25"))
            .asOfDate(req.getAsOfDate() != null ? req.getAsOfDate() : LocalDate.now())
            .build();
        return toEpfResponse(epfRepo.save(e));
    }

    public List<EpfResponse> listEpf(Long userId) {
        return epfRepo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toEpfResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deleteEpf(Long id, Long userId) {
        if (!epfRepo.existsByIdAndUserId(id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        epfRepo.deleteById(id);
    }

    private EpfResponse toEpfResponse(EpfAccount e) {
        double bal = e.getCurrentBalance() != null ? e.getCurrentBalance().doubleValue() : 0;
        double m = e.getMonthlyContribution() != null ? e.getMonthlyContribution().doubleValue() : 0;
        double r = (e.getRate() != null ? e.getRate().doubleValue() : 8.25) / 100.0;
        double annualInterest = bal * r;
        // 5-year projection: current balance compounded + monthly contributions annuity
        double fvBal = bal * Math.pow(1 + r, 5);
        double fvContrib = m > 0 ? m * 12 * ((Math.pow(1 + r, 5) - 1) / r) : 0;
        return EpfResponse.builder()
            .id(e.getId()).employer(e.getEmployer())
            .currentBalance(e.getCurrentBalance()).monthlyContribution(e.getMonthlyContribution())
            .rate(e.getRate()).asOfDate(e.getAsOfDate())
            .annualInterest(BigDecimal.valueOf(annualInterest).setScale(2, RoundingMode.HALF_UP))
            .projected5Y(BigDecimal.valueOf(fvBal + fvContrib).setScale(2, RoundingMode.HALF_UP))
            .build();
    }

    /* ── Updates (edit) ──────────────────────────────────────── */

    @Transactional
    public FdResponse updateFd(Long id, Long userId, FdRequest req) {
        FixedDeposit fd = fdRepo.findByIdAndUserId(id, userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.getBank() != null) fd.setBank(req.getBank());
        if (req.getPrincipal() != null) fd.setPrincipal(req.getPrincipal());
        if (req.getRate() != null) fd.setRate(req.getRate());
        if (req.getCompounding() != null) fd.setCompounding(req.getCompounding());
        fd.setAutoRenew(req.isAutoRenew());
        if (req.getStartDate() != null) fd.setStartDate(req.getStartDate());
        if (req.getMaturityDate() != null) fd.setMaturityDate(req.getMaturityDate());
        return toFdResponse(fdRepo.save(fd));
    }

    @Transactional
    public RdResponse updateRd(Long id, Long userId, RdRequest req) {
        RecurringDeposit rd = rdRepo.findByIdAndUserId(id, userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.getBank() != null) rd.setBank(req.getBank());
        if (req.getMonthlyAmount() != null) rd.setMonthlyAmount(req.getMonthlyAmount());
        if (req.getRate() != null) rd.setRate(req.getRate());
        if (req.getStartDate() != null) rd.setStartDate(req.getStartDate());
        if (req.getTenureMonths() > 0) rd.setTenureMonths(req.getTenureMonths());
        return toRdResponse(rdRepo.save(rd));
    }

    @Transactional
    public LoanResponse updateLoan(Long id, Long userId, LoanRequest req) {
        Loan l = loanRepo.findByIdAndUserId(id, userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.getName() != null) l.setName(req.getName());
        if (req.getType() != null) l.setType(req.getType());
        if (req.getEmi() != null) l.setEmi(req.getEmi());
        if (req.getOutstanding() != null) l.setOutstanding(req.getOutstanding());
        if (req.getRate() != null) l.setRate(req.getRate());
        if (req.getRemainingMonths() > 0) l.setRemainingMonths(req.getRemainingMonths());
        return toLoanResponse(loanRepo.save(l));
    }

    @Transactional
    public EpfResponse updateEpf(Long id, Long userId, EpfRequest req) {
        EpfAccount e = epfRepo.findByIdAndUserId(id, userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.getEmployer() != null) e.setEmployer(req.getEmployer());
        if (req.getCurrentBalance() != null) e.setCurrentBalance(req.getCurrentBalance());
        if (req.getMonthlyContribution() != null) e.setMonthlyContribution(req.getMonthlyContribution());
        if (req.getRate() != null) e.setRate(req.getRate());
        if (req.getAsOfDate() != null) e.setAsOfDate(req.getAsOfDate());
        return toEpfResponse(epfRepo.save(e));
    }

    /**
     * Flips ACTIVE FDs/RDs whose maturity date has passed into MATURED — a status distinct
     * from ACTIVE so an overdue deposit surfaces as needing a decision (withdraw or renew)
     * instead of silently sitting frozen at its maturity value forever with no signal. Never
     * touches CLOSED/MATURED_RENEWED/already-MATURED rows.
     */
    @Transactional
    public void markMaturedDeposits() {
        LocalDate today = LocalDate.now();
        for (FixedDeposit fd : fdRepo.findByStatus("ACTIVE")) {
            if (fd.getMaturityDate() != null && !today.isBefore(fd.getMaturityDate())) {
                fd.setStatus("MATURED");
                fdRepo.save(fd);
            }
        }
        for (RecurringDeposit rd : rdRepo.findByStatus("ACTIVE")) {
            LocalDate maturity = rd.getMaturityDate();
            if (maturity != null && !today.isBefore(maturity)) {
                rd.setStatus("MATURED");
                rdRepo.save(rd);
            }
        }
    }

    /**
     * FD/RD equivalent of {@link com.marketai.portfolio.service.PortfolioService#checkIntegrity}
     * — flags problems instead of letting them sit invisibly: an FD/RD that's been sitting
     * MATURED for over a month with no closure or renewal decision recorded, and a renewal
     * link that points at a since-deleted FD/RD (a data-loss situation the UI must not paper
     * over by silently ignoring the dangling reference).
     */
    public List<com.marketai.reconciliation.dto.ReconciliationIssue> checkFdRdIntegrity(Long userId) {
        List<com.marketai.reconciliation.dto.ReconciliationIssue> issues = new java.util.ArrayList<>();
        List<FdResponse> fds = listFds(userId);
        List<RdResponse> rds = listRds(userId);

        for (FdResponse fd : fds) {
            if ("MATURED".equals(fd.getStatus()) && fd.getDaysToMaturity() != null && fd.getDaysToMaturity() < -30) {
                issues.add(com.marketai.reconciliation.dto.ReconciliationIssue.builder()
                    .domain("FD").type("MATURED_IDLE").referenceId(fd.getId()).severity("MEDIUM")
                    .description(fd.getBank() + " FD matured " + (-fd.getDaysToMaturity()) + " days ago and hasn't been closed or renewed yet — record what happened to it.")
                    .build());
            }
            if (fd.getRenewedToId() != null && fds.stream().noneMatch(x -> fd.getRenewedToId().equals(x.getId()))) {
                issues.add(com.marketai.reconciliation.dto.ReconciliationIssue.builder()
                    .domain("FD").type("ORPHANED_RENEWAL_LINK").referenceId(fd.getId()).severity("HIGH")
                    .description(fd.getBank() + " FD is marked renewed into a successor FD that no longer exists — its money may not be counted anywhere.")
                    .build());
            }
        }

        for (RdResponse rd : rds) {
            if ("MATURED".equals(rd.getStatus()) && rd.getMaturityDate() != null
                    && ChronoUnit.DAYS.between(rd.getMaturityDate(), LocalDate.now()) > 30) {
                issues.add(com.marketai.reconciliation.dto.ReconciliationIssue.builder()
                    .domain("RD").type("MATURED_IDLE").referenceId(rd.getId()).severity("MEDIUM")
                    .description(rd.getBank() + " RD matured " + ChronoUnit.DAYS.between(rd.getMaturityDate(), LocalDate.now()) + " days ago and hasn't been closed or renewed yet — record what happened to it.")
                    .build());
            }
            if (rd.getRenewedToId() != null && rds.stream().noneMatch(x -> rd.getRenewedToId().equals(x.getId()))) {
                issues.add(com.marketai.reconciliation.dto.ReconciliationIssue.builder()
                    .domain("RD").type("ORPHANED_RENEWAL_LINK").referenceId(rd.getId()).severity("HIGH")
                    .description(rd.getBank() + " RD is marked renewed into a successor RD that no longer exists — its money may not be counted anywhere.")
                    .build());
            }
        }

        return issues;
    }

    /* ── Summary ─────────────────────────────────────────────── */

    public TrackingSummaryResponse getSummary(Long userId) {
        List<FdResponse> fds = listFds(userId);
        List<RdResponse> rds = listRds(userId);
        List<LoanResponse> loans = listLoans(userId);
        List<OtherAssetResponse> others = listOtherAssets(userId);
        List<EpfResponse> epf = listEpf(userId);
        BigDecimal totalEpf = epf.stream().map(EpfResponse::getCurrentBalance).reduce(BigDecimal.ZERO, BigDecimal::add);

        // MATURED_RENEWED must be excluded alongside CLOSED: that money is now counted via
        // the successor FD it was rolled into (renewedToId), so including both here would
        // double-count the same rupees under two rows — the exact bug a renewal used to cause.
        java.util.List<FdResponse> activeFds = fds.stream()
            .filter(fd -> !"CLOSED".equalsIgnoreCase(fd.getStatus()) && !"MATURED_RENEWED".equalsIgnoreCase(fd.getStatus()))
            .collect(java.util.stream.Collectors.toList());
        BigDecimal totalFdPrincipal = activeFds.stream().map(FdResponse::getPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFdMaturity  = activeFds.stream().map(FdResponse::getMaturityValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFdCurrent   = activeFds.stream().map(FdResponse::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Same reasoning as FD above: a CLOSED or MATURED_RENEWED RD's money either left the
        // account or is now counted via the successor RD — including it here would double it.
        List<RdResponse> activeRds = rds.stream()
            .filter(rd -> !"CLOSED".equalsIgnoreCase(rd.getStatus()) && !"MATURED_RENEWED".equalsIgnoreCase(rd.getStatus()))
            .collect(Collectors.toList());
        BigDecimal totalRdCorpus    = activeRds.stream().map(RdResponse::getProjectedCorpus).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRdCurrent   = activeRds.stream().map(RdResponse::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOther       = others.stream().map(OtherAssetResponse::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOutstanding = loans.stream().map(LoanResponse::getOutstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEmi         = loans.stream().map(LoanResponse::getEmi).reduce(BigDecimal.ZERO, BigDecimal::add);

        return TrackingSummaryResponse.builder()
            .fds(fds).rds(rds).loans(loans).otherAssets(others).epfAccounts(epf)
            .totalFdPrincipal(totalFdPrincipal)
            .totalFdMaturityValue(totalFdMaturity)
            .totalFdCurrentValue(totalFdCurrent)
            .totalRdCorpus(totalRdCorpus)
            .totalRdCurrentValue(totalRdCurrent)
            .totalOtherAssets(totalOther)
            .totalEpf(totalEpf)
            .totalLoanOutstanding(totalOutstanding)
            .totalMonthlyEmi(totalEmi)
            .build();
    }
}
