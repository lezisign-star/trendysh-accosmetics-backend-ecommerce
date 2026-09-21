package com.nbh.backend.payment.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReferenceGeneratorTest {

    private final PaymentReferenceGenerator generator = new PaymentReferenceGenerator();

    @Test
    void generatedReferencesAreSevenCharsFromTheVowelFreeAlphabetAndSelfValidate() {
        for (int i = 0; i < 2000; i++) {
            String ref = generator.generate();
            assertThat(ref).hasSize(7);
            assertThat(ref.chars().allMatch(c -> PaymentReferenceGenerator.ALPHABET.indexOf(c) >= 0)).isTrue();
            assertThat(generator.normaliseAndValidate(ref)).isEqualTo(ref);
        }
    }

    @Test
    void generatedReferencesDoNotRepeatInAReasonableSample() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSizeGreaterThan(4990);
    }

    @Test
    void anySingleMistypedCharacterIsRejected() {
        String ref = generator.generate();
        for (int pos = 0; pos < ref.length(); pos++) {
            for (char replacement : PaymentReferenceGenerator.ALPHABET.toCharArray()) {
                if (replacement == ref.charAt(pos)) {
                    continue;
                }
                String typo = ref.substring(0, pos) + replacement + ref.substring(pos + 1);
                assertThat(generator.normaliseAndValidate(typo))
                        .as("typo %s of %s at %d", typo, ref, pos).isNull();
            }
        }
    }

    @Test
    void normalisationForgivesCaseSpacesHyphensAndLookAlikes() {
        String ref = generator.generate();
        assertThat(generator.normaliseAndValidate(" " + ref.toLowerCase().substring(0, 3) + "-" + ref.substring(3) + " "))
                .isEqualTo(ref);
    }

    @Test
    void malformedInputIsRejected() {
        assertThat(generator.normaliseAndValidate(null)).isNull();
        assertThat(generator.normaliseAndValidate("")).isNull();
        assertThat(generator.normaliseAndValidate("SHORT")).isNull();
        assertThat(generator.normaliseAndValidate("TOOLONGREF1")).isNull();
        assertThat(generator.normaliseAndValidate("AAAAAAA")).isNull(); // vowels are not in the alphabet
    }
}
