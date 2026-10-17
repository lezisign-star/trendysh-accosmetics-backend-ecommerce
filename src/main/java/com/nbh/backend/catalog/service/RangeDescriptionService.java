package com.nbh.backend.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiTextClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * AI-written descriptions for a range's variants (owner, 2026-10-17: "AI will make each description a bit custom for
 * each variant while still being similar among the variants"). Each is the range's shared description told again for
 * that variant, with a small touch of its own (its shade, code or size), and no fact that isn't in the shared text or
 * the variant's name. {@link #write} only drafts them for staff to read and change; {@link #save} stores what they keep.
 */
@Slf4j
@Service
public class RangeDescriptionService {

    /** Variants per AI call: longer texts than a one-line option, so fewer per answer. */
    static final int CHUNK = 10;
    /** Variants per press of "Write descriptions": the rest wait for the next press. */
    public static final int MAX_PER_WRITE = 100;
    public static final int MAX_DESCRIPTION = 1500;

    public record Draft(UUID variantId, String name, String description, String error) {}

    public record WriteRequest(List<UUID> variantIds, String note) {}

    public record Item(UUID variantId, String description) {}

    public record SaveRequest(List<Item> items) {}

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final GeminiTextClient gemini;
    private final ImageCleanupConfig config;
    private final ApplicationEventPublisher events;

    public RangeDescriptionService(ProductRepository products, ProductVariantRepository variants, GeminiTextClient gemini,
                                   ImageCleanupConfig config, ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.gemini = gemini;
        this.config = config;
        this.events = events;
    }

    /**
     * Drafts descriptions for the variants asked for (none named = every variant without its own description), from the
     * range's shared description. Writes nothing.
     */
    public List<Draft> write(UUID productId, WriteRequest r) {
        Product p = products.findById(productId).filter(x -> !x.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such range."));
        String shared = p.getDescription() == null ? "" : p.getDescription().trim();
        if (shared.isEmpty()) {
            throw new UnprocessableException("Write the range's shared description first; each variant's is made from it.");
        }
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("The AI isn't set up yet — add the GEMINI_API_KEY before writing descriptions.");
        }
        List<ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .filter(v -> !v.isKit()).toList();
        Set<UUID> asked = r == null || r.variantIds() == null || r.variantIds().isEmpty() ? null : new HashSet<>(r.variantIds());
        List<ProductVariant> chosen = live.stream()
                .filter(v -> asked != null ? asked.contains(v.getId()) : v.getDescription() == null || v.getDescription().isBlank())
                .limit(MAX_PER_WRITE).toList();
        if (chosen.isEmpty()) {
            throw new UnprocessableException(asked == null
                    ? "Every variant already has its own description. Tick the ones to write again."
                    : "Tick the variants to write descriptions for.");
        }
        String note = r == null ? null : RangeService.clean(r.note());

        List<Draft> out = new ArrayList<>();
        boolean busy = false;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<UUID, String>>> calls = new ArrayList<>();
            List<List<ProductVariant>> parts = new ArrayList<>();
            for (int from = 0; from < chosen.size(); from += CHUNK) {
                List<ProductVariant> part = chosen.subList(from, Math.min(chosen.size(), from + CHUNK));
                parts.add(part);
                calls.add(pool.submit(() -> ask(p.getName(), shared, note, part)));
            }
            for (int i = 0; i < calls.size(); i++) {
                Map<UUID, String> texts;
                String error = null;
                try {
                    texts = calls.get(i).get();
                } catch (ExecutionException e) {
                    texts = Map.of();
                    if (e.getCause() instanceof GeminiImageClient.GeminiException g && g.isQuotaExceeded()) {
                        busy = true;
                        error = "The AI is busy at the moment. Try these again in a few minutes.";
                    } else {
                        log.info("Writing range descriptions failed: {}", e.getCause().getMessage());
                        error = "Not written this time. Try again.";
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnprocessableException("That didn't finish. Try again.");
                }
                for (ProductVariant v : parts.get(i)) {
                    String text = texts.get(v.getId());
                    out.add(new Draft(v.getId(), v.getVariantName(), text,
                            text == null ? (error != null ? error : "Not written this time. Try again.") : null));
                }
            }
        }
        if (busy && out.stream().allMatch(d -> d.description() == null)) {
            throw new UnprocessableException("The AI is busy at the moment (its limit for now is used up). Try again in a few minutes.");
        }
        return out;
    }

