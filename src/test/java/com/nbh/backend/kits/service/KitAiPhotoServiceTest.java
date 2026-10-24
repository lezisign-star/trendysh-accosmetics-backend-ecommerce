package com.nbh.backend.kits.service;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import com.nbh.backend.kits.service.KitAiPhotoService.Style;
import com.nbh.backend.kits.service.KitService.AiPhotoInputs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A kit's picture made with Gemini from its products' photos (owner, 2026-10-23), with Gemini and storage stubbed. */
class KitAiPhotoServiceTest {

    private final KitService kits = mock(KitService.class);
    private final GeminiImageClient gemini = mock(GeminiImageClient.class);
    private final CloudinaryUploader cloudinary = mock(CloudinaryUploader.class);
    private final ProductService products = mock(ProductService.class);
    private final KitAiPhotoService service = new KitAiPhotoService(kits, gemini, cloudinary, products);

    private final UUID kitId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID variantId = UUID.randomUUID();

    private AiPhotoInputs inputs(int photos) {
        List<String> names = IntStream.range(0, photos).mapToObj(i -> "Product " + i).toList();
        List<String> urls = IntStream.range(0, photos).mapToObj(i -> "https://res.cloudinary.com/x/p" + i + ".jpg").toList();
        return new AiPhotoInputs(productId, variantId, "Lash Starter Kit", "Lash Technician", names, urls);
    }

    @Test
    void thePictureIsMadeFromTheProductsPhotosAndFiledWithTheKitWithoutTakingOverItsThumbnail() {
        when(kits.aiPhotoInputs(kitId)).thenReturn(inputs(3));
        when(cloudinary.configured()).thenReturn(true);
        Image photo = new Image(new byte[]{1}, "image/jpeg");
        when(gemini.download(anyString())).thenReturn(photo);
        when(gemini.edit(any(Image.class), anyList(), anyString())).thenReturn(new Image(new byte[]{9}, "image/png"));
        when(cloudinary.upload(any(), eq("kits"), anyString()))
                .thenReturn(new CloudinaryUploader.Uploaded("https://res.cloudinary.com/x/kits/new.png", "kits/new"));

        service.make(kitId, Style.AT_WORK, "  in a bright salon  ");

        verify(gemini, times(3)).download(anyString());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).edit(eq(photo), anyList(), prompt.capture());
        assertThat(prompt.getValue()).contains("Lash Starter Kit").contains("lash technician at work")
                .contains("Keep every product exactly as it looks").contains("Also: in a bright salon");
        ArgumentCaptor<AttachImageRequest> attached = ArgumentCaptor.forClass(AttachImageRequest.class);
        verify(products).attachImage(eq(productId), attached.capture());
        assertThat(attached.getValue().url()).isEqualTo("https://res.cloudinary.com/x/kits/new.png");
        assertThat(attached.getValue().variantId()).isEqualTo(variantId);
        assertThat(attached.getValue().isPrimary()).as("staff choose it; it is the thumbnail only when there is none").isFalse();
    }

    @Test
    void atMostSixPhotosAreSentAndTheSetStyleAsksForTheProductsTogether() {
        when(kits.aiPhotoInputs(kitId)).thenReturn(inputs(9));
        when(cloudinary.configured()).thenReturn(true);
        when(gemini.download(anyString())).thenReturn(new Image(new byte[]{1}, "image/jpeg"));
        when(gemini.edit(any(Image.class), anyList(), anyString())).thenReturn(new Image(new byte[]{9}, "image/png"));
        when(cloudinary.upload(any(), anyString(), anyString())).thenReturn(new CloudinaryUploader.Uploaded("u", "p"));

        service.make(kitId, Style.SET, null);

        verify(gemini, times(KitAiPhotoService.MAX_PHOTOS)).download(anyString());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).edit(any(Image.class), anyList(), prompt.capture());
        assertThat(prompt.getValue()).contains("together as one neat set").doesNotContain("Also:");
    }

    @Test
    void aKitWhoseProductsHaveNoPhotosSaysSoAndAsksNothingOfGemini() {
        when(kits.aiPhotoInputs(kitId)).thenReturn(inputs(0));
        assertThatThrownBy(() -> service.make(kitId, Style.SET, null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("nothing to make");
        verify(gemini, never()).edit(any(Image.class), anyList(), anyString());
    }

    @Test
    void aGeminiLimitIsToldInPlainWordsAndNothingIsFiled() {
        when(kits.aiPhotoInputs(kitId)).thenReturn(inputs(2));
        when(cloudinary.configured()).thenReturn(true);
        when(gemini.download(anyString())).thenReturn(new Image(new byte[]{1}, "image/jpeg"));
        when(gemini.edit(any(Image.class), anyList(), anyString())).thenThrow(new GeminiException("429", true));
        assertThatThrownBy(() -> service.make(kitId, Style.SET, null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("limit for now is used up");
        verify(products, never()).attachImage(any(), any());
    }
}
