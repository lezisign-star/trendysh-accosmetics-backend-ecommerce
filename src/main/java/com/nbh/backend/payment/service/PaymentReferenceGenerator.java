package com.nbh.backend.payment.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Short per-order payment reference the customer types into a bank paybill
 * account field or transfer narration, e.g. {@code TR7K4M2}.
 *
 * <p>Alphabet is digits plus consonants (no vowels, so no accidental words, and
 * no I/L/O/U to confuse with 1/0). Six random characters plus one check
 * character: the check uses weights coprime to the alphabet size, so any
 * single mistyped character is detected, which lets the matcher tell "typo'd
 * reference" apart from "reference that never existed". Random rather than
 * counter-derived so a reference is not guessable from an order number — it
 * doubles as the bearer secret for the public payment-status page.
 */
@Component
public class PaymentReferenceGenerator {

    static final String ALPHABET = "0123456789BCDFGHJKMNPQRSTVWXYZ";
    private static final int BODY_LENGTH = 6;
    /** Each coprime to 30 (the alphabet size), so a single-character change always changes the check value. */
    private static final int[] WEIGHTS = {1, 7, 11, 13, 17, 19};

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder body = new StringBuilder(BODY_LENGTH + 1);
        for (int i = 0; i < BODY_LENGTH; i++) {
            body.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return body.append(checkChar(body.toString())).toString();
    }

    /**
     * Upper-cases, drops spaces/hyphens and maps the look-alikes the alphabet
     * excludes (O to 0, I/L to 1), returning null for anything that is not a
     * well-formed reference (wrong length, foreign character, bad check digit).
     */
    public String normaliseAndValidate(String input) {
        if (input == null) {
            return null;
        }
        String cleaned = input.toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        if (cleaned.length() != BODY_LENGTH + 1) {
            return null;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            if (ALPHABET.indexOf(cleaned.charAt(i)) < 0) {
                return null;
            }
        }
        String body = cleaned.substring(0, BODY_LENGTH);
        return checkChar(body) == cleaned.charAt(BODY_LENGTH) ? cleaned : null;
    }

    private static char checkChar(String body) {
        int sum = 0;
        for (int i = 0; i < BODY_LENGTH; i++) {
            sum += WEIGHTS[i] * ALPHABET.indexOf(body.charAt(i));
        }
        return ALPHABET.charAt(sum % ALPHABET.length());
    }
}
