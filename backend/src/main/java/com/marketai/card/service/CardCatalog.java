package com.marketai.card.service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Built-in catalog of popular Indian credit cards with their effective reward
 * rates per spend category, plus benefits and "best for" guidance.
 * Rates are approximate effective value (%) and used by the recommender.
 */
public final class CardCatalog {

    private CardCatalog() {}

    public static final List<String> CATEGORIES = Arrays.asList(
        "Online Shopping", "Dining", "Groceries", "Fuel",
        "Travel", "Utilities", "Entertainment", "Insurance",
        "UPI", "Subscriptions", "Rent", "Other"
    );

    public static class CatalogCard {
        public String name, issuer, network, bestFor;
        public BigDecimal annualFee, pointValue;
        public List<String> benefits;
        public Map<String, BigDecimal> rewardRates;
        public BigDecimal annualFeeWaiverSpend;   // null means no waiver available or LTF
        public BigDecimal monthlyCashbackCap;     // null means no cap
        public Map<String, BigDecimal> merchantBonusRates; // merchant keyword -> bonus rate %

        CatalogCard(String name, String issuer, String network, String annualFee, String pointValue,
                    String bestFor, List<String> benefits, Map<String, BigDecimal> rates) {
            this.name = name; this.issuer = issuer; this.network = network;
            this.annualFee = new BigDecimal(annualFee); this.pointValue = new BigDecimal(pointValue);
            this.bestFor = bestFor; this.benefits = benefits; this.rewardRates = rates;
            this.annualFeeWaiverSpend = null;
            this.monthlyCashbackCap = null;
            this.merchantBonusRates = Collections.emptyMap();
        }
    }

    /** Set extra metadata on a card after construction. */
    private static CatalogCard withExtras(CatalogCard c, BigDecimal feeWaiverSpend,
                                          BigDecimal cashbackCap, Map<String, BigDecimal> merchantBonuses) {
        c.annualFeeWaiverSpend = feeWaiverSpend;
        c.monthlyCashbackCap = cashbackCap;
        c.merchantBonusRates = merchantBonuses;
        return c;
    }

