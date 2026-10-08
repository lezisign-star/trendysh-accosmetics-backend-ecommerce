package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drafts product text from a product's real photo during a photo batch, and the review step for it: a draft waits here,
 * invisible to the shop, until staff approve it (the text is copied onto the product) or reject it. Whatever the AI
 * writes is cut to {@link DescriptionCaps} before it is saved, and text a person edits on approval must already fit.
 * Only products with no description of their own are drafted for, so a run never proposes replacing text someone wrote.
 */
@Service
public class DescriptionDraftService {

    /** What staff may edit before approving; null fields keep the draft's own text. */
    public record Edits(String shortDescription, String description, List<String> highlights, String ingredients,
                        String howToUse, String seoTitle, String seoDescription) {}

    public record DraftDto(UUID id, UUID batchId, UUID productId, String productName, String shortDescription,
                           String description, List<String> highlights, String ingredients, String howToUse,
                           String seoTitle, String seoDescription, Instant createdAt) {}

    /** What the prompt needs to know about a product, read while a transaction is open. */
    public record ProductContext(String name, List<String> categories, boolean hasDescription) {}

    private final ProductRepository products;
    private final ProductDescriptionDraftRepository drafts;
    private final GeminiTextClient gemini;
    private final DescriptionPromptBuilder prompts;
    /** For the read in {@link #context}: a method called from inside this class is not wrapped by @Transactional. */
    private final TransactionTemplate readOnly;

    public DescriptionDraftService(ProductRepository products, ProductDescriptionDraftRepository drafts,
                                   GeminiTextClient gemini, DescriptionPromptBuilder prompts,
                                   PlatformTransactionManager transactions) {
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.products = products;
        this.drafts = drafts;
        this.gemini = gemini;
        this.prompts = prompts;
    }

    public ProductContext context(UUID productId) {
        return readOnly.execute(status -> {
            Product product = products.findById(productId).orElse(null);
            if (product == null || product.isDeleted()) {
                return null;
            }
            List<String> categories = product.getCategories().stream().map(Category::getName).toList();
            boolean has = product.getDescription() != null && !product.getDescription().isBlank();
            return new ProductContext(product.getName(), categories, has);
        });
    }

    /**
     * Writes a draft for the product from its real photo (replacing any draft already waiting for it). Returns false,
     * without calling Gemini, when the product no longer exists or already has a description of its own. Throws
     * {@link GeminiImageClient.GeminiException} when Gemini fails, for the caller to report.
     */
    public boolean draftFor(UUID batchId, UUID productId, GeminiImageClient.Image photo, String note) {
        ProductContext context = context(productId);
        if (context == null || context.hasDescription()) {
            return false;
        }
        JsonNode answer = gemini.describe(photo, prompts.build(context.name(), context.categories(), note));
        save(batchId, productId, answer);
        return true;
    }

    private void save(UUID batchId, UUID productId, JsonNode a) {
        ProductDescriptionDraft draft = drafts.findByProductId(productId).orElseGet(ProductDescriptionDraft::new);
        draft.setBatchId(batchId);
        draft.setProductId(productId);
        draft.setShortDescription(DescriptionCaps.fit(text(a, "shortDescription"), DescriptionCaps.SHORT_DESCRIPTION));
        draft.setDescription(DescriptionCaps.fit(text(a, "description"), DescriptionCaps.DESCRIPTION));
        List<String> highlights = new ArrayList<>();
        a.path("highlights").forEach(h -> highlights.add(h.asText("")));
        draft.setHighlights(DescriptionCaps.fitHighlights(highlights));
        draft.setIngredients(DescriptionCaps.fit(text(a, "ingredients"), DescriptionCaps.INGREDIENTS));
        draft.setHowToUse(DescriptionCaps.fit(text(a, "howToUse"), DescriptionCaps.HOW_TO_USE));
        draft.setSeoTitle(DescriptionCaps.fit(text(a, "seoTitle"), DescriptionCaps.SEO_TITLE));
        draft.setSeoDescription(DescriptionCaps.fit(text(a, "seoDescription"), DescriptionCaps.SEO_DESCRIPTION));
        drafts.save(draft);
    }

