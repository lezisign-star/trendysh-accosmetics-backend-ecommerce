package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.UnprocessableException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Tidy names" (owner, 2026-10-29): names typed all in capitals or all in small letters saved with tidy capitals. The
 * staff screen works out the tidy spelling and sends it; this only ever changes capitals, never the words, so a name
 * that would read differently is refused. Products in the shop can be tidied too: nothing a shopper sees changes but
 * the capitals.
 */
@Service
public class NameTidyService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ApplicationEventPublisher events;

    public NameTidyService(ProductRepository products, ProductVariantRepository variants,
                           ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.events = events;
    }

    public record VariantName(UUID id, String variantName) {}

    public record ProductName(UUID productId, String name, List<VariantName> variants) {}

    public record Request(List<ProductName> products) {}

    public record Result(int products, int variants) {}

    @Transactional
    public Result tidy(Request in) {
        if (in == null || in.products() == null || in.products().isEmpty()) {
            throw new UnprocessableException("Nothing to tidy.");
        }
        int productCount = 0;
        int variantCount = 0;
        for (ProductName row : in.products()) {
            Product p = products.findById(row.productId())
                    .orElseThrow(() -> new UnprocessableException("A product in the list no longer exists."));
            if (row.name() != null && !row.name().equals(p.getName())) {
                p.setName(sameWords(p.getName(), row.name()));
                products.save(p);
                productCount++;
            }
            if (row.variants() == null) continue;
            for (VariantName vn : row.variants()) {
                ProductVariant v = variants.findById(vn.id())
                        .filter(x -> x.getProduct().getId().equals(p.getId()))
                        .orElseThrow(() -> new UnprocessableException("A variant in the list is not that product's."));
                if (vn.variantName() == null || vn.variantName().equals(v.getVariantName())) continue;
                String name = sameWords(v.getVariantName(), vn.variantName());
                v.setVariantName(name);
                // Its own part of the name ends the full name: take it, with the new capitals, from there.
                String part = v.getNamePart();
                if (part != null && name.toLowerCase(Locale.ROOT).endsWith(part.toLowerCase(Locale.ROOT))) {
                    v.setNamePart(name.substring(name.length() - part.length()));
                }
                variants.save(v);
                variantCount++;
            }
        }
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
        return new Result(productCount, variantCount);
    }

    /** The new spelling, when it is the old one with other capitals (spaces tidied); refused otherwise. */
    static String sameWords(String old, String proposed) {
        String next = proposed == null ? "" : proposed.replaceAll("\\s+", " ").strip();
        String before = old == null ? "" : old.replaceAll("\\s+", " ").strip();
        if (next.isEmpty() || !next.equalsIgnoreCase(before)) {
            throw new UnprocessableException("“" + proposed + "” is not “" + old
                    + "” with other capitals, so it is not changed here.");
        }
        return next;
    }
}