    private Map<UUID, String> ask(String rangeName, String shared, String note, List<ProductVariant> part) {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of("index", Map.of("type", "INTEGER"), "description", str),
                "required", List.of("index", "description"));
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("variants", Map.of("type", "ARRAY", "items", item)), "required", List.of("variants"));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < part.size(); i++) {
            ProductVariant v = part.get(i);
            list.append(i).append(": \"").append(v.getVariantName()).append('"');
            if (v.getOptionColor() != null) list.append(", colour \"").append(v.getOptionColor()).append('"');
            if (v.getOptionSize() != null) list.append(", size \"").append(v.getOptionSize()).append('"');
            list.append('\n');
        }
        String prompt = "You write product descriptions for Trendysh Accosmetic, a beauty supplies shop in Nairobi, Kenya. "
                + "These are variants of one range, \"" + rangeName + "\", sold as separate products. The range's shared "
                + "description is:\n\"\"\"\n" + clip(shared, 3000) + "\n\"\"\"\n\n"
                + "For each numbered variant below, write its own description: the shared description told again for this "
                + "variant, about as long, keeping its facts, structure and tone, with a small touch of its own (its shade, "
                + "colour code or size, and what that shade suits). Every variant's text must read a little differently "
                + "from the others, but stay clearly part of the same range. Use only facts from the shared description, "
                + "the shop's note and the variant's name; never invent ingredients, sizes, claims, finishes or features. "
                + "No emojis, no hype words, no price. At most " + MAX_DESCRIPTION + " characters each.\n\n"
                + "Variants:\n" + list
                + (note == null ? "" : "\nShop's note (treat as true): \"" + clip(note, 1000) + "\"\n")
                + "\nReturn each variant's index and description. Answer with the JSON object only.";
        JsonNode a = gemini.ask(null, prompt, schema);
        Map<UUID, String> out = new HashMap<>();
        for (JsonNode n : a.path("variants")) {
            int i = n.path("index").asInt(-1);
            if (i < 0 || i >= part.size()) continue;
            String d = n.path("description").asText("").trim();
            if (d.length() > MAX_DESCRIPTION) d = d.substring(0, MAX_DESCRIPTION).trim();
            if (!d.isEmpty()) out.putIfAbsent(part.get(i).getId(), d);
        }
        return out;
    }

    /**
     * Stores the descriptions staff kept. The range must be confirmed; it is set to let descriptions differ, since each
     * of these variants now has its own.
     */
    @Transactional
    public int save(UUID productId, SaveRequest r) {
        Product p = products.findById(productId).filter(x -> !x.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such range."));
        if (p.getRangeReviewedAt() == null) {
            throw new UnprocessableException("Confirm the range's configuration first.");
        }
        if (r == null || r.items() == null || r.items().isEmpty()) {
            throw new UnprocessableException("Nothing to save.");
        }
        Map<UUID, ProductVariant> live = new HashMap<>();
        variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).forEach(v -> live.put(v.getId(), v));
        int saved = 0;
        for (Item item : r.items()) {
            ProductVariant v = live.get(item.variantId());
            if (v == null) throw new NotFoundException("A variant isn't in this range any more. Reload and try again.");
            String text = item.description() == null ? null : item.description().trim();
            if (text != null && text.length() > 5000) {
                throw new UnprocessableException("A description is too long (5000 characters at most).");
            }
            v.setDescription(text == null || text.isEmpty() ? null : text);
            saved++;
        }
        variants.saveAll(live.values());
        if (!p.isDescriptionVaries()) {
            p.setDescriptionVaries(true);
            products.save(p);
        }
        products.touch(productId, Instant.now());
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        return saved;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
