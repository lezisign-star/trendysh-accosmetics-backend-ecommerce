package com.nbh.backend.cart.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page opened before packages were called packages (a customer's tab, a staff dashboard left open) still adds a package to the
 * basket with its colour picks under the OLD field name. Without the alias those picks were dropped silently and the order got the
 * default colours, so this pins it down with the application's own Jackson configuration. (The full test context, the same one
 * the integration tests share, because a JSON-only slice cannot start this application: it enables JPA auditing.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddToCartRequestJsonTest {

    @Autowired private ObjectMapper json;

    private final UUID variant = UUID.randomUUID();
    private final UUID defaultColour = UUID.randomUUID();
    private final UUID chosenColour = UUID.randomUUID();

    private String body(String choicesField) {
        return "{\"variantId\":\"" + variant + "\",\"qty\":2,\"" + choicesField + "\":{\"" + defaultColour + "\":\"" + chosenColour + "\"}}";
    }

    @Test
    void thePackageChoicesFieldIsRead() throws Exception {
        AddToCartRequest request = json.readValue(body("packageChoices"), AddToCartRequest.class);
        assertThat(request.variantId()).isEqualTo(variant);
        assertThat(request.packageChoices()).isEqualTo(Map.of(defaultColour, chosenColour));
    }

    @Test
    void theOldKitChoicesFieldIsStillReadSoAStaleTabKeepsItsPicks() throws Exception {
        AddToCartRequest request = json.readValue(body("kitChoices"), AddToCartRequest.class);
        assertThat(request.packageChoices()).isEqualTo(Map.of(defaultColour, chosenColour));
    }

    @Test
    void noChoicesAtAllIsStillAPackageAsItComes() throws Exception {
        AddToCartRequest request = json.readValue("{\"variantId\":\"" + variant + "\",\"qty\":1}", AddToCartRequest.class);
        assertThat(request.packageChoices()).isNull();
    }

    @Test
    void anUnknownFieldIsIgnoredAsBefore() throws Exception {
        AddToCartRequest request = json.readValue(
                "{\"variantId\":\"" + variant + "\",\"qty\":1,\"somethingNew\":true}", AddToCartRequest.class);
        assertThat(request.qty()).isEqualByComparingTo("1");
    }
}
