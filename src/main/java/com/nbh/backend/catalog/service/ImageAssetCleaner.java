package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.ImageDraftRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes a photo off Cloudinary when staff delete it in the dashboard (owner, 2026-10-07: deleting must work on
 * Cloudinary too, not just in our database).
 *
 * <p>Three guards stop it deleting something it should not:
 * <ul>
 *   <li>it waits until the transaction that removed the row has COMMITTED, so a rolled-back removal never leaves a
 *       photo that is still in the gallery but gone from Cloudinary;</li>
 *   <li>it deletes only when no other photo or waiting AI draft still uses the same URL (a duplicated product shares
 *       its photos' URLs);</li>
 *   <li>it deletes only inside our own product-photo folders, so a photo added "by link" that happens to point at
 *       some other asset in the same Cloudinary account (a logo, say) cannot take that asset with it.</li>
 * </ul>
 * Anything not on Cloudinary at all (the demo catalogue's stock photos) is left alone. A failure is logged, never
 * thrown: the database state is what staff see.
 */
@Slf4j
@Component
public class ImageAssetCleaner {

    /** Where our own photos live: browser uploads under trendysh/products, AI-made shots under products. */
    private static final List<String> OWN_FOLDERS = List.of("trendysh/products/", "products/");
    private static final Pattern CLOUDINARY_URL =
            Pattern.compile("^https?://res\\.cloudinary\\.com/([^/]+)/image/upload/([^?#]+)");
    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+");
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{2,5}$");

    private final CloudinaryUploader cloudinary;
    private final ProductImageRepository images;
    private final ImageDraftRepository drafts;
    private final Executor executor;

    public ImageAssetCleaner(CloudinaryUploader cloudinary, ProductImageRepository images, ImageDraftRepository drafts,
                             @Qualifier("imageAssetExecutor") Executor executor) {
        this.cloudinary = cloudinary;
        this.images = images;
        this.drafts = drafts;
        this.executor = executor;
    }

    /**
     * Call from inside the transaction that deleted the rows. Each URL is checked and deleted once that transaction
     * commits (immediately when there is no transaction).
     */
    public void releaseAfterCommit(Collection<String> urls) {
        List<String> candidates = urls.stream().filter(Objects::nonNull).filter(u -> !u.isBlank()).distinct().toList();
        if (candidates.isEmpty()) {
            return;
        }
        Runnable job = () -> candidates.forEach(this::deleteIfUnused);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(job);
                }
            });
        } else {
            executor.execute(job);
        }
    }

    void deleteIfUnused(String url) {
        try {
            Optional<String> publicId = publicIdOf(url, cloudinary.cloudName());
            if (publicId.isEmpty()) {
                return;
            }
            if (images.existsByUrl(url) || drafts.existsByUrl(url)) {
                log.debug("Keeping Cloudinary asset {}: a photo or a draft still uses it", publicId.get());
                return;
            }
            cloudinary.delete(publicId.get());
            log.info("Deleted Cloudinary asset {}", publicId.get());
        } catch (RuntimeException e) {
            log.warn("Couldn't release the photo {}: {}", url, e.getMessage());
        }
    }

    /**
     * The Cloudinary public id behind one of our photo URLs, or empty when the URL is not one we may delete (another
     * host, another account, or outside our own folders). The id is what follows the version segment, minus the file
     * extension: {@code .../image/upload/v1790773760/trendysh/products/abc.jpg} is {@code trendysh/products/abc}.
     */
    static Optional<String> publicIdOf(String url, String cloudName) {
        if (url == null || cloudName == null || cloudName.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CLOUDINARY_URL.matcher(url.trim());
        if (!m.find() || !m.group(1).equals(cloudName)) {
            return Optional.empty();
        }
        String[] segments = m.group(2).split("/");
        int start = 0;
        for (int i = 0; i < segments.length; i++) {
            if (VERSION_SEGMENT.matcher(segments[i]).matches()) {
                start = i + 1;
                break;
            }
        }
        String id = String.join("/", java.util.Arrays.copyOfRange(segments, start, segments.length));
        id = EXTENSION.matcher(id).replaceFirst("");
        if (id.isBlank() || id.contains("..") || OWN_FOLDERS.stream().noneMatch(id::startsWith)) {
            return Optional.empty();
        }
        return Optional.of(id);
    }
}
