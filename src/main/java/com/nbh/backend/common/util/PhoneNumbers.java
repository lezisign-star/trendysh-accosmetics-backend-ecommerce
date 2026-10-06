package com.nbh.backend.common.util;

/**
 * Kenyan mobile numbers reach us in several spellings ("0720 000 111",
 * "+254720000111", "254720000111") for the same subscriber. Everything that
 * stores or compares a customer's phone goes through here so the spellings
 * agree — and 254XXXXXXXXX is also the form the M-PESA API expects.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** Strips spaces/dashes/brackets and rewrites a local 0… or +254… number as 254…; anything else is returned cleaned but otherwise untouched. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) {
            return "254" + digits.substring(1);
        }
        return digits;
    }

    /**
     * The same rule the storefront's forms apply (validators.ts {@code isValidKenyanPhone}): a mobile number as
     * 07xx / 01xx, +2547xx / +2541xx or 2547xx, with spaces and dashes allowed because that is how people write them.
     */
    public static boolean isValidKenyanMobile(String raw) {
        return raw != null && raw.replaceAll("[\\s-]", "").matches("(?:\\+?254|0)[17]\\d{8}");
    }

    /**
     * For substring search over stored (normalised) numbers: staff type "0720…"
     * or "+254720…", stored numbers read "254720…", so a leading 0 is dropped and
     * the rest is matched as a fragment. Null when the text isn't phone-shaped.
     */
    public static String searchFragment(String q) {
        if (q == null || !q.matches("[0-9+\\-\\s()]+")) {
            return null;
        }
        String digits = q.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        return digits.startsWith("0") ? digits.substring(1) : digits;
    }

    public static boolean sameNumber(String a, String b) {
        String na = normalize(a);
        return na != null && !na.isEmpty() && na.equals(normalize(b));
    }
}