    private static String text(JsonNode a, String field) {
        String value = a.path(field).asText(null);
        return value == null ? null : value;
    }

    @Transactional(readOnly = true)
    public List<DraftDto> list() {
        return toDtos(drafts.findAllByOrderByCreatedAtDesc());
    }

    @Transactional(readOnly = true)
    public List<DraftDto> forBatch(UUID batchId) {
        return toDtos(drafts.findByBatchIdOrderByCreatedAtAsc(batchId));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return drafts.count();
    }

    private List<DraftDto> toDtos(List<ProductDescriptionDraft> rows) {
        Map<UUID, String> names = products.findAllById(rows.stream().map(ProductDescriptionDraft::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getName, (a, b) -> a));
        return rows.stream().map(d -> new DraftDto(d.getId(), d.getBatchId(), d.getProductId(),
                names.getOrDefault(d.getProductId(), "(removed product)"), d.getShortDescription(), d.getDescription(),
                DescriptionCaps.splitHighlights(d.getHighlights()), d.getIngredients(), d.getHowToUse(), d.getSeoTitle(),
                d.getSeoDescription(), d.getCreatedAt())).toList();
    }

    /** Copies the draft (with the person's edits, which must fit the limits) onto the product and removes the draft. */
    @Transactional
    public void approve(UUID draftId, Edits edits) {
        ProductDescriptionDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new NotFoundException("That description is no longer waiting for review."));
        Product product = products.findById(draft.getProductId())
                .orElseThrow(() -> new NotFoundException("That product no longer exists."));
        Function<String, String> clean = DescriptionCaps::clean;
        String shortDescription = edits != null && edits.shortDescription() != null
                ? clean.apply(edits.shortDescription()) : draft.getShortDescription();
        String description = edits != null && edits.description() != null
                ? clean.apply(edits.description()) : draft.getDescription();
        List<String> highlights = edits != null && edits.highlights() != null
                ? edits.highlights().stream().map(DescriptionCaps::clean).filter(h -> h != null).toList()
                : DescriptionCaps.splitHighlights(draft.getHighlights());
        String ingredients = edits != null && edits.ingredients() != null
                ? clean.apply(edits.ingredients()) : draft.getIngredients();
        String howToUse = edits != null && edits.howToUse() != null
                ? clean.apply(edits.howToUse()) : draft.getHowToUse();
        String seoTitle = edits != null && edits.seoTitle() != null
                ? clean.apply(edits.seoTitle()) : draft.getSeoTitle();
        String seoDescription = edits != null && edits.seoDescription() != null
                ? clean.apply(edits.seoDescription()) : draft.getSeoDescription();

        String tooLong = DescriptionCaps.firstTooLong(shortDescription, description, highlights, ingredients, howToUse,
                seoTitle, seoDescription);
        if (tooLong != null) {
            throw new UnprocessableException(tooLong);
        }
        product.setShortDescription(shortDescription);
        product.setDescription(description);
        product.setHighlights(highlights.isEmpty() ? null : String.join("\n", highlights));
        product.setIngredients(ingredients);
        product.setHowToUse(howToUse);
        product.setSeoTitle(seoTitle);
        product.setSeoDescription(seoDescription);
        products.save(product);
        drafts.delete(draft);
    }

    /** Undoing a batch also throws away the descriptions it drafted that nobody has approved. */
    @Transactional
    public void discardBatch(UUID batchId) {
        drafts.deleteByBatchId(batchId);
    }

    @Transactional
    public void reject(UUID draftId) {
        if (!drafts.existsById(draftId)) {
            throw new NotFoundException("That description is no longer waiting for review.");
        }
        drafts.deleteById(draftId);
    }
}
