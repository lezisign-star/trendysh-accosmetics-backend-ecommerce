package com.nbh.backend.packages.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a shopper picked inside a package, as a map from the DEFAULT variant of a product in the package to the variant they chose
 * instead (a package's component rows are replaced on every save, but a product is in a package once, so its default variant is
 * the stable key). Only picks that differ from the default are kept, so "no picks" is the empty map.
 */
public final class PackageChoices {

    private PackageChoices() {}

    /**
     * Identifies a set of picks: empty for none, else a digest of the picks in a fixed order, so the same picks always
     * make the same key and the same package with different picks is a different basket line.
     */
    public static String keyOf(Map<UUID, UUID> choices) {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        String joined = choices.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ">" + e.getValue())
                .collect(Collectors.joining(";"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is part of every Java runtime", e);
        }
    }
}
