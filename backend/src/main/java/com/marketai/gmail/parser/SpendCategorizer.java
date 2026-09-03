package com.marketai.gmail.parser;

import com.marketai.expense.entity.ExpenseCategory;

/**
 * Infers an expense category from merchant / narration text found in
 * bank & card transaction alerts. Categories match the Expense section.
 */
public final class SpendCategorizer {

    private SpendCategorizer() {}

    public static ExpenseCategory categorize(String text) {
        if (text == null) return ExpenseCategory.UNCATEGORIZED;
        String t = text.toLowerCase();

        if (has(t, "sip", "mutual fund", "groww", "zerodha", "coin", "nps", "ppf", "elss", "investment"))
            return ExpenseCategory.INVESTMENT; // checked first: must never fall through to a spend category

        if (has(t, "swiggy", "zomato", "eatfit", "dominos", "pizza", "mcdonald", "kfc", "restaurant",
                 "cafe", "starbucks", "bakery", "biryani", "food",
                 "bigbasket", "blinkit", "zepto", "grofers", "dmart", "d-mart", "instamart",
                 "jiomart", "reliance fresh", "supermarket", "grocery", "kirana"))
            return ExpenseCategory.FOOD;

        if (has(t, "uber", "ola", "rapido", "irctc", "makemytrip", "goibibo", "cleartrip", "indigo",
                 "vistara", "air india", "spicejet", "redbus", "ixigo", "flight", "railway", "metro", "namma yatri"))
            return ExpenseCategory.TRAVEL;

        if (has(t, "petrol", "diesel", "fuel", "hpcl", "iocl", "bpcl", "shell", "indian oil", "bharat petroleum", "fuel station"))
            return ExpenseCategory.FUEL;

        if (has(t, "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tatacliq", "shopping",
                 "lifestyle", "pantaloons", "zara", "h&m", "decathlon"))
            return ExpenseCategory.SHOPPING;

        if (has(t, "electricity", "bescom", "adani electricity", "tata power", "bses", "water bill",
                 "gas", "indane", "hp gas", "broadband", "airtel", "jio", "vodafone", "vi ", "recharge",
                 "postpaid", "bill payment", "utility", "dth", "wifi"))
            return ExpenseCategory.BILLS;

        if (has(t, "netflix", "hotstar", "disney", "prime video", "spotify", "youtube premium", "sony liv",
                 "zee5", "bookmyshow", "pvr", "inox", "cinema", "gaming", "playstation"))
            return ExpenseCategory.ENTERTAINMENT;

        if (has(t, "apollo", "pharmeasy", "1mg", "netmeds", "hospital", "clinic", "diagnostic", "medic",
                 "pharmacy", "dental", "practo", "cult.fit", "cultfit", "gym"))
            return ExpenseCategory.MEDICAL;

        if (has(t, "emi", "loan", "home loan", "car loan", "personal loan", "credit card payment", "nach"))
            return ExpenseCategory.EMI;

        if (has(t, "upi", "gpay", "google pay", "phonepe", "paytm"))
            return ExpenseCategory.UPI;

        return ExpenseCategory.UNCATEGORIZED;
    }

