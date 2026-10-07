package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.ImageDraftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deleting a photo in the dashboard must also delete it on Cloudinary — without ever deleting what it should not. */
class ImageAssetCleanerTest {

    private static final String CLOUD = "demo";
    private static final String UPLOAD = "https://res.cloudinary.com/demo/image/upload/v1790773760/trendysh/products/abc.jpg";

    private CloudinaryUploader cloudinary;
    private ProductImageRepository images;
    private ImageDraftRepository drafts;
    private ImageAssetCleaner cleaner;

    @BeforeEach
    void setUp() {
        cloudinary = mock(CloudinaryUploader.class);
        images = mock(ProductImageRepository.class);
        drafts = mock(ImageDraftRepository.class);
        when(cloudinary.cloudName()).thenReturn(CLOUD);
        Executor sameThread = Runnable::run;
        cleaner = new ImageAssetCleaner(cloudinary, images, drafts, sameThread);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---- which Cloudinary file a photo URL means ----

    @Test
    void theVersionAndFileExtensionAreDroppedFromTheId() {
        assertThat(ImageAssetCleaner.publicIdOf(UPLOAD, CLOUD)).contains("trendysh/products/abc");
        assertThat(ImageAssetCleaner.publicIdOf(
                "https://res.cloudinary.com/demo/image/upload/v12/products/0bb1-clean-1-e93f4446.png", CLOUD))
                .contains("products/0bb1-clean-1-e93f4446");
    }

    @Test
    void transformationSegmentsBeforeTheVersionAreIgnored() {
        assertThat(ImageAssetCleaner.publicIdOf(
                "https://res.cloudinary.com/demo/image/upload/c_fill,w_200/v1/trendysh/products/abc.jpg", CLOUD))
                .contains("trendysh/products/abc");
    }

    @Test
    void aUrlWithoutAVersionStillWorks() {
        assertThat(ImageAssetCleaner.publicIdOf(
                "https://res.cloudinary.com/demo/image/upload/trendysh/products/abc.jpg", CLOUD))
                .contains("trendysh/products/abc");
    }

    @Test
    void anythingThatIsNotOursIsLeftAlone() {
        // another account, another host (the demo catalogue's stock photos), outside our folders, odd input
        assertThat(ImageAssetCleaner.publicIdOf(UPLOAD, "someone-else")).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf("https://picsum.photos/seed/x/800/600", CLOUD)).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf(
                "https://res.cloudinary.com/demo/image/upload/v1/branding/logo.png", CLOUD)).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf(
                "https://res.cloudinary.com/demo/image/upload/v1/trendysh/products/../branding/logo.png", CLOUD)).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf(UPLOAD, "")).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf(UPLOAD, null)).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf(null, CLOUD)).isEmpty();
        assertThat(ImageAssetCleaner.publicIdOf("not a url", CLOUD)).isEmpty();
    }

    // ---- when it is deleted ----

    @Test
    void anUnusedPhotoIsDeletedFromCloudinary() {
        when(images.existsByUrl(UPLOAD)).thenReturn(false);

        cleaner.deleteIfUnused(UPLOAD);

        verify(cloudinary).delete("trendysh/products/abc");
    }

    @Test
    void aPhotoAnotherRowStillUsesIsKept() {
        // a duplicated product shares its photos' files until the last of them is removed
        when(images.existsByUrl(UPLOAD)).thenReturn(true);

        cleaner.deleteIfUnused(UPLOAD);

        verify(cloudinary, never()).delete(anyString());
    }

    @Test
    void aFileAWaitingAiDraftStillUsesIsKept() {
        when(images.existsByUrl(UPLOAD)).thenReturn(false);
        when(drafts.existsByUrl(UPLOAD)).thenReturn(true);

        cleaner.deleteIfUnused(UPLOAD);

        verify(cloudinary, never()).delete(anyString());
    }

    @Test
    void aFailureOnCloudinarysSideNeverEscapes() {
        when(images.existsByUrl(UPLOAD)).thenReturn(false);
        doThrow(new IllegalStateException("cloudinary is down")).when(cloudinary).delete(anyString());

        cleaner.deleteIfUnused(UPLOAD); // does not throw
    }

    @Test
    void withoutATransactionTheFilesAreReleasedAtOnce() {
        when(images.existsByUrl(UPLOAD)).thenReturn(false);

        cleaner.releaseAfterCommit(List.of(UPLOAD));

        verify(cloudinary).delete("trendysh/products/abc");
    }

    @Test
    void insideATransactionNothingHappensUntilItCommits() {
        when(images.existsByUrl(UPLOAD)).thenReturn(false);
        TransactionSynchronizationManager.initSynchronization();

        cleaner.releaseAfterCommit(List.of(UPLOAD, UPLOAD, "  "));
        verify(cloudinary, never()).delete(anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(cloudinary).delete("trendysh/products/abc"); // once, though the URL was given twice
    }

    @Test
    void aRolledBackRemovalNeverTouchesCloudinary() {
        TransactionSynchronizationManager.initSynchronization();

        cleaner.releaseAfterCommit(List.of(UPLOAD));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cloudinary, never()).delete(anyString());
        assertThat(Optional.ofNullable(cloudinary)).isPresent();
    }

    // ---- a file whose Cloudinary id was stored when it was made (an AI shot) ----

    @Test
    void aStoredIdIsDeletedWhenNothingStillShowsTheFile() {
        cleaner.releaseKnown(UPLOAD, "products/stored-id");

        verify(cloudinary).delete("products/stored-id");
    }

    @Test
    void aStoredIdIsKeptWhileADuplicatedProductsPhotoStillShowsTheFile() {
        when(images.existsByUrl(UPLOAD)).thenReturn(true);

        cleaner.releaseKnown(UPLOAD, "products/stored-id");

        verify(cloudinary, never()).delete(anyString());
    }

    @Test
    void aStoredIdWhoseRowIsAlreadyGoneIsDeletedWithoutTheUsageCheck() {
        cleaner.releaseKnown(null, "products/stored-id");

        verify(cloudinary).delete("products/stored-id");
        verify(images, never()).existsByUrl(anyString());
    }

    @Test
    void aFullDeletionQueueNeverFailsTheSaveThatCausedIt() {
        Executor full = job -> {
            throw new java.util.concurrent.RejectedExecutionException("queue is full");
        };
        ImageAssetCleaner overloaded = new ImageAssetCleaner(cloudinary, images, drafts, full);

        overloaded.releaseAfterCommit(List.of(UPLOAD)); // does not throw
        overloaded.releaseKnown(UPLOAD, "products/stored-id"); // nor this

        verify(cloudinary, never()).delete(anyString());
    }
}
