package com.marketai.planner.service;

import com.marketai.planner.entity.PlanCategory;
import com.marketai.planner.entity.SinkingFund;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The one-time seed values digitized from the user's "Updated Household Budget Booklet" PDF —
 * 24 categories (planned amounts, ₹64,700 total) grouped exactly as page 6's Month-End Review
 * groups them, plus the two Annual Travel Fund Tracker funds. Used only by
 * {@code PlannerService.ensureSeeded}/{@code SinkingFundService.ensureSeeded} to populate a new
 * user's editable rows — nothing downstream reads these constants directly, so a user who
 * renames, re-groups, or deletes a category never has business logic silently reverting it.
 *
 * <p>Two of the six PDF group totals (Food & Household: printed ₹14,500; Lifestyle & Personal:
 * printed ₹4,450) don't exactly equal the sum of the categories the PDF itself lists under them
 * (₹13,000 and ₹4,500 respectively) — an inconsistency in the source document, not something
 * this seed should paper over. The UI computes group totals live from each category's own
 * planned amount, so it will show the arithmetically correct ₹13,000/₹4,500 rather than
 * reproducing the PDF's own rounding.
 */
public final class PlanCategoryDefaults {

    private PlanCategoryDefaults() {}

    public static final BigDecimal DEFAULT_MONTHLY_LIMIT = new BigDecimal("65000.00");

    private static final String HOUSING_BILLS = "Housing & Bills";
    private static final String FOOD_HOUSEHOLD = "Food & Household";
    private static final String TRANSPORT = "Transport";
    private static final String LIFESTYLE_PERSONAL = "Lifestyle & Personal";
    private static final String HEALTH_MAINTENANCE_MISC = "Health & Maintenance/Misc";
    private static final String TRAVEL_FUNDS = "Travel Funds (Savings)";

