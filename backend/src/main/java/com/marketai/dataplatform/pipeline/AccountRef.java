package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.AccountType;
import com.marketai.dataplatform.domain.Ownership;

/**
 * How a source names the account an event belongs to. {@code externalAccountId} is null when the
 * source does not say (an email often names only the broker); the account is then a placeholder
 * that a later, more specific source can replace.
 */
public record AccountRef(String institution, String externalAccountId, AccountType accountType,
                         Ownership ownership, Long ownerUserId, Long familyId) {

    public AccountRef(String institution, String externalAccountId, AccountType accountType) {
        this(institution, externalAccountId, accountType, Ownership.INDIVIDUAL, null, null);
    }

    public boolean specific() { return externalAccountId != null && !externalAccountId.isBlank(); }
}
