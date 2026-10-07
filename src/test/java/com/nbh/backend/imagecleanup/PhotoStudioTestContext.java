package com.nbh.backend.imagecleanup;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The one application context every photo test shares: Gemini and Cloudinary are stand-ins and a Gemini key is
 * "configured". Spring keeps a context (and its pool of database connections) alive for the whole run for every
 * distinct set of overrides, and the suite already holds most of the local Postgres' 100 connections, so the photo
 * tests must share ONE of these rather than each making its own; its pool is kept small because these tests never run
 * more than a couple of things at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.image-cleanup.gemini-api-key=test-only-key",
        "spring.datasource.hikari.maximum-pool-size=4"
})
public abstract class PhotoStudioTestContext {

    @MockitoBean protected GeminiImageClient gemini;
    @MockitoBean protected CloudinaryUploader cloudinary;
}
