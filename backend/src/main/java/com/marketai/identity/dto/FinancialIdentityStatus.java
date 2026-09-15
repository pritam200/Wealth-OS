package com.marketai.identity.dto;

/**
 * What the UI is allowed to know about the stored identity: whether values exist, never what
 * they are. There is deliberately no field that could carry a PAN or a date of birth, so no
 * future change to a mapper can accidentally start returning one.
 */
public record FinancialIdentityStatus(boolean panSaved, boolean dobSaved,
                                      boolean canDerivePasswords, String message) {

    public static FinancialIdentityStatus of(boolean panSaved, boolean dobSaved) {
        boolean canDerive = panSaved || dobSaved;
        String message;
        if (panSaved && dobSaved) {
            message = "PAN and date of birth saved — locked statements can be opened automatically.";
        } else if (panSaved) {
            message = "PAN saved. Add your date of birth to cover providers that use it.";
        } else if (dobSaved) {
            message = "Date of birth saved. Add your PAN to cover providers that use it.";
        } else {
            message = "Not set. Add your PAN and date of birth so locked statements can be "
                    + "opened without entering a password each time.";
        }
        return new FinancialIdentityStatus(panSaved, dobSaved, canDerive, message);
    }
}