    /** Extract a human merchant name from bank/UPI narration text. */
    public static String extractMerchant(String text) {
        if (text == null) return null;

        // 0. Known merchant names first (highest confidence, matches branded names)
        String lc = text.toLowerCase();
        for (String[] pair : KNOWN_MERCHANTS) {
            if (lc.contains(pair[0])) return pair[1];
        }

        // 1. Try "at/to/towards/for MERCHANT" pattern (most bank narrations)
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:at|to|towards|for|paid to|payment to|sent to|transferred to|debited for)\\s+([A-Za-z0-9&.\\-* ]{3,50}?)(?:\\s+on|\\s+via|\\s+ref|\\.|,|;|\\n|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
        if (m.find()) {
            String raw = m.group(1).trim();
            String cleaned = cleanMerchant(raw);
            if (cleaned != null) return cleaned;
        }

        // 2. Try UPI VPA patterns: "VPA merchant@ybl" / "UPI: merchant.name@oksbi"
        m = java.util.regex.Pattern
            .compile("(?:VPA|UPI|UPI/)[:\\s]*([A-Za-z0-9._]+)@", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
        if (m.find()) {
            String vpaUser = m.group(1).trim();
            String cleaned = cleanMerchant(vpaUser.replace(".", " ").replace("_", " "));
            if (cleaned != null) return cleaned;
        }

        // 3. Try "Info: MERCHANT" or "Remarks: MERCHANT" (HDFC/ICICI style)
        m = java.util.regex.Pattern
            .compile("(?:Info|Remarks|Narration|Particulars|Desc)[:\\s]+([A-Za-z0-9&.\\-/ ]{3,60}?)(?:\\s+Ref|\\.|,|;|\\n|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
        if (m.find()) {
            String raw = m.group(1).trim();
            String cleaned = cleanMerchant(raw);
            if (cleaned != null) return cleaned;
        }

        return null;
    }

    private static final String[][] KNOWN_MERCHANTS = {
        {"swiggy", "Swiggy"}, {"zomato", "Zomato"}, {"blinkit", "Blinkit"},
        {"zepto", "Zepto"}, {"bigbasket", "BigBasket"}, {"instamart", "Swiggy Instamart"},
        {"amazon", "Amazon"}, {"flipkart", "Flipkart"}, {"myntra", "Myntra"},
        {"ajio", "AJIO"}, {"meesho", "Meesho"}, {"nykaa", "Nykaa"}, {"tatacliq", "Tata CLiQ"},
        {"uber", "Uber"}, {"ola cabs", "Ola"}, {"rapido", "Rapido"}, {"namma yatri", "Namma Yatri"},
        {"irctc", "IRCTC"}, {"makemytrip", "MakeMyTrip"}, {"goibibo", "Goibibo"},
        {"cleartrip", "Cleartrip"}, {"indigo", "IndiGo"}, {"ixigo", "ixigo"},
        {"netflix", "Netflix"}, {"hotstar", "Disney+ Hotstar"}, {"spotify", "Spotify"},
        {"youtube premium", "YouTube Premium"}, {"bookmyshow", "BookMyShow"},
        {"prime video", "Amazon Prime Video"}, {"zee5", "ZEE5"}, {"sonyliv", "SonyLIV"},
        {"bharatpe", "BharatPe"}, {"phonepe", "PhonePe"}, {"paytm", "Paytm"}, {"gpay", "Google Pay"},
        {"google pay", "Google Pay"}, {"amazonpay", "Amazon Pay"},
        {"airtel", "Airtel"}, {"jio", "Jio"}, {"vodafone", "Vodafone"}, {"vi ", "Vi"},
        {"bescom", "BESCOM"}, {"tata power", "Tata Power"}, {"adani electricity", "Adani Electricity"},
        {"apollo", "Apollo"}, {"pharmeasy", "PharmEasy"}, {"1mg", "1mg"}, {"practo", "Practo"},
        {"cultfit", "Cult.fit"}, {"cult.fit", "Cult.fit"},
        {"decathlon", "Decathlon"}, {"starbucks", "Starbucks"}, {"dominos", "Domino's"},
        {"mcdonald", "McDonald's"}, {"kfc", "KFC"}, {"dunzo", "Dunzo"}, {"burger king", "Burger King"},
        {"pizza hut", "Pizza Hut"}, {"subway", "Subway"}, {"haldiram", "Haldiram's"},
        {"dmart", "DMart"}, {"d-mart", "DMart"}, {"reliance fresh", "Reliance Fresh"}, {"cred", "CRED"},
        {"lenskart", "Lenskart"}, {"boat", "boAt"}, {"noise", "Noise"},
        {"zara", "Zara"}, {"h&m", "H&M"}, {"pantaloons", "Pantaloons"},
        {"reliance digital", "Reliance Digital"}, {"croma", "Croma"}, {"vijay sales", "Vijay Sales"},
        {"jiomart", "JioMart"}, {"grofers", "Blinkit"},
        {"eatfit", "EatFit"}, {"box8", "Box8"}, {"faasos", "Faasos"},
    };

    private static String cleanMerchant(String raw) {
        if (raw == null) return null;
        raw = raw.replaceAll("\\*+", "").replaceAll("\\s+", " ").trim();
        if (raw.length() < 2) return null;
        // Skip if it's just a generic word
        String lc = raw.toLowerCase();
        if (lc.equals("bank") || lc.equals("upi") || lc.equals("account") || lc.equals("payment")
            || lc.equals("transaction") || lc.equals("debit") || lc.equals("credit")) return null;
        // Title-case the first letter
        return raw.substring(0, 1).toUpperCase() + raw.substring(1);
    }

    private static boolean has(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }
}
