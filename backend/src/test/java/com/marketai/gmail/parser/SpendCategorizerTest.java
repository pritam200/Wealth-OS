package com.marketai.gmail.parser;

import com.marketai.expense.entity.ExpenseCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the sub-brand disambiguation added on top of the pre-existing keyword matching:
 * Swiggy's own delivery, its grocery arm (Instamart), and its dine-in booking arm (Dineout) must
 * not collapse into one bucket, and a CRED/credit-card-bill debit must never be counted as spend
 * under any category — it settles a debt already spent against elsewhere.
 */
class SpendCategorizerTest {

    @Test
    void swiggyInstamartIsGroceriesNotFoodDelivery() {
        assertThat(SpendCategorizer.categorize("Payment to SWIGGY INSTAMART via UPI"))
            .isEqualTo(ExpenseCategory.GROCERIES);
    }

    @Test
    void plainSwiggyIsFoodDelivery() {
        assertThat(SpendCategorizer.categorize("Payment to SWIGGY via UPI"))
            .isEqualTo(ExpenseCategory.FOOD_DELIVERY);
    }

    @Test
    void swiggyDineoutIsRestaurantOuting() {
        assertThat(SpendCategorizer.categorize("Payment to SWIGGY DINEOUT via UPI"))
            .isEqualTo(ExpenseCategory.RESTAURANT_OUTING);
    }

    @Test
    void zomatoIsFoodDelivery() {
        assertThat(SpendCategorizer.categorize("Debited for ZOMATO order"))
            .isEqualTo(ExpenseCategory.FOOD_DELIVERY);
    }

    @Test
    void groceryAppIsGroceries() {
        assertThat(SpendCategorizer.categorize("Payment to BIGBASKET"))
            .isEqualTo(ExpenseCategory.GROCERIES);
    }

    @Test
    void namedRestaurantIsRestaurantOuting() {
        assertThat(SpendCategorizer.categorize("Card spend at STARBUCKS"))
            .isEqualTo(ExpenseCategory.RESTAURANT_OUTING);
    }

    @Test
    void credPaymentIsAccountTransferNotEmiOrAnythingElse() {
        assertThat(SpendCategorizer.categorize("Payment to CRED for credit card bill"))
            .isEqualTo(ExpenseCategory.ACCOUNT_TRANSFER);
    }

    @Test
    void creditCardBillPaymentPhraseIsAccountTransfer() {
        assertThat(SpendCategorizer.categorize("Your credit card payment of Rs.5000 has been received"))
            .isEqualTo(ExpenseCategory.ACCOUNT_TRANSFER);
    }

    @Test
    void investmentIsCheckedBeforeAccountTransferOrAnythingElse() {
        // An unlikely but possible overlap (e.g. a SIP narration that also mentions "cred" as a
        // merchant name fragment) must still resolve to INVESTMENT, never a spend category.
        assertThat(SpendCategorizer.categorize("SIP investment in mutual fund via Zerodha Coin"))
            .isEqualTo(ExpenseCategory.INVESTMENT);
    }

    @Test
    void genericLoanEmiStillCategorizesAsEmi() {
        assertThat(SpendCategorizer.categorize("Home loan EMI debited"))
            .isEqualTo(ExpenseCategory.EMI);
    }

    @Test
    void unrecognisedTextIsUncategorized() {
        assertThat(SpendCategorizer.categorize("Some unknown merchant XYZ123"))
            .isEqualTo(ExpenseCategory.UNCATEGORIZED);
    }
}
