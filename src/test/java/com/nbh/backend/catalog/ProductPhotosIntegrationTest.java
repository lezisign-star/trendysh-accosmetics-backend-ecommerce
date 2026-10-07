package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.imagecleanup.PhotoStudioTestContext;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The owner's photo rules (2026-10-07), end to end through the real API and database: a photo belongs to exactly
 * ONE option, a shopper sees only the option they opened, deleting a photo deletes it on Cloudinary too.
 */
class ProductPhotosIntegrationTest extends PhotoStudioTestContext {

    private static final String CLOUD_URL = "https://res.cloudinary.com/demo/image/upload/v1/trendysh/products/";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductRepository products;
    @Autowired private ProductImageRepository images;

    private String token;

    @BeforeEach
    void signInAsSuperAdminAndPointCloudinaryAtADemoAccount() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        reset(cloudinary);
        when(cloudinary.cloudName()).thenReturn("demo");
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode staff(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private JsonNode shopper(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode productWithOptions(String name, String... optionNames) throws Exception {
        List<String> variants = new ArrayList<>();
        for (String option : optionNames) {
            variants.add("{\"variantName\":\"" + option + "\",\"sellingPrice\":\"400\",\"isActive\":true}");
        }
        return staff(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[" + String.join(",", variants) + "]}");
    }

    private JsonNode attach(String productId, String variantId, String url, boolean main) throws Exception {
        return staff(post("/api/v1/products/" + productId + "/images"), "{\"url\":\"" + url + "\",\"isPrimary\":" + main
                + (variantId == null ? "" : ",\"variantId\":\"" + variantId + "\"") + "}");
    }

    /** The photos a shopper gets on one option's page, in order. */
    private List<String> shopPhotos(String variantId) throws Exception {
        List<String> urls = new ArrayList<>();
        shopper(get("/api/v1/catalog/variants/" + variantId)).get("images").forEach(i -> urls.add(i.get("url").asText()));
        return urls;
    }

    private String shopCard(String name, String variantId) throws Exception {
        for (JsonNode card : shopper(get("/api/v1/catalog/variants").param("q", name).param("size", "50")).get("content")) {
            if (card.get("variantId").asText().equals(variantId)) {
                return card.get("imageUrl").isNull() ? null : card.get("imageUrl").asText();
            }
        }
        throw new AssertionError("no card for " + variantId);
    }

    private List<JsonNode> staffPhotos(String productId) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        staff(get("/api/v1/products/" + productId + "/images"), null).forEach(list::add);
        return list;
    }

    private String optionId(JsonNode product, int index) {
        return product.get("variants").get(index).get("id").asText();
    }

    // ---- the shop ----

    @Test
    void eachOptionsPageAndCardShowOnlyThatOptionsPhotos() throws Exception {
        String name = unique("Photo Rule Gel");
        JsonNode product = productWithOptions(name, "Red", "Blue");
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        attach(productId, red, "https://photos.test/red-1.jpg", false);
        attach(productId, red, "https://photos.test/red-2.jpg", false);
        attach(productId, blue, "https://photos.test/blue-1.jpg", false);

        assertThat(shopPhotos(red)).containsExactly("https://photos.test/red-1.jpg", "https://photos.test/red-2.jpg");
        assertThat(shopPhotos(blue)).containsExactly("https://photos.test/blue-1.jpg");
        assertThat(shopCard(name, red)).isEqualTo("https://photos.test/red-1.jpg");
        assertThat(shopCard(name, blue)).isEqualTo("https://photos.test/blue-1.jpg");
    }

    @Test
    void anOptionWithNoPhotosShowsNoneRatherThanBorrowingASiblings() throws Exception {
        String name = unique("Photo Rule Lonely");
        JsonNode product = productWithOptions(name, "Red", "Blue");
        attach(product.get("id").asText(), optionId(product, 0), "https://photos.test/red-1.jpg", false);

        assertThat(shopPhotos(optionId(product, 1))).isEmpty();
        assertThat(shopCard(name, optionId(product, 1))).isNull();
    }

    @Test
    void aPhotoLeftAtProductLevelIsHiddenFromTheShopUntilStaffGiveItToAnOption() throws Exception {
        String name = unique("Photo Rule Unassigned");
        JsonNode product = productWithOptions(name, "Red", "Blue");
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        attach(productId, red, "https://photos.test/red-1.jpg", false);

        // The old "whole product" kind of photo, as the live shop still holds (variant_id null).
        ProductImage stray = new ProductImage();
        stray.setProduct(products.getReferenceById(UUID.fromString(productId)));
        stray.setUrl("https://photos.test/stray.jpg");
        stray.setPrimary(true);
        stray.setSortOrder(50);
        stray = images.save(stray);

        assertThat(shopPhotos(red)).containsExactly("https://photos.test/red-1.jpg");
        assertThat(shopPhotos(blue)).isEmpty();
        JsonNode listed = staffPhotos(productId).stream()
                .filter(i -> i.get("url").asText().endsWith("stray.jpg")).findFirst().orElseThrow();
        assertThat(listed.get("variantId").isNull()).as("staff see it, marked as belonging to no option").isTrue();

        JsonNode assigned = staff(put("/api/v1/products/" + productId + "/images/" + stray.getId() + "/option"),
                "{\"variantId\":\"" + blue + "\"}");

        assertThat(assigned.get("variantId").asText()).isEqualTo(blue);
        assertThat(assigned.get("isPrimary").asBoolean()).as("blue had no photo, so this is its main one").isTrue();
        assertThat(shopPhotos(blue)).containsExactly("https://photos.test/stray.jpg");
        assertThat(shopPhotos(red)).containsExactly("https://photos.test/red-1.jpg");
    }

    @Test
    void aPhotoOfAProductWithOneOptionNeedsNoOptionNamedAndShowsOnThatOption() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Solo"), "Default");
        String productId = product.get("id").asText();
        String only = optionId(product, 0);

        JsonNode saved = attach(productId, null, "https://photos.test/solo-1.jpg", false);

        assertThat(saved.get("variantId").asText()).isEqualTo(only);
        assertThat(shopPhotos(only)).containsExactly("https://photos.test/solo-1.jpg");
    }

