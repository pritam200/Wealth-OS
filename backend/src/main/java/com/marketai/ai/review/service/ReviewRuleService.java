package com.marketai.ai.review.service;

import com.marketai.ai.review.dto.ReviewDecisionRequest;
import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewRule;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.ai.review.repository.ReviewRuleRepository;
import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Remembers an accept or reject and applies it to every pending item of the same kind, now and
 * after each sync. Each item is decided through {@link EmailReviewService#decide} in its own
 * transaction, so an item that can't be booked as-is (no figures, already recorded) just stays
 * in the queue without undoing the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRuleService {

    private final ReviewRuleRepository ruleRepo;
    private final EmailReviewItemRepository itemRepo;
    private final EmailReviewService reviewService;

    private static final Pattern ADDRESS = Pattern.compile("<([^>]+)>");
    private static final Pattern MONTH = Pattern.compile(
        "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b");
    private static final Pattern NUMBER = Pattern.compile("[₹$]?\\s?\\d[\\d,./:-]*");

    /**
     * What makes two items "the same kind": the sender's address, the subject and the review
     * reason with their dates, amounts and reference numbers taken out, and the proposed type.
     * The subject keeps one broker's contract notes apart from its fund statements.
     */
    public static String kindKey(EmailReviewItem item) {
        String kind = address(item.getSender()) + "|" + shape(item.getSubject()) + "|"
            + (item.getProposedType() == null ? "" : item.getProposedType()) + "|" + shape(item.getReviewReason());
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(kind.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String address(String sender) {
        if (sender == null) return "";
        Matcher m = ADDRESS.matcher(sender);
        return (m.find() ? m.group(1) : sender).trim().toLowerCase(Locale.ROOT);
    }

    static String shape(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT);
        s = MONTH.matcher(s).replaceAll("#");
        s = NUMBER.matcher(s).replaceAll("#");
        return s.replaceAll("[#\\s]+(?=#)", "").replaceAll("\\s+", " ").trim();
    }

    /** Remembers the decision on {@code item}; then decides every other pending item like it. */
    public int remember(User user, EmailReviewItem item, String decision) {
        String key = kindKey(item);
        ReviewRule rule = ruleRepo.findByUserIdAndKindKey(user.getId(), key).orElseGet(() -> ReviewRule.builder()
            .userId(user.getId()).kindKey(key).createdAt(LocalDateTime.now()).build());
        rule.setDecision(decision);
        rule.setSender(item.getSender());
        rule.setSubject(item.getSubject());
        rule.setReviewReason(item.getReviewReason());
        rule.setProposedType(item.getProposedType());
        ruleRepo.save(rule);
        return apply(user);
    }

    /** Decides each pending item a remembered rule covers. Returns how many were decided. */
    public int apply(User user) {
        List<ReviewRule> rules = ruleRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        if (rules.isEmpty()) return 0;
        Map<String, ReviewRule> byKey = rules.stream()
            .collect(Collectors.toMap(ReviewRule::getKindKey, Function.identity(), (a, b) -> a));
        int decided = 0;
        for (EmailReviewItem item : itemRepo.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), ReviewStatus.PENDING)) {
            ReviewRule rule = byKey.get(kindKey(item));
            if (rule == null) continue;
            ReviewDecisionRequest req = new ReviewDecisionRequest();
            req.setDecision(rule.getDecision());
            req.setNote("Decided like the earlier " + rule.getDecision().toLowerCase(Locale.ROOT)
                + " of the same kind from this sender.");
            try {
                reviewService.decide(user, item.getId(), req);
                decided++;
            } catch (RuntimeException e) {
                // Left for the user: typically no figures to book, or already recorded.
                log.debug("Remembered {} not applied to review item {}: {}", rule.getDecision(), item.getId(), e.getMessage());
            }
        }
        if (decided > 0) log.info("Remembered decisions resolved {} review item(s) for user {}", decided, user.getId());
        return decided;
    }

    /** Each pending item's kind, so the queue can show one row per kind of question. */
    public Map<Long, String> kinds(Long userId) {
        Map<Long, String> out = new HashMap<>();
        for (EmailReviewItem i : itemRepo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ReviewStatus.PENDING)) {
            out.put(i.getId(), kindKey(i));
        }
        return out;
    }

    public List<ReviewRule> list(Long userId) {
        return ruleRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public void forget(Long userId, Long ruleId) {
        ruleRepo.findByIdAndUserId(ruleId, userId).ifPresent(ruleRepo::delete);
    }
}
