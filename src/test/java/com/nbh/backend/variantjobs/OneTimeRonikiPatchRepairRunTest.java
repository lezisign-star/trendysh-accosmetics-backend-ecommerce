package com.nbh.backend.variantjobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The one-time repair end to end on a real database, run like production (outside any database session; found live
 * 2026-10-17: "Could not initialize proxy … no session"). Photos are downloaded and stored through stand-ins.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OneTimeRonikiPatchRepairRunTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductRepository products;
    @Autowired private ProductImageRepository images;
    @Autowired private ProductVariantRepository variants;
    @Autowired private VariantJobWriter writer;
    @Autowired private VariantJobRepository jobs;
    @Autowired private ImageCleanupConfig config;

    private static byte[] photo(Color cap, boolean patched) throws Exception {
        BufferedImage im = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(222, 222, 226));
        g.fillRect(0, 0, 200, 300);
        g.setColor(Color.GRAY);
        g.fillRect(80, 80, 40, 150);
        g.setColor(cap);
        g.fillRect(80, 40, 40, 40);
        if (patched) {
            g.setColor(new Color(0xF3, 0xEB, 0xDD));
            g.fillOval(70, 25, 60, 50);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(im, "png", out);
        return out.toByteArray();
    }

    @Test
    void theRepairRunsOutsideASessionAndPointsTheDamagedPhotoAtItsRepairedCopy() throws Exception {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        String token = jwtService.generateAccessToken(admin);
        String w = UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"Repair test " + w + "\",\"categoryIds\":[],\"published\":false,\"variants\":["
                + "{\"variantName\":\"Baby Pink\",\"sellingPrice\":\"350\",\"isActive\":true},"
                + "{\"variantName\":\"Sheer Pink\",\"sellingPrice\":\"350\",\"isActive\":true,"
                + "\"options\":{\"color\":\"Sheer Pink\",\"swatchHex\":\"#DCBFC9\"}}]}";
        String productId = json.readTree(mvc.perform(post("/api/v1/products/draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        var product = products.findById(UUID.fromString(productId)).orElseThrow();
        var list = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId());
        var base = list.get(0);
        var damaged = list.get(1);

        ProductImage start = new ProductImage();
        start.setProduct(product);
        start.setVariant(base);
        start.setUrl("https://res.cloudinary.com/test/start-" + w + ".png");
        start.setPrimary(true);
        start.setSource(ProductImage.ImageSource.UPLOAD);
        images.save(start);
        ProductImage bad = new ProductImage();
        bad.setProduct(product);
        bad.setVariant(damaged);
        bad.setUrl("https://res.cloudinary.com/test/" + damaged.getId() + "-job-0000abcd-x.png");
        bad.setPrimary(true);
        bad.setSource(ProductImage.ImageSource.AI_CLEANED);
        bad = images.save(bad);

        GeminiImageClient gemini = mock(GeminiImageClient.class);
        when(gemini.download(start.getUrl())).thenReturn(new GeminiImageClient.Image(photo(new Color(236, 170, 182), false), "image/png"));
        when(gemini.download(bad.getUrl())).thenReturn(new GeminiImageClient.Image(photo(new Color(220, 191, 201), true), "image/png"));
        CloudinaryUploader cloudinary = mock(CloudinaryUploader.class);
        when(cloudinary.configured()).thenReturn(true);
        when(cloudinary.upload(org.mockito.ArgumentMatchers.any(), anyString(), anyString()))
                .thenReturn(new CloudinaryUploader.Uploaded("https://res.cloudinary.com/test/repaired-" + w + ".png", "x"));

        var repair = new OneTimeRonikiPatchRepair(jdbc, products, images, writer, gemini, cloudinary, config, jobs, variants);
        String result = repair.repairAll(product.getSlug());

        assertThat(result).startsWith("repaired 1 of 1");
        assertThat(images.findById(bad.getId()).orElseThrow().getUrl()).isEqualTo("https://res.cloudinary.com/test/repaired-" + w + ".png");
        assertThat(images.findById(start.getId()).orElseThrow().getUrl()).isEqualTo(start.getUrl());
    }
}