    private static Map<String, BigDecimal> rates(double online, double dining, double groceries,
                                                 double fuel, double travel, double utilities,
                                                 double entertainment, double insurance,
                                                 double upi, double subscriptions,
                                                 double rent, double other) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("Online Shopping", BigDecimal.valueOf(online));
        m.put("Dining",          BigDecimal.valueOf(dining));
        m.put("Groceries",       BigDecimal.valueOf(groceries));
        m.put("Fuel",            BigDecimal.valueOf(fuel));
        m.put("Travel",          BigDecimal.valueOf(travel));
        m.put("Utilities",       BigDecimal.valueOf(utilities));
        m.put("Entertainment",   BigDecimal.valueOf(entertainment));
        m.put("Insurance",       BigDecimal.valueOf(insurance));
        m.put("UPI",             BigDecimal.valueOf(upi));
        m.put("Subscriptions",   BigDecimal.valueOf(subscriptions));
        m.put("Rent",            BigDecimal.valueOf(rent));
        m.put("Other",           BigDecimal.valueOf(other));
        return m;
    }

    /** Map a merchant name to a spend category. Returns null if no match. */
    public static String merchantToCategory(String merchant) {
        if (merchant == null || merchant.isEmpty()) return null;
        String m = merchant.toLowerCase().trim();
        // Food delivery / Dining
        if (m.contains("swiggy") || m.contains("zomato") || m.contains("eatsure") || m.contains("domino")) return "Dining";
        // Grocery / Quick commerce
        if (m.contains("blinkit") || m.contains("zepto") || m.contains("bigbasket") || m.contains("jiomart") || m.contains("dmart") || m.contains("grofers") || m.contains("dunzo") || m.contains("instamart")) return "Groceries";
        // Online shopping
        if (m.contains("amazon") || m.contains("flipkart") || m.contains("myntra") || m.contains("ajio") || m.contains("meesho") || m.contains("nykaa") || m.contains("tatacliq") || m.contains("croma")) return "Online Shopping";
        // Fuel
        if (m.contains("bpcl") || m.contains("iocl") || m.contains("hpcl") || m.contains("indian oil") || m.contains("petrol") || m.contains("shell") || m.contains("reliance fuel")) return "Fuel";
        // Travel
        if (m.contains("makemytrip") || m.contains("irctc") || m.contains("cleartrip") || m.contains("goibibo") || m.contains("uber") || m.contains("ola") || m.contains("rapido") || m.contains("indigo") || m.contains("spicejet") || m.contains("vistara") || m.contains("air india") || m.contains("booking.com") || m.contains("yatra") || m.contains("easemytrip")) return "Travel";
        // Utilities
        if (m.contains("electricity") || m.contains("water") || m.contains("gas bill") || m.contains("broadband") || m.contains("jio") || m.contains("airtel") || m.contains("vi ") || m.contains("bsnl") || m.contains("tata power") || m.contains("adani")) return "Utilities";
        // Entertainment
        if (m.contains("netflix") || m.contains("hotstar") || m.contains("prime video") || m.contains("bookmyshow") || m.contains("pvr") || m.contains("inox") || m.contains("spotify") || m.contains("youtube")) return "Entertainment";
        // Insurance
        if (m.contains("insurance") || m.contains("lic") || m.contains("policybazaar") || m.contains("star health") || m.contains("hdfc life") || m.contains("sbi life") || m.contains("icici lombard") || m.contains("max life")) return "Insurance";
        // Subscriptions
        if (m.contains("subscription") || m.contains("apple") || m.contains("google play") || m.contains("microsoft") || m.contains("adobe") || m.contains("linkedin")) return "Subscriptions";
        // UPI
        if (m.contains("upi") || m.contains("gpay") || m.contains("phonepe") || m.contains("paytm") || m.contains("google pay")) return "UPI";
        // Rent
        if (m.contains("rent") || m.contains("cred rent") || m.contains("nobroker")) return "Rent";
        return null;
    }

    private static Map<String, BigDecimal> merchantMap(Object... keyVal) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < keyVal.length; i += 2) {
            m.put((String) keyVal[i], BigDecimal.valueOf(((Number) keyVal[i + 1]).doubleValue()));
        }
        return m;
    }

    public static final List<CatalogCard> CARDS;

    static {
        List<CatalogCard> list = new ArrayList<>(Arrays.asList(
            // ── HDFC ──
            new CatalogCard("HDFC Millennia", "HDFC", "Visa", "1000", "1.0",
                "Online shopping on Amazon, Flipkart, Swiggy, Zomato",
                Arrays.asList("5% cashback on Amazon/Flipkart/Swiggy/Zomato & 10 partners",
                    "1% on all other spends", "₹1000 gift voucher on ₹1L/quarter spend",
                    "8 domestic lounge visits/year"),
                rates(5, 5, 2.5, 1, 2.5, 1, 5, 1, 0, 5, 0, 1)),

            new CatalogCard("HDFC Regalia Gold", "HDFC", "Visa", "2500", "0.65",
                "Travel, dining and premium lifestyle",
                Arrays.asList("4 reward points per ₹150", "5X on Marks & Spencer, Reliance, Myntra",
                    "12 domestic + 6 international lounge visits", "₹1500 vouchers on milestones"),
                rates(3, 4, 3, 1.3, 4, 1.3, 3, 1.3, 0, 3, 0, 1.3)),

            new CatalogCard("HDFC Infinia", "HDFC", "Visa", "12500", "1.0",
                "Ultra-premium travel, dining and unlimited lounges",
                Arrays.asList("5 RP per ₹150 (3.3% base)", "10X on SmartBuy (up to 33%)",
                    "Unlimited domestic + international lounge access", "Complimentary golf, Club Marriott"),
                rates(3.3, 5, 3.3, 1.5, 5, 3.3, 5, 3.3, 0, 5, 0, 3.3)),

            new CatalogCard("HDFC Diners Club Black", "HDFC", "Diners", "10000", "1.0",
                "Premium rewards, SmartBuy 10X and lounges",
                Arrays.asList("5 RP per ₹150", "10X on SmartBuy", "Unlimited lounge access",
                    "2 memberships (Amazon Prime, etc.) on milestones"),
                rates(3.3, 5, 3.3, 1.5, 5, 3.3, 5, 3.3, 0, 5, 0, 3.3)),

            new CatalogCard("Tata Neu Infinity HDFC", "HDFC", "RuPay", "1499", "1.0",
                "Tata brands (BigBasket, Croma, 1mg) + UPI",
                Arrays.asList("5% NeuCoins on Tata Neu/partners", "1.5% on others + UPly on RuPay",
                    "Lounge access", "Pay via UPI"),
                rates(3, 2, 5, 2, 2, 2, 2, 1.5, 1.5, 2, 0, 1.5)),

            new CatalogCard("Swiggy HDFC", "HDFC", "Mastercard", "500", "1.0",
                "Food delivery, dining and online spends",
                Arrays.asList("10% cashback on Swiggy", "5% on online spends", "1% on others",
                    "Swiggy One membership"),
                rates(5, 10, 5, 1, 1, 1, 5, 1, 0, 5, 0, 1)),

            new CatalogCard("HDFC MoneyBack+", "HDFC", "Visa", "500", "0.25",
                "Cashback on online and offline spends",
                Arrays.asList("2X on online spends", "1X on offline", "Monthly cashback auto-credit",
                    "Fuel surcharge waiver"),
                rates(2, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1)),

            new CatalogCard("HDFC Freedom", "HDFC", "Visa", "500", "0.25",
                "Dining and grocery rewards",
                Arrays.asList("5X on dining", "5X on grocery", "1X on others",
                    "Fuel surcharge waiver"),
                rates(1, 5, 5, 1, 1, 1, 1, 1, 0, 1, 0, 1)),

            new CatalogCard("IndianOil HDFC", "HDFC", "RuPay", "500", "1.0",
                "Fuel savings at IndianOil outlets",
                Arrays.asList("5% fuel points at IOCL", "5% on groceries/bill pay",
                    "1% on others", "Fuel surcharge waiver"),
                rates(2, 2, 5, 5, 1, 5, 2, 1, 1, 2, 0, 1)),

            new CatalogCard("HDFC Regalia", "HDFC", "Visa", "2500", "0.5",
                "Travel and dining rewards",
                Arrays.asList("4 RP per ₹150 spend", "10X on SmartBuy travel bookings",
                    "12 lounge visits/year", "Insurance cover included"),
                rates(3, 4, 2, 1.3, 5, 1.3, 3, 1.3, 0, 3, 0, 1.3)),

            // ── SBI ──
            new CatalogCard("SBI Cashback", "SBI", "Visa", "999", "1.0",
                "All online spends — no merchant restriction",
                Arrays.asList("5% cashback on ALL online spends (no category cap on merchant)",
                    "1% on offline spends", "Auto-credited cashback", "₹2000 cap/month"),
                rates(5, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1)),

            new CatalogCard("SBI SimplyCLICK", "SBI", "Visa", "499", "0.25",
                "Online shopping for beginners",
                Arrays.asList("10X on Amazon/BookMyShow/Cleartrip", "5X on other online",
                    "₹2000 Amazon voucher on joining", "1% base"),
                rates(5, 1, 1, 1, 2.5, 1, 5, 1, 0, 5, 0, 1)),

            new CatalogCard("SBI Elite", "SBI", "Visa", "4999", "0.25",
                "Movies, travel and lifestyle",
                Arrays.asList("5X on dining/groceries/departmental", "2 movie tickets/month",
                    "6 international + 8 domestic lounge visits", "Milestone e-vouchers"),
                rates(2.5, 5, 5, 1.25, 2.5, 1.25, 5, 1.25, 0, 5, 0, 1.25)),

            new CatalogCard("SBI SimplySave", "SBI", "Visa", "499", "0.25",
                "Dining, movies, grocery rewards",
                Arrays.asList("10X on dining/movies/groceries/departmental",
                    "1% on others", "Fuel surcharge waiver"),
                rates(1, 5, 5, 1, 1, 1, 5, 1, 0, 5, 0, 1)),

            new CatalogCard("BPCL SBI Octane", "SBI", "Visa", "1499", "0.25",
                "Fuel — best-in-class fuel savings",
                Arrays.asList("7.25% value-back on BPCL fuel", "10X on dining/groceries/entertainment",
                    "Fuel surcharge waiver", "Milestone e-vouchers"),
                rates(2.5, 2.5, 2.5, 7.25, 1.25, 1.25, 2.5, 1.25, 0, 2.5, 0, 1.25)),

            new CatalogCard("SBI PRIME", "SBI", "Visa", "2999", "0.25",
                "Movies, dining, and department store rewards",
                Arrays.asList("5% on dining/departmental stores", "Buy 1 Get 1 on movies",
                    "Lounge access", "Fuel surcharge waiver"),
                rates(2.5, 5, 5, 1.25, 2.5, 1.25, 5, 1.25, 0, 5, 0, 1.25)),

            // ── ICICI ──
            new CatalogCard("Amazon Pay ICICI", "ICICI", "Visa", "0", "1.0",
                "Amazon purchases, especially for Prime members",
                Arrays.asList("5% on Amazon (Prime) / 3% (non-Prime)", "2% on 100+ Amazon Pay partners",
                    "1% on all other spends", "Lifetime free — no annual fee"),
                rates(5, 2, 2, 2, 1, 2, 1, 1, 0, 1, 0, 1)),

            new CatalogCard("ICICI Coral", "ICICI", "Visa", "500", "0.25",
                "Everyday spends with lounge access",
                Arrays.asList("2 RP per ₹100", "Movie ticket discounts (BookMyShow)",
                    "4 lounge visits/year", "Fuel surcharge waiver"),
                rates(1, 1.5, 1.5, 1, 1, 1, 2, 1, 0, 2, 0, 1)),

            new CatalogCard("ICICI Sapphiro", "ICICI", "Visa", "3500", "0.5",
                "Premium travel and online spends",
                Arrays.asList("4X on online/travel/entertainment", "2X on all other",
                    "Domestic + intl lounge access", "Golf privileges"),
                rates(4, 2, 2, 2, 4, 2, 4, 2, 0, 4, 0, 2)),

            new CatalogCard("ICICI Rubyx", "ICICI", "Visa", "3000", "0.5",
                "Premium everyday rewards with lounge access",
                Arrays.asList("10 RP per ₹100 on dining", "2 RP per ₹100 elsewhere",
                    "Lounge access", "Movie discounts"),
                rates(2, 5, 2, 2, 2, 2, 3, 2, 0, 3, 0, 2)),

            new CatalogCard("ICICI Emeralde", "ICICI", "Mastercard", "12000", "1.0",
                "Ultra-premium with Taj and lounge privileges",
                Arrays.asList("5% on dining and entertainment", "3% on all other spends",
                    "Unlimited domestic + intl lounges", "Taj InnerCircle Epicure Plus"),
                rates(3, 5, 3, 2, 5, 3, 5, 3, 0, 5, 0, 3)),

            // ── Axis ──
            new CatalogCard("Axis Ace", "Axis", "Visa", "499", "1.0",
                "Utility bills via Google Pay, food delivery",
                Arrays.asList("5% on Google Pay bill payments (electricity, gas, mobile)",
                    "4% on Swiggy/Zomato/Ola", "1.5% unlimited on all other spends",
                    "4 domestic lounge visits/year"),
                rates(1.5, 4, 1.5, 1.5, 1.5, 5, 4, 1.5, 0, 4, 0, 1.5)),

            new CatalogCard("Axis Magnus", "Axis", "Mastercard", "12500", "2.0",
                "High-value travel & dining, airport lounges",
                Arrays.asList("Up to 4.8% reward on general spends", "5% on travel via Travel Edge",
                    "Unlimited domestic + international lounge access", "Transfer points to airline miles"),
                rates(4.8, 5, 4.8, 2, 5, 4.8, 5, 4.8, 0, 5, 0, 4.8)),

            new CatalogCard("Flipkart Axis", "Axis", "Mastercard", "500", "1.0",
                "Flipkart, Myntra and everyday spends",
                Arrays.asList("5% on Flipkart/Cleartrip", "4% on Swiggy/Uber/PVR & preferred partners",
                    "1.5% on all other spends", "4 domestic lounge visits/year"),
                rates(5, 4, 1.5, 1.5, 4, 1.5, 4, 1.5, 0, 4, 0, 1.5)),

            new CatalogCard("Axis Vistara", "Axis", "Visa", "3000", "1.0",
                "Air travel on Vistara with free tickets",
                Arrays.asList("Complimentary Vistara tickets on milestones", "Club Vistara Silver",
                    "Lounge access", "CV points on spends"),
                rates(2, 2, 2, 2, 4, 2, 2, 2, 0, 2, 0, 2)),

            new CatalogCard("Axis MyZone", "Axis", "Visa", "500", "0.25",
                "Everyday spends with movie benefits",
                Arrays.asList("Buy 1 Get 1 on movies (BookMyShow)", "5X on dining",
                    "Fuel surcharge waiver", "EMI conversion"),
                rates(1, 5, 1, 1, 1, 1, 3, 1, 0, 3, 0, 1)),

            new CatalogCard("Airtel Axis", "Axis", "Visa", "500", "1.0",
                "Airtel services and online shopping",
                Arrays.asList("25% on Airtel bill payments", "10% on Swiggy/Zomato/BigBasket",
                    "5% on utility bills", "1% on all other spends"),
                rates(5, 10, 10, 1, 1, 5, 5, 0, 0, 5, 0, 1)),

            // ── Amex ──
            new CatalogCard("Amex Membership Rewards", "Amex", "Amex", "1000", "0.5",
                "Milestone bonuses and reward point stacking",
                Arrays.asList("1 MR point per ₹50", "Milestone: 1000/1500 bonus points",
                    "18% back at partners via 4-9 point redemptions", "Amex Offers"),
                rates(1.5, 1.5, 1.5, 1, 1.5, 1.5, 1.5, 1.5, 0, 1.5, 0, 1.5)),

            new CatalogCard("Amex Platinum Travel", "Amex", "Amex", "5000", "0.5",
                "Travel milestones and lounge access",
                Arrays.asList("Milestone: Taj vouchers + travel benefits", "1 MR per ₹50",
                    "Domestic lounge access", "Strong airline transfer partners"),
                rates(1.5, 1.5, 1.5, 1, 3, 1.5, 1.5, 1.5, 0, 1.5, 0, 1.5)),

            new CatalogCard("Amex SmartEarn", "Amex", "Amex", "495", "0.5",
                "Online shopping and everyday cashback",
                Arrays.asList("10X on Flipkart/Amazon/Uber/Swiggy", "5X on others",
                    "Milestone rewards", "Low annual fee"),
                rates(5, 5, 2, 1, 1, 2, 5, 1, 0, 5, 0, 1)),

            // ── IDFC First ──
            new CatalogCard("IDFC First Millennia", "IDFC First", "Visa", "0", "0.25",
                "Lifetime-free everyday rewards",
                Arrays.asList("Up to 10X on spends above ₹20k/month", "3X on others",
                    "Lifetime free", "Low interest, lounge on spends"),
                rates(2.5, 2.5, 2.5, 1, 2.5, 2.5, 2.5, 1, 0, 2.5, 0, 1)),

            new CatalogCard("IDFC First Classic", "IDFC First", "Visa", "0", "0.25",
                "Lifetime-free entry card",
                Arrays.asList("Up to 3X reward points", "Lifetime free",
                    "Movie discounts", "Fuel surcharge waiver"),
                rates(1.5, 1.5, 1.5, 1, 1.5, 1.5, 1.5, 1, 0, 1.5, 0, 1)),

            new CatalogCard("IDFC First WOW", "IDFC First", "RuPay", "0", "0.25",
                "No-frills cashback card with UPI",
                Arrays.asList("Cashback on all spends", "UPI payments via RuPay",
                    "Lifetime free", "Zero forex markup"),
                rates(1, 1, 1, 1, 1, 1, 1, 1, 1.5, 1, 0, 1)),

            new CatalogCard("IDFC First Select", "IDFC First", "Visa", "999", "0.5",
                "Mid-range travel and lifestyle",
                Arrays.asList("Up to 10X on spends above ₹20k/month", "Lounge access",
                    "Golf privileges", "Milestone benefits"),
                rates(3, 3, 3, 2, 4, 3, 3, 2, 0, 3, 0, 2)),

            // ── HSBC ──
            new CatalogCard("HSBC Live+ Cashback", "HSBC", "Visa", "999", "1.0",
                "Dining, food delivery and groceries cashback",
                Arrays.asList("10% on dining/food-delivery/groceries", "1.5% on all else",
                    "4 lounge visits/year", "Cashback auto-credited"),
                rates(1.5, 10, 10, 1.5, 1.5, 1.5, 5, 1.5, 0, 5, 0, 1.5)),

            new CatalogCard("HSBC Cashback", "HSBC", "Visa", "750", "1.0",
                "Flat 1.5% cashback on everything",
                Arrays.asList("1.5% unlimited cashback", "No category restrictions",
                    "Fuel surcharge waiver", "Easy auto-credit"),
                rates(1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 0, 1.5, 0, 1.5)),

            // ── Kotak ──
            new CatalogCard("Kotak League Platinum", "Kotak", "Visa", "499", "0.25",
                "Everyday spends and fuel waiver",
                Arrays.asList("4 RP per ₹500 (8X on select)", "Fuel surcharge waiver",
                    "Lounge access", "Movie offers"),
                rates(1.5, 1.5, 1.5, 1, 1.5, 1.5, 2, 1, 0, 2, 0, 1)),

            new CatalogCard("Kotak 811 Dream Different", "Kotak", "Visa", "0", "0.25",
                "Lifetime-free starter card",
                Arrays.asList("1% cashback on all spends", "Lifetime free",
                    "Movie discounts", "Fuel surcharge waiver"),
                rates(1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1)),

            // ── IndusInd ──
            new CatalogCard("IndusInd Legend", "IndusInd", "Visa", "0", "0.7",
                "Lifetime-free premium with lounge access",
                Arrays.asList("2.5% weekend reward points", "Lifetime free",
                    "Lounge access", "Concierge, golf"),
                rates(1.5, 2.5, 1.5, 1, 2, 1.5, 2.5, 1, 0, 2.5, 0, 1)),

            new CatalogCard("IndusInd Tiger", "IndusInd", "Mastercard", "599", "0.25",
                "Weekend and entertainment rewards",
                Arrays.asList("2.5% on weekend spends", "Movie benefits",
                    "Fuel surcharge waiver", "Milestone rewards"),
                rates(1, 2.5, 1, 1, 1, 1, 2.5, 1, 0, 2.5, 0, 1)),

            new CatalogCard("IndusInd Pinnacle", "IndusInd", "Visa", "12000", "1.0",
                "Ultra-premium lifestyle and travel",
                Arrays.asList("Up to 5% rewards", "Unlimited domestic + intl lounges",
                    "Golf + spa", "Concierge"),
                rates(3, 5, 3, 2, 5, 3, 5, 3, 0, 5, 0, 3)),

            // ── Standard Chartered ──
            new CatalogCard("Standard Chartered Ultimate", "Standard Chartered", "Visa", "5000", "1.0",
                "High-reward travel and dining",
                Arrays.asList("5% cashback on top 2 spend categories", "1% on others",
                    "8 lounge visits/year", "Fuel surcharge waiver"),
                rates(3, 5, 3, 2, 5, 3, 5, 1, 0, 5, 0, 1)),

            new CatalogCard("Standard Chartered Super Value Titanium", "Standard Chartered", "Visa", "750", "0.25",
                "Cashback on utility and grocery spends",
                Arrays.asList("5% cashback on supermarket/grocery/utility/telecom",
                    "1% on others", "Fuel surcharge waiver"),
                rates(1, 1, 5, 2, 1, 5, 1, 1, 0, 1, 0, 1)),

            // ── Scapia ──
            new CatalogCard("Scapia", "Scapia", "Visa", "0", "1.0",
                "Travel rewards — 10% on flights, hotels",
                Arrays.asList("10% back on flights & hotels via Scapia app", "1% on all other spends",
                    "No forex markup (zero FX fee)", "Lifetime free", "Instant card via app"),
                rates(1, 1, 1, 1, 10, 1, 1, 1, 0, 1, 0, 1)),

            // ── AU Bank ──
            new CatalogCard("AU LIT", "AU Bank", "RuPay", "0", "0.25",
                "Customizable rewards — pick your top 3 categories",
                Arrays.asList("Up to 5% on 3 self-selected categories", "1% on all others",
                    "Lifetime free", "UPI via RuPay", "Customizable benefits"),
                rates(3, 3, 3, 3, 3, 3, 3, 1, 2, 3, 0, 1)),

            new CatalogCard("AU Vetta", "AU Bank", "Visa", "2999", "0.5",
                "Premium travel and dining",
                Arrays.asList("Up to 7.5% on dining", "3.5% on travel", "Lounge access",
                    "Golf privileges", "Concierge"),
                rates(2, 7.5, 2, 2, 3.5, 2, 3, 2, 0, 3, 0, 2)),

            new CatalogCard("AU Altura", "AU Bank", "Visa", "199", "0.25",
                "Everyday spends with milestone benefits",
                Arrays.asList("1% cashback on all spends", "5% on utility bills",
                    "Fuel surcharge waiver", "Milestone rewards"),
                rates(1, 1, 1, 1, 1, 5, 1, 1, 0, 1, 0, 1)),

            new CatalogCard("AU Zenith+", "AU Bank", "Mastercard", "7999", "1.0",
                "Ultra-premium dining and lifestyle",
                Arrays.asList("Up to 12% on dining", "6% on international spends",
                    "Unlimited lounge access", "Buy 1 Get 1 movie tickets"),
                rates(3, 12, 3, 2, 5, 3, 5, 3, 0, 5, 0, 3)),

            // ── OneCard ──
            new CatalogCard("OneCard", "OneCard", "Visa", "0", "1.0",
                "Metal card with 5X on top spend categories",
                Arrays.asList("5X rewards on top 2 spend categories (auto-detected)",
                    "1X on all else", "Lifetime free metal card", "Instant approval via app"),
                rates(3, 5, 3, 2, 3, 3, 5, 1, 0, 5, 0, 1)),

            // ── Uni Card ──
            new CatalogCard("Uni Pay 1/3rd", "Uni", "Visa", "0", "1.0",
                "Pay 1/3rd every month — automatic 3-month split",
                Arrays.asList("Split every purchase into 3 — auto, no interest",
                    "1% cashback on everything", "Lifetime free", "Instant digital card"),
                rates(1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1)),

            // ── Fi (Tiger) ──
            new CatalogCard("Fi Federal", "Federal Bank", "Visa", "0", "1.0",
                "Fi money app credit card with Fi-Coins",
                Arrays.asList("Up to 5% Fi-Coins on select brands", "2% on others",
                    "Lifetime free via Fi app", "Smart money management"),
                rates(2, 2, 2, 1, 2, 2, 5, 2, 0, 5, 0, 2)),

            // ── Yes Bank ──
            new CatalogCard("Yes First Exclusive", "Yes Bank", "Visa", "10000", "1.0",
                "Premium travel with milestone benefits",
                Arrays.asList("Up to 12 RP per ₹200 on travel/dining", "6 RP on others",
                    "Unlimited domestic + intl lounge", "Golf + concierge"),
                rates(3, 6, 3, 2, 6, 3, 5, 3, 0, 5, 0, 3)),

            new CatalogCard("Yes First Preferred", "Yes Bank", "Visa", "4999", "0.5",
                "Travel and dining rewards",
                Arrays.asList("8 RP per ₹200 on travel/dining", "2 RP on others",
                    "Lounge access", "Fuel surcharge waiver"),
                rates(2, 4, 2, 1, 4, 2, 3, 1, 0, 3, 0, 1)),

            new CatalogCard("Yes Ace", "Yes Bank", "Visa", "499", "1.0",
                "Cashback on Amazon and online spends",
                Arrays.asList("5% on Amazon", "2% on online spends", "1% on others",
                    "Low annual fee"),
                rates(5, 2, 2, 1, 1, 2, 2, 1, 0, 2, 0, 1)),

            // ── RBL Bank ──
            new CatalogCard("RBL Shoprite", "RBL", "Mastercard", "500", "0.25",
                "Grocery and online shopping rewards",
                Arrays.asList("5% on grocery/supermarket", "2% on online spends",
                    "Movie offers", "Fuel surcharge waiver"),
                rates(2, 1, 5, 1, 1, 2, 2, 1, 0, 2, 0, 1)),

            new CatalogCard("RBL Icon", "RBL", "Visa", "3500", "0.5",
                "Premium with lounge and travel",
                Arrays.asList("4 RP per ₹100", "Domestic + intl lounge access",
                    "Golf privileges", "Milestone vouchers"),
                rates(2, 3, 2, 2, 4, 2, 3, 2, 0, 3, 0, 2)),

            // ── Federal Bank ──
            new CatalogCard("Federal Bank Celesta", "Federal Bank", "Visa", "1999", "0.5",
                "Premium lifestyle and travel",
                Arrays.asList("4X on dining/travel/entertainment", "2X on all else",
                    "Lounge access", "Fuel surcharge waiver"),
                rates(2, 4, 2, 2, 4, 2, 4, 2, 0, 4, 0, 2)),

            // ── PNB ──
            new CatalogCard("PNB RuPay Platinum", "PNB", "RuPay", "0", "0.25",
                "Basic rewards with UPI and RuPay benefits",
                Arrays.asList("1 RP per ₹100", "UPI via RuPay", "Fuel surcharge waiver",
                    "Low/no annual fee"),
                rates(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1)),

            // ── BOB ──
            new CatalogCard("BOB Eterna", "Bank of Baroda", "Visa", "2499", "0.5",
                "Travel and dining rewards",
                Arrays.asList("5X on dining and travel", "2X on others",
                    "Lounge access", "Fuel surcharge waiver"),
                rates(2, 5, 2, 2, 5, 2, 3, 2, 0, 3, 0, 2)),

            // ── New cards ──
            new CatalogCard("Kiwi", "Kiwi", "RuPay", "0", "1.0",
                "UPI rewards on RuPay credit card",
                Arrays.asList("1.5% on UPI payments via RuPay", "1% on all other spends",
                    "Lifetime free", "Instant card via app"),
                rates(1, 1, 1, 1, 1, 1, 1, 1, 1.5, 1, 0, 1)),

            new CatalogCard("Slice", "Slice", "Visa", "0", "1.0",
                "Instant cashback on all spends",
                Arrays.asList("Up to 2% instant cashback", "Pay later in 3 monthly installments",
                    "Lifetime free", "Instant approval via app"),
                rates(2, 2, 2, 2, 2, 2, 2, 2, 0, 2, 0, 2)),

            new CatalogCard("CRED Mint RuPay", "CRED", "RuPay", "0", "1.0",
                "UPI credit card with CRED rewards",
                Arrays.asList("CRED coins on all spends", "UPI payments via RuPay",
                    "Lifetime free via CRED app", "Up to 2% rewards via CRED"),
                rates(1.5, 1.5, 1.5, 1, 1.5, 1.5, 1.5, 1, 2, 1.5, 0, 1))
        ));

        CARDS = Collections.unmodifiableList(list);

        // ── Apply extras (fee waiver, cashback cap, merchant bonuses) ──
        for (CatalogCard c : list) {
            String n = c.name;
            if ("HDFC Millennia".equals(n)) {
                withExtras(c, new BigDecimal("100000"), new BigDecimal("1000"),
                    merchantMap("amazon", 5, "flipkart", 5, "swiggy", 5, "zomato", 5));
            } else if ("SBI Cashback".equals(n)) {
                withExtras(c, new BigDecimal("200000"), new BigDecimal("2000"), Collections.<String, BigDecimal>emptyMap());
            } else if ("Amazon Pay ICICI".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("amazon", 5));
            } else if ("Axis Ace".equals(n)) {
                withExtras(c, new BigDecimal("200000"), null,
                    merchantMap("gpay", 5, "google pay", 5, "swiggy", 4, "zomato", 4, "ola", 4));
            } else if ("HDFC Regalia Gold".equals(n)) {
                withExtras(c, new BigDecimal("300000"), null, Collections.<String, BigDecimal>emptyMap());
            } else if ("SBI SimplyCLICK".equals(n)) {
                withExtras(c, new BigDecimal("100000"), new BigDecimal("1500"),
                    merchantMap("amazon", 10, "bookmyshow", 10, "cleartrip", 10));
            } else if ("HSBC Live+ Cashback".equals(n)) {
                withExtras(c, null, new BigDecimal("750"), Collections.<String, BigDecimal>emptyMap());
            } else if ("Swiggy HDFC".equals(n)) {
                withExtras(c, new BigDecimal("100000"), new BigDecimal("500"),
                    merchantMap("swiggy", 10));
            } else if ("Flipkart Axis".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("flipkart", 5, "cleartrip", 5, "swiggy", 4, "uber", 4));
            } else if ("Tata Neu Infinity HDFC".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("bigbasket", 5, "croma", 5, "1mg", 5, "tatacliq", 5));
            } else if ("BPCL SBI Octane".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("bpcl", 7.25));
            } else if ("IndianOil HDFC".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("indianoil", 5, "iocl", 5));
            } else if ("Scapia".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("makemytrip", 10, "cleartrip", 10, "booking", 10));
            } else if ("Yes Ace".equals(n)) {
                withExtras(c, null, null,
                    merchantMap("amazon", 5));
            }
        }
    }

    public static Optional<CatalogCard> byName(String name) {
        for (CatalogCard c : CARDS) {
            if (c.name.equalsIgnoreCase(name)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
