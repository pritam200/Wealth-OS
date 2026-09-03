package com.marketai.card.service;

import com.marketai.card.dto.CardDtos.*;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CreditCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CardService {

    private final CreditCardRepository repo;

    /* ── Catalog ── */
    public List<CatalogEntry> catalog() {
        return CardCatalog.CARDS.stream().map(c -> CatalogEntry.builder()
            .name(c.name).issuer(c.issuer).network(c.network).bestFor(c.bestFor)
            .annualFee(c.annualFee).pointValue(c.pointValue)
            .benefits(c.benefits).rewardRates(c.rewardRates).build())
            .collect(Collectors.toList());
    }

    public List<String> categories() { return CardCatalog.CATEGORIES; }

    /* ── CRUD ── */
    public CardResponse add(Long userId, CardRequest req) {
        CreditCard card;
        if (req.getCatalogName() != null && !req.getCatalogName().isEmpty()) {
            CardCatalog.CatalogCard cat = CardCatalog.byName(req.getCatalogName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown card"));
            card = CreditCard.builder()
                .userId(userId)
                .name(cat.name).issuer(cat.issuer).network(cat.network)
                .lastFour(req.getLastFour())
                .annualFee(cat.annualFee).pointValue(cat.pointValue)
                .pointsBalance(req.getPointsBalance() != null ? req.getPointsBalance() : 0)
                .billingDay(req.getBillingDay()).dueDay(req.getDueDay())
                .benefits(String.join("\n", cat.benefits)).bestFor(cat.bestFor)
                .rewardRates(new LinkedHashMap<>(cat.rewardRates))
                .build();
        } else {
            card = CreditCard.builder()
                .userId(userId)
                .name(req.getName()).issuer(req.getIssuer()).network(req.getNetwork())
                .lastFour(req.getLastFour())
                .annualFee(req.getAnnualFee() != null ? req.getAnnualFee() : BigDecimal.ZERO)
                .pointValue(req.getPointValue() != null ? req.getPointValue() : new BigDecimal("0.25"))
                .pointsBalance(req.getPointsBalance() != null ? req.getPointsBalance() : 0)
                .billingDay(req.getBillingDay()).dueDay(req.getDueDay())
                .benefits(req.getBenefits()).bestFor(req.getBestFor())
                .rewardRates(req.getRewardRates() != null ? req.getRewardRates() : new LinkedHashMap<String, BigDecimal>())
                .build();
        }
        return toDto(repo.save(card));
    }

    public List<CardResponse> list(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).collect(Collectors.toList());
    }

    public void delete(Long userId, Long id) {
        repo.findByIdAndUserId(id, userId).ifPresent(repo::delete);
    }

    public CardResponse updatePoints(Long userId, Long id, Integer points) {
        CreditCard c = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        c.setPointsBalance(points);
        return toDto(repo.save(c));
    }

    /** Full edit of a saved card (points, name, network, fees, billing days, etc.). */
    public CardResponse updateCard(Long userId, Long id, CardRequest req) {
        CreditCard c = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.getName() != null) c.setName(req.getName());
        if (req.getIssuer() != null) c.setIssuer(req.getIssuer());
        if (req.getNetwork() != null) c.setNetwork(req.getNetwork());
        if (req.getLastFour() != null) c.setLastFour(req.getLastFour());
        if (req.getAnnualFee() != null) c.setAnnualFee(req.getAnnualFee());
        if (req.getPointValue() != null) c.setPointValue(req.getPointValue());
        if (req.getPointsBalance() != null) c.setPointsBalance(req.getPointsBalance());
        if (req.getBillingDay() != null) c.setBillingDay(req.getBillingDay());
        if (req.getDueDay() != null) c.setDueDay(req.getDueDay());
        if (req.getBenefits() != null) c.setBenefits(req.getBenefits());
        if (req.getBestFor() != null) c.setBestFor(req.getBestFor());
        if (req.getRewardRates() != null && !req.getRewardRates().isEmpty())
            c.setRewardRates(new LinkedHashMap<>(req.getRewardRates()));
        return toDto(repo.save(c));
    }

    /* ── Recommender: best card for a transaction ── */
    public List<RecommendResult> recommend(Long userId, RecommendRequest req) {
        List<CreditCard> cards = repo.findByUserIdOrderByCreatedAtDesc(userId);
        String cat = req.getCategory() != null ? req.getCategory() : "Other";
        BigDecimal amt = req.getAmount() != null ? req.getAmount() : BigDecimal.ZERO;
        String merchant = req.getMerchant();

        // If merchant is provided, try to refine the category
        if (merchant != null && !merchant.isEmpty()) {
            String inferred = CardCatalog.merchantToCategory(merchant);
            if (inferred != null && "Other".equals(cat)) {
                cat = inferred;
            }
        }

        if (cards.isEmpty()) {
            final String finalCat = cat;
            List<RecommendResult> results = CardCatalog.CARDS.stream()
                .filter(c -> isNetworkEligible(c.network, finalCat))
                .map(c -> {
                    BigDecimal rate = c.rewardRates.getOrDefault(finalCat,
                        c.rewardRates.getOrDefault("Other", BigDecimal.ONE));
                    BigDecimal reward = amt.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    return RecommendResult.builder()
                        .cardId(null).cardName(c.name).issuer(c.issuer).network(c.network)
                        .rewardRate(rate).expectedReward(reward).eligible(true)
                        .reason(String.format("%.1f%% on %s", rate, finalCat))
                        .best(false).fromCatalog(true).build();
                }).sorted(Comparator.comparing(RecommendResult::getExpectedReward).reversed())
                  .limit(3).collect(Collectors.toList());
            if (!results.isEmpty()) {
                RecommendResult top = results.get(0);
                top.setBest(true);
                top.setReason(String.format("Best card to get for %s — %.1f%% back (%s). Add it once you have it.",
                    finalCat, top.getRewardRate(), inr(top.getExpectedReward())));
            }
            return results;
        }

        // Phase 1: Check eligibility of each card for this payment method/category
        final String finalCat = cat;
        List<ScoredCard> eligible = new ArrayList<>();
        List<ScoredCard> ineligible = new ArrayList<>();

        for (CreditCard c : cards) {
            String network = c.getNetwork() != null ? c.getNetwork() : "";
            String ineligibilityNote = checkEligibility(network, finalCat, merchant);

            BigDecimal baseRate = c.getRewardRates().getOrDefault(finalCat,
                c.getRewardRates().getOrDefault("Other", BigDecimal.ONE));

            BigDecimal effectiveRate = baseRate;
            String merchantBonus = null;
            if (merchant != null && !merchant.isEmpty()) {
                Optional<CardCatalog.CatalogCard> catCard = CardCatalog.byName(c.getName());
                if (catCard.isPresent() && catCard.get().merchantBonusRates != null) {
                    String ml = merchant.toLowerCase();
                    for (Map.Entry<String, BigDecimal> entry : catCard.get().merchantBonusRates.entrySet()) {
                        if (ml.contains(entry.getKey())) {
                            if (entry.getValue().compareTo(effectiveRate) > 0) {
                                effectiveRate = entry.getValue();
                                merchantBonus = entry.getKey();
                            }
                            break;
                        }
                    }
                }
            }

            BigDecimal reward = amt.multiply(effectiveRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            List<String> reasons = new ArrayList<>();
            if (ineligibilityNote == null) {
                reasons.add(String.format("%.1f%% effective rate on %s", effectiveRate, finalCat));
                if (merchantBonus != null) {
                    reasons.add(String.format("Special merchant bonus for %s", merchantBonus));
                }
            }

            Optional<CardCatalog.CatalogCard> catCard = CardCatalog.byName(c.getName());
            if (catCard.isPresent() && catCard.get().monthlyCashbackCap != null) {
                BigDecimal cap = catCard.get().monthlyCashbackCap;
                if (reward.compareTo(cap) > 0) {
                    reward = cap;
                    reasons.add(String.format("Capped at %s/month", inr(cap)));
                }
            }

            if (catCard.isPresent()) {
                BigDecimal fee = catCard.get().annualFee;
                if (fee.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal waiverSpend = catCard.get().annualFeeWaiverSpend;
                    if (waiverSpend != null) {
                        reasons.add(String.format("Annual fee %s — waived at %s/year spend", inr(fee), inr(waiverSpend)));
                    }
                } else {
                    reasons.add("Lifetime free card");
                }
            }

            if (c.getPointValue() != null && c.getPointValue().compareTo(new BigDecimal("0.5")) > 0) {
                reasons.add(String.format("Points worth ₹%.2f each", c.getPointValue()));
            }

            ScoredCard sc = new ScoredCard(c, effectiveRate, reward, reasons, merchantBonus, ineligibilityNote);
            if (ineligibilityNote == null) {
                eligible.add(sc);
            } else {
                ineligible.add(sc);
            }
        }

        // Phase 2: Sort eligible cards by reward, then append ineligible
        Collections.sort(eligible, new Comparator<ScoredCard>() {
            @Override public int compare(ScoredCard a, ScoredCard b) { return b.reward.compareTo(a.reward); }
        });

        List<RecommendResult> results = new ArrayList<>();

        // Eligible cards ranked
        int rank = 0;
        for (ScoredCard sc : eligible) {
            rank++;
            String reasonDetail = joinStrings(sc.reasons, ". ");
            String mainReason;
            if (rank == 1) {
                mainReason = String.format("Best choice — earns %s (%.1f%%) on this %s spend. %s",
                    inr(sc.reward), sc.rate, finalCat, reasonDetail);
            } else if (rank == 2) {
                mainReason = String.format("Runner-up — earns %s (%.1f%%). %s",
                    inr(sc.reward), sc.rate, reasonDetail);
            } else if (rank == 3) {
                mainReason = String.format("Alternative — earns %s (%.1f%%). %s",
                    inr(sc.reward), sc.rate, reasonDetail);
            } else {
                mainReason = String.format("%.1f%% on %s = %s", sc.rate, finalCat, inr(sc.reward));
            }
            results.add(RecommendResult.builder()
                .cardId(sc.card.getId()).cardName(sc.card.getName()).issuer(sc.card.getIssuer())
                .network(sc.card.getNetwork()).rewardRate(sc.rate).expectedReward(sc.reward)
                .reason(mainReason).best(rank == 1).fromCatalog(false).eligible(true)
                .build());
        }

        // Ineligible cards at the end with explanation
        for (ScoredCard sc : ineligible) {
            results.add(RecommendResult.builder()
                .cardId(sc.card.getId()).cardName(sc.card.getName()).issuer(sc.card.getIssuer())
                .network(sc.card.getNetwork()).rewardRate(sc.rate).expectedReward(BigDecimal.ZERO)
                .reason(sc.ineligibilityNote).best(false).fromCatalog(false)
                .eligible(false).ineligibilityNote(sc.ineligibilityNote)
                .build());
        }

        return results;
    }

    private static boolean isNetworkEligible(String network, String category) {
        if (!"UPI".equals(category)) return true;
        return "RuPay".equalsIgnoreCase(network);
    }

    private static String checkEligibility(String network, String category, String merchant) {
        // UPI requires RuPay
        if ("UPI".equals(category)) {
            if (!"RuPay".equalsIgnoreCase(network)) {
                return "Not eligible — UPI payments require a RuPay credit card. " +
                       network + " cards cannot be linked for UPI transactions.";
            }
        }
        // Rent payments: most issuers code this as a cash advance or exclude from rewards
        if ("Rent".equals(category)) {
            if ("Amex".equalsIgnoreCase(network)) {
                return "Not eligible — Amex is not accepted by most rent payment platforms.";
            }
        }
        // Amex has limited merchant acceptance
        if ("Amex".equalsIgnoreCase(network) && merchant != null) {
            String ml = merchant.toLowerCase();
            if (ml.contains("blinkit") || ml.contains("zepto") || ml.contains("dunzo")) {
                return "Not eligible — Amex may not be accepted at this merchant. " +
                       "Quick-commerce apps typically only accept Visa/Mastercard/RuPay.";
            }
        }
        // Fuel: Diners Club has limited acceptance at fuel stations
        if ("Fuel".equals(category) && "Diners".equalsIgnoreCase(network)) {
            return "Not eligible — Diners Club cards have limited acceptance at fuel stations.";
        }
        return null;
    }

    /** Inner helper class for scoring cards during recommendation. */
    private static class ScoredCard {
        final CreditCard card;
        final BigDecimal rate;
        final BigDecimal reward;
        final List<String> reasons;
        final String merchantBonus;
        final String ineligibilityNote;
        ScoredCard(CreditCard card, BigDecimal rate, BigDecimal reward,
                   List<String> reasons, String merchantBonus, String ineligibilityNote) {
            this.card = card; this.rate = rate; this.reward = reward;
            this.reasons = reasons; this.merchantBonus = merchantBonus;
            this.ineligibilityNote = ineligibilityNote;
        }
    }

    private static String joinStrings(List<String> parts, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /* ── Points optimizer ── */
    public List<PointsTip> pointsTips(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .filter(c -> c.getPointsBalance() != null && c.getPointsBalance() > 0)
            .map(c -> {
                int pts = c.getPointsBalance();
                BigDecimal cash = c.getPointValue().multiply(BigDecimal.valueOf(pts)).setScale(2, RoundingMode.HALF_UP);
                // best value ~ transferring to travel/airline partners typically ~2x cashback value
                BigDecimal best = cash.multiply(new BigDecimal("2")).setScale(2, RoundingMode.HALF_UP);
                String rec;
                if (pts < 500) rec = "Low balance — let points accumulate before redeeming.";
                else if ("Amex".equalsIgnoreCase(c.getIssuer()) || "Axis".equalsIgnoreCase(c.getIssuer()))
                    rec = "Transfer to airline/hotel partners for up to 2x value (" + inr(best) + ") vs " + inr(cash) + " cashback.";
                else
                    rec = "Redeem for statement credit / vouchers (" + inr(cash) + "). Avoid low-value catalogue products.";
                return PointsTip.builder()
                    .cardId(c.getId()).cardName(c.getName()).pointsBalance(pts)
                    .cashValue(cash).bestValue(best).recommendation(rec).build();
            }).collect(Collectors.toList());
    }

    private String inr(BigDecimal v) {
        return "₹" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private CardResponse toDto(CreditCard c) {
        BigDecimal cash = c.getPointValue().multiply(BigDecimal.valueOf(c.getPointsBalance() != null ? c.getPointsBalance() : 0))
            .setScale(2, RoundingMode.HALF_UP);
        return CardResponse.builder()
            .id(c.getId()).name(c.getName()).issuer(c.getIssuer()).network(c.getNetwork())
            .lastFour(c.getLastFour()).annualFee(c.getAnnualFee()).pointValue(c.getPointValue())
            .pointsBalance(c.getPointsBalance()).billingDay(c.getBillingDay()).dueDay(c.getDueDay())
            .benefits(c.getBenefits()).bestFor(c.getBestFor())
            .rewardRates(c.getRewardRates()).pointsCashValue(cash)
            .currentDue(c.getCurrentDue()).currentDueDate(c.getCurrentDueDate()).build();
    }
}