    public static List<PlanCategory> seedCategories(Long userId) {
        List<PlanCategory> rows = new ArrayList<>();
        int order = 0;

        rows.add(row(userId, order++, "RENT", "Rent", HOUSING_BILLS, "31000.00", "rent"));
        rows.add(row(userId, order++, "TRIP_VACATION_SAVINGS", "Trip / Vacation Savings", TRAVEL_FUNDS, "7500.00", "")
            .toBuilder().linkedSinkingFundName("Vacation / Trip Fund").build());
        rows.add(row(userId, order++, "DOMESTIC_HOME_TRAVEL", "Domestic Home Travel", TRAVEL_FUNDS, "3500.00", "")
            .toBuilder().linkedSinkingFundName("Domestic Home Travel Fund").build());
        rows.add(row(userId, order++, "PETROL_FUEL", "Petrol / Fuel", TRANSPORT, "2000.00",
            "petrol,diesel,fuel,hpcl,iocl,bpcl,shell,indian oil,bharat petroleum,fuel station"));
        rows.add(row(userId, order++, "LOCAL_TRANSPORTATION", "Local Transportation", TRANSPORT, "1000.00",
            "uber,ola,rapido,auto,metro,bus fare,namma yatri"));
        rows.add(row(userId, order++, "WIFI", "Wi-Fi", HOUSING_BILLS, "600.00",
            "wifi,broadband,fiber,fibernet,excitel,act broadband"));
        rows.add(row(userId, order++, "MAID", "Maid", HOUSING_BILLS, "1500.00", "maid,housemaid,domestic help"));
        rows.add(row(userId, order++, "ELECTRICITY", "Electricity", HOUSING_BILLS, "1000.00",
            "electricity,bescom,tata power,adani electricity,bses,discom"));
        rows.add(row(userId, order++, "COOKING_GAS", "Cooking Gas", HOUSING_BILLS, "700.00",
            "cooking gas,indane,hp gas,lpg,bharatgas"));
        rows.add(row(userId, order++, "GROCERIES", "Groceries", FOOD_HOUSEHOLD, "6500.00",
            "bigbasket,blinkit,zepto,grofers,dmart,d-mart,jiomart,reliance fresh,grocery,kirana,supermarket,instamart"));
        rows.add(row(userId, order++, "MEAT_NON_VEG", "Meat / Non-Veg", FOOD_HOUSEHOLD, "2000.00",
            "meat,chicken,mutton,fish,seafood,licious,freshtohome,fresh to home,zappfresh"));
        rows.add(row(userId, order++, "MILK", "Milk", FOOD_HOUSEHOLD, "1000.00",
            "milk,dairy,country delight,milkbasket"));
        rows.add(row(userId, order++, "EATING_OUT_FOOD_DELIVERY", "Eating Out / Food Delivery", FOOD_HOUSEHOLD, "2500.00",
            "swiggy,zomato,eatfit,dominos,pizza,mcdonald,kfc,restaurant,cafe,starbucks,box8,faasos,bakery"));
        rows.add(row(userId, order++, "MOBILE_BILLS", "Mobile Bills", LIFESTYLE_PERSONAL, "600.00",
            "airtel,jio,vodafone,vi recharge,postpaid,mobile recharge,prepaid recharge"));
        rows.add(row(userId, order++, "HOUSEHOLD_SUPPLIES", "Household Supplies", FOOD_HOUSEHOLD, "500.00",
            "household supplies,home supplies"));
        rows.add(row(userId, order++, "CLEANING_TOILETRIES", "Cleaning / Toiletries", FOOD_HOUSEHOLD, "500.00",
            "toiletries,cleaning,detergent,dishwash,dish wash"));
        rows.add(row(userId, order++, "PERSONAL_CARE", "Personal Care", LIFESTYLE_PERSONAL, "600.00",
            "salon,spa,grooming,personal care,cosmetics"));
        rows.add(row(userId, order++, "SHOPPING", "Shopping", LIFESTYLE_PERSONAL, "1050.00",
            "amazon,flipkart,myntra,ajio,meesho,nykaa,tatacliq,zara,h&m,decathlon,lifestyle,pantaloons"));
        rows.add(row(userId, order++, "PARTIES_OUTINGS", "Parties / Outings", LIFESTYLE_PERSONAL, "1750.00",
            "party,outing,pvr,inox,cinema,bookmyshow"));
        rows.add(row(userId, order++, "GIFTS", "Gifts", LIFESTYLE_PERSONAL, "500.00", "gift,flowers,archies,ferns n petals"));
        rows.add(row(userId, order++, "MEDICINES_HEALTHCARE", "Medicines / Healthcare", HEALTH_MAINTENANCE_MISC, "1000.00",
            "apollo,pharmeasy,1mg,netmeds,hospital,clinic,pharmacy,diagnostic,medic"));
        rows.add(row(userId, order++, "BIKE_MAINTENANCE", "Bike Maintenance", HEALTH_MAINTENANCE_MISC, "600.00",
            "bike service,two wheeler,bike maintenance,royal enfield service,bike garage"));
        rows.add(row(userId, order++, "HOME_APPLIANCE", "Home / Appliance", HEALTH_MAINTENANCE_MISC, "500.00",
            "croma,reliance digital,vijay sales,appliance"));
        PlanCategory misc = row(userId, order++, "MISCELLANEOUS", "Miscellaneous", HEALTH_MAINTENANCE_MISC, "1100.00", "");
        rows.add(misc.toBuilder().fallback(true).build());

        return rows;
    }

    public static List<SinkingFund> seedSinkingFunds(Long userId) {
        List<SinkingFund> funds = new ArrayList<>();
        funds.add(SinkingFund.builder()
            .userId(userId).sortOrder(0).name("Vacation / Trip Fund")
            .monthlyPlanned(new BigDecimal("7500.00")).annualTarget(new BigDecimal("90000.00"))
            .build());
        funds.add(SinkingFund.builder()
            .userId(userId).sortOrder(1).name("Domestic Home Travel Fund")
            .monthlyPlanned(new BigDecimal("3500.00")).annualTarget(new BigDecimal("42000.00"))
            .build());
        return funds;
    }

    private static PlanCategory row(Long userId, int order, String key, String name, String group,
                                     String plannedAmount, String keywords) {
        return PlanCategory.builder()
            .userId(userId).sortOrder(order).key(key).name(name).groupName(group)
            .plannedAmount(new BigDecimal(plannedAmount))
            .keywords(keywords == null || keywords.isBlank() ? null : keywords)
            .build();
    }
}
