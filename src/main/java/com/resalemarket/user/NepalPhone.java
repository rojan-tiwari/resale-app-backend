package com.resalemarket.user;

/**
 * Nepali mobile numbers. People type {@code 9812345678} (or {@code +9779812345678}); the database stores the
 * E.164 form ({@code chk_user_profiles_phone}), so every number is normalised before it is saved.
 */
public final class NepalPhone {

    /** Accepted input: a mobile number starting 96, 97 or 98, with or without the +977 country code. */
    public static final String INPUT_PATTERN = "^(\\+977)?9[678][0-9]{8}$";

    private static final String COUNTRY_CODE = "+977";

    private NepalPhone() {
    }

    /** The E.164 form of a number accepted by {@link #INPUT_PATTERN}; {@code null} for a missing or blank number. */
    public static String normalise(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String number = phone.strip();
        return number.startsWith(COUNTRY_CODE) ? number : COUNTRY_CODE + number;
    }
}
