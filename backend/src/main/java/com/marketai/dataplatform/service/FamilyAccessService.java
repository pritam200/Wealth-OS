package com.marketai.dataplatform.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Who can see which accounts. Ownership is explicit and nothing is shared by default:
 * <ul>
 *   <li>INDIVIDUAL — only the owner.</li>
 *   <li>JOINT — the owner and the co-owners listed on the account.</li>
 *   <li>FAMILY — the owner, plus family members who may view the family, <em>and only while the
 *       owner has chosen to share</em> with that family.</li>
 * </ul>
 * Members' transactions are never mixed: each belongs to its owner's accounts, and a family view
 * is a union of accounts the viewer is entitled to, not a merged ledger.
 */
@Service
@RequiredArgsConstructor
public class FamilyAccessService {

    private final FinancialAccountRepository accounts;
    private final AccountMemberRepository accountMembers;
    private final FamilyRepository families;
    private final FamilyMemberRepository familyMembers;
    private final UserRepository users;

    /** @param includeFamily also include accounts shared with the user's families */
    public Set<Long> accessibleAccountIds(Long userId, boolean includeFamily) {
        Set<Long> ids = new LinkedHashSet<>();
        accounts.findByOwnerUserId(userId).forEach(a -> ids.add(a.getId()));
        accountMembers.findByUserId(userId).forEach(m -> ids.add(m.getAccountId()));
        if (includeFamily) {
            for (FamilyMember mine : familyMembers.findByUserId(userId)) {
                if (!mine.isCanViewFamily()) continue;
                Set<Long> sharers = new HashSet<>();
                for (FamilyMember other : familyMembers.findByFamilyId(mine.getFamilyId()))
                    if (other.isSharesData()) sharers.add(other.getUserId());
                for (FinancialAccount a : accounts.findByFamilyId(mine.getFamilyId()))
                    if (a.getOwnership() == Ownership.FAMILY && sharers.contains(a.getOwnerUserId())) ids.add(a.getId());
            }
        }
        return ids;
    }

    public boolean canAccess(Long userId, Long accountId) {
        return accessibleAccountIds(userId, true).contains(accountId);
    }

    /** Whether the user may change (confirm/reject) records on the account: owners and co-owners only. */
    public boolean canModify(Long userId, Long accountId) {
        FinancialAccount a = accounts.findById(accountId).orElse(null);
        if (a == null) return false;
        if (a.getOwnerUserId().equals(userId)) return true;
        return accountMembers.findByAccountIdAndUserId(accountId, userId).map(m -> "CO_OWNER".equals(m.getRole())).orElse(false);
    }

    /* ───────────── family management ───────────── */

    @Transactional
    public Family create(Long userId, String name) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A family needs a name");
        Family f = families.save(Family.builder().name(name.trim()).ownerUserId(userId).build());
        familyMembers.save(FamilyMember.builder().familyId(f.getId()).userId(userId).role(FamilyMember.OWNER).canViewFamily(true).sharesData(false).build());
        return f;
    }

    @Transactional
    public FamilyMember addMember(Long actingUserId, Long familyId, String email, String role) {
        Family f = families.findById(familyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
        if (!f.getOwnerUserId().equals(actingUserId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the family owner can add members");
        User u = users.findByEmail(email == null ? "" : email.trim()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Wealth-OS user with that email"));
        String r = FamilyMember.VIEWER.equals(role) ? FamilyMember.VIEWER : FamilyMember.ADULT;
        // A new member shares nothing until they choose to.
        return familyMembers.findByFamilyIdAndUserId(familyId, u.getId()).orElseGet(() ->
            familyMembers.save(FamilyMember.builder().familyId(familyId).userId(u.getId()).role(r)
                .canViewFamily(true).sharesData(false).build()));
    }

    /** A member's own decision whether the others in this family may see their FAMILY-scoped accounts. */
    @Transactional
    public FamilyMember setSharing(Long userId, Long familyId, boolean shares) {
        FamilyMember m = familyMembers.findByFamilyIdAndUserId(familyId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You are not in this family"));
        m.setSharesData(shares);
        return familyMembers.save(m);
    }

    @Transactional
    public void removeMember(Long actingUserId, Long familyId, Long memberUserId) {
        Family f = families.findById(familyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family not found"));
        if (!f.getOwnerUserId().equals(actingUserId) && !actingUserId.equals(memberUserId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the family owner can remove other members");
        if (memberUserId.equals(f.getOwnerUserId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The owner cannot leave their own family");
        familyMembers.findByFamilyIdAndUserId(familyId, memberUserId).ifPresent(familyMembers::delete);
    }

    public List<Map<String, Object>> myFamilies(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FamilyMember mine : familyMembers.findByUserId(userId)) {
            families.findById(mine.getFamilyId()).ifPresent(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId()); m.put("name", f.getName()); m.put("owner", f.getOwnerUserId().equals(userId));
                m.put("role", mine.getRole()); m.put("sharesData", mine.isSharesData());
                List<Map<String, Object>> members = new ArrayList<>();
                for (FamilyMember fm : familyMembers.findByFamilyId(f.getId())) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("userId", fm.getUserId()); mm.put("role", fm.getRole()); mm.put("sharesData", fm.isSharesData());
                    users.findById(fm.getUserId()).ifPresent(u -> mm.put("name", u.getName()));
                    members.add(mm);
                }
                m.put("members", members);
                out.add(m);
            });
        }
        return out;
    }

    /** As {@link #setOwnership}, naming co-owners by the email they registered with. */
    @Transactional
    public FinancialAccount setOwnershipByEmail(Long userId, Long accountId, Ownership ownership, Long familyId, List<String> coOwnerEmails) {
        List<Long> ids = new ArrayList<>();
        if (coOwnerEmails != null) for (String e : coOwnerEmails) {
            if (e == null || e.isBlank()) continue;
            ids.add(users.findByEmail(e.trim()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "No Wealth-OS user with the email " + e.trim())).getId());
        }
        return setOwnership(userId, accountId, ownership, familyId, ids);
    }

    /** Declares an account JOINT with a co-owner, or shares it with a family. Owner only. */
    @Transactional
    public FinancialAccount setOwnership(Long userId, Long accountId, Ownership ownership, Long familyId, List<Long> coOwnerUserIds) {
        FinancialAccount a = accounts.findById(accountId).filter(x -> x.getOwnerUserId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        if (ownership == Ownership.FAMILY) {
            if (familyId == null || familyMembers.findByFamilyIdAndUserId(familyId, userId).isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a family you belong to");
            a.setFamilyId(familyId);
        } else {
            a.setFamilyId(null);
        }
        a.setOwnership(ownership);
        if (ownership == Ownership.JOINT && coOwnerUserIds != null) {
            for (Long co : coOwnerUserIds) {
                if (co.equals(userId) || accountMembers.findByAccountIdAndUserId(accountId, co).isPresent()) continue;
                if (users.findById(co).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown co-owner " + co);
                accountMembers.save(AccountMember.builder().accountId(accountId).userId(co).role("CO_OWNER").build());
            }
        }
        return accounts.save(a);
    }
}
