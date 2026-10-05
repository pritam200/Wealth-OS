package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.AccountRef;
import com.marketai.dataplatform.repo.FinancialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Identification of the account an event belongs to. Accounts are per owner: two family members
 * with accounts at the same broker never share a row. A source that names only an institution
 * gets a placeholder account, which a later specific source can be reconciled into.
 */
@Service
@RequiredArgsConstructor
public class AccountResolver {

    private final FinancialAccountRepository accounts;

    public record Resolved(FinancialAccount account, boolean specific) {}

    public Resolved resolve(Long userId, AccountRef ref) {
        Long owner = ref.ownerUserId() != null ? ref.ownerUserId() : userId;
        String inst = normalise(ref.institution());
        String ext = ref.externalAccountId() == null || ref.externalAccountId().isBlank() ? null : ref.externalAccountId().trim().toUpperCase();
        String key = ext == null ? inst + ":DEFAULT" : inst + ":" + ext;
        FinancialAccount acct = accounts.findByOwnerUserIdAndAccountKey(owner, key).orElseGet(() ->
            accounts.save(FinancialAccount.builder()
                .ownerUserId(owner).familyId(ref.familyId())
                .ownership(ref.ownership() == null ? Ownership.INDIVIDUAL : ref.ownership())
                .accountType(ref.accountType() == null ? AccountType.OTHER : ref.accountType())
                .institution(ref.institution().trim()).accountKey(key)
                .maskedNumber(ext == null ? null : mask(ext))
                .displayName(ref.institution().trim() + (ext == null ? "" : " " + mask(ext)))
                .build()));
        return new Resolved(acct, ext != null);
    }

    /** The accounts whose entries make up one account's position: itself, plus placeholders at the same institution. */
    public List<Long> scopeFor(FinancialAccount account) {
        List<FinancialAccount> same = accounts.findByOwnerUserIdAndInstitutionIgnoreCase(account.getOwnerUserId(), account.getInstitution());
        long specific = same.stream().filter(a -> !isPlaceholder(a)).count();
        List<Long> ids = new java.util.ArrayList<>();
        ids.add(account.getId());
        // With two or more specific accounts a placeholder cannot be attributed to either.
        if (specific <= 1) same.stream().filter(AccountResolver::isPlaceholder).map(FinancialAccount::getId).filter(i -> !ids.contains(i)).forEach(ids::add);
        return ids;
    }

    public static boolean isPlaceholder(FinancialAccount a) { return a.getAccountKey().endsWith(":DEFAULT"); }

    static String normalise(String institution) {
        return institution == null ? "" : institution.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    static String mask(String id) { return id.length() <= 4 ? id : "••••" + id.substring(id.length() - 4); }
}