    // ---- who may receive a photo ----

    @Test
    void withSeveralOptionsAPhotoMustSayWhichOptionItIsFor() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Choose"), "Red", "Blue");

        MvcResult result = call(post("/api/v1/products/" + product.get("id").asText() + "/images"),
                "{\"url\":\"https://photos.test/a.jpg\",\"isPrimary\":false}");

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("which option");
    }

    @Test
    void anotherProductsOptionCannotBeGivenAPhoto() throws Exception {
        JsonNode mine = productWithOptions(unique("Photo Rule Mine"), "Red", "Blue");
        JsonNode theirs = productWithOptions(unique("Photo Rule Theirs"), "Red", "Blue");

        MvcResult result = call(post("/api/v1/products/" + mine.get("id").asText() + "/images"),
                "{\"url\":\"https://photos.test/a.jpg\",\"isPrimary\":false,\"variantId\":\"" + optionId(theirs, 0) + "\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(staffPhotos(theirs.get("id").asText())).isEmpty();
    }

    // ---- main photos ----

    @Test
    void everyOptionKeepsItsOwnMainPhoto() throws Exception {
        String name = unique("Photo Rule Mains");
        JsonNode product = productWithOptions(name, "Red", "Blue");
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        attach(productId, red, "https://photos.test/red-1.jpg", false);
        JsonNode red2 = attach(productId, red, "https://photos.test/red-2.jpg", false);
        attach(productId, blue, "https://photos.test/blue-1.jpg", false);

        staff(put("/api/v1/products/" + productId + "/images/" + red2.get("id").asText() + "/primary"), null);

        assertThat(shopPhotos(red)).containsExactly("https://photos.test/red-2.jpg", "https://photos.test/red-1.jpg");
        assertThat(shopPhotos(blue)).containsExactly("https://photos.test/blue-1.jpg");
        assertThat(shopCard(name, red)).isEqualTo("https://photos.test/red-2.jpg");
        assertThat(shopCard(name, blue)).isEqualTo("https://photos.test/blue-1.jpg");
        long mains = staffPhotos(productId).stream().filter(i -> i.get("isPrimary").asBoolean()).count();
        assertThat(mains).as("one main photo per option, two options").isEqualTo(2);
    }

    @Test
    void theStaffListStandsAProductInForTheFirstOptionThatHasAPhoto() throws Exception {
        String name = unique("Photo Rule Cover");
        JsonNode product = productWithOptions(name, "Red", "Blue");
        attach(product.get("id").asText(), optionId(product, 1), "https://photos.test/blue-1.jpg", false);

        JsonNode page = staff(get("/api/v1/products").param("q", name), null);

        assertThat(page.get("content").get(0).get("primaryImageUrl").asText()).isEqualTo("https://photos.test/blue-1.jpg");
    }

    // ---- Cloudinary ----

    @Test
    void deletingAPhotoDeletesItOnCloudinaryToo() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Delete"), "Red", "Blue");
        String productId = product.get("id").asText();
        String file = "gone-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode photo = attach(productId, optionId(product, 0), CLOUD_URL + file + ".jpg", false);

        staff(delete("/api/v1/products/" + productId + "/images/" + photo.get("id").asText()), null);

        assertThat(staffPhotos(productId)).isEmpty();
        verify(cloudinary).delete("trendysh/products/" + file);
    }

    @Test
    void aFileAnotherPhotoStillUsesIsKeptOnCloudinaryUntilTheLastOneGoes() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Shared File"), "Red", "Blue");
        String productId = product.get("id").asText();
        String file = "shared-" + UUID.randomUUID().toString().substring(0, 8);
        attach(productId, optionId(product, 0), CLOUD_URL + file + ".jpg", false);
        JsonNode copy = staff(post("/api/v1/products/" + productId + "/duplicate"), null);
        String copyId = copy.get("id").asText();

        // the copy has the same photo, filed under ITS OWN red option
        JsonNode copiedPhoto = staffPhotos(copyId).get(0);
        assertThat(copiedPhoto.get("variantId").asText()).isEqualTo(copy.get("variants").get(0).get("id").asText());

        JsonNode original = staffPhotos(productId).get(0);
        staff(delete("/api/v1/products/" + productId + "/images/" + original.get("id").asText()), null);
        verify(cloudinary, never()).delete(anyString());

        staff(delete("/api/v1/products/" + copyId + "/images/" + copiedPhoto.get("id").asText()), null);
        verify(cloudinary).delete("trendysh/products/" + file);
    }

    @Test
    void aPhotoThatIsNotOursOrSitsOutsideOurFoldersIsNeverDeletedFromCloudinary() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Foreign"), "Red");
        String productId = product.get("id").asText();
        JsonNode logo = attach(productId, null, "https://res.cloudinary.com/demo/image/upload/v1/branding/logo.png", false);
        staff(delete("/api/v1/products/" + productId + "/images/" + logo.get("id").asText()), null);
        JsonNode stock = attach(productId, null, "https://picsum.photos/seed/x/800/600", false);
        staff(delete("/api/v1/products/" + productId + "/images/" + stock.get("id").asText()), null);

        verify(cloudinary, never()).delete(anyString());
    }

    @Test
    void removingAnOptionTakesItsPhotosWithItIncludingOnCloudinary() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Remove Option"), "Red", "Blue");
        String productId = product.get("id").asText();
        String red = optionId(product, 0);
        String blue = optionId(product, 1);
        String blueFile = "blue-" + UUID.randomUUID().toString().substring(0, 8);
        attach(productId, red, "https://photos.test/red-1.jpg", false);
        attach(productId, blue, CLOUD_URL + blueFile + ".jpg", false);

        staff(put("/api/v1/products/" + productId + "/full"), "{\"expectedUpdatedAt\":\"" + product.get("updatedAt").asText()
                + "\",\"name\":\"" + product.get("name").asText() + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[{\"id\":\"" + red + "\",\"variantName\":\"Red\",\"sellingPrice\":\"400\",\"isActive\":true}]}");

        assertThat(staffPhotos(productId)).extracting(i -> i.get("url").asText()).containsExactly("https://photos.test/red-1.jpg");
        assertThat(images.findByVariantIdOrderBySortOrderAsc(UUID.fromString(blue))).isEmpty();
        verify(cloudinary).delete("trendysh/products/" + blueFile);
    }

    // ---- copies ----

    @Test
    void aCopyKeepsEachPhotoWithTheSameOption() throws Exception {
        JsonNode product = productWithOptions(unique("Photo Rule Copy"), "Red", "Blue");
        String productId = product.get("id").asText();
        attach(productId, optionId(product, 0), "https://photos.test/red-1.jpg", false);
        attach(productId, optionId(product, 1), "https://photos.test/blue-1.jpg", false);

        JsonNode copy = staff(post("/api/v1/products/" + productId + "/duplicate"), null);

        String copiedRed = optionId(copy, 0);
        String copiedBlue = optionId(copy, 1);
        assertThat(staffPhotos(copy.get("id").asText()))
                .extracting(i -> i.get("variantId").asText() + "=" + i.get("url").asText())
                .containsExactlyInAnyOrder(copiedRed + "=https://photos.test/red-1.jpg",
                        copiedBlue + "=https://photos.test/blue-1.jpg");
    }
}
