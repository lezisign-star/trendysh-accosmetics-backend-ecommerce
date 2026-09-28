package com.nbh.backend.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Paybill number, the merchant name a customer's phone shows, and the account number they type are
 * managed from admin Settings, and the checkout's payment step previews them. The settings row is a
 * singleton shared with other tests, so this puts back exactly what it found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaybillSettingsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private PaymentSettingsRepository settingsRepository;

    private String staffToken;
    private PaymentSettings saved;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
        PaymentSettings row = settingsRepository.findFirstByOrderByCreatedAtAsc().orElseThrow();
        saved = new PaymentSettings();
        saved.setBankName(row.getBankName());
        saved.setPaybillNumber(row.getPaybillNumber());
        saved.setPaybillMerchantName(row.getPaybillMerchantName());
        saved.setPaybillAccountTemplate(row.getPaybillAccountTemplate());
        saved.setAccountName(row.getAccountName());
        saved.setAccountNumber(row.getAccountNumber());
        saved.setAccountBranch(row.getAccountBranch());
        saved.setSupportWhatsapp(row.getSupportWhatsapp());
    }

    @AfterEach
    void restore() {
        PaymentSettings row = settingsRepository.findFirstByOrderByCreatedAtAsc().orElseThrow();
        row.setBankName(saved.getBankName());
        row.setPaybillNumber(saved.getPaybillNumber());
        row.setPaybillMerchantName(saved.getPaybillMerchantName());
        row.setPaybillAccountTemplate(saved.getPaybillAccountTemplate());
        row.setAccountName(saved.getAccountName());
        row.setAccountNumber(saved.getAccountNumber());
        row.setAccountBranch(saved.getAccountBranch());
        row.setSupportWhatsapp(saved.getSupportWhatsapp());
        settingsRepository.save(row);
    }

    private MvcResult saveSettings(String paybill, String merchant, String template) throws Exception {
        String body = """
                {"paybillNumber":%s,"paybillMerchantName":%s,"paybillAccountTemplate":%s}
                """.formatted(quoted(paybill), quoted(merchant), quoted(template));
        return mvc.perform(put("/api/v1/admin/settings/payment")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** {@code params} are name/value pairs; passed as real request params so nothing is double-encoded. */
    private JsonNode preview(String... params) throws Exception {
        var request = get("/api/v1/payments/paybill-info");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void adminCanSetTheMerchantNameAndAccountNumberAndTheyComeBackOnRead() throws Exception {
        MvcResult saveResult = saveSettings("400200", "TRENDYSH ACCOSMETIC", "<name>");
        assertThat(saveResult.getResponse().getStatus()).as(saveResult.getResponse().getContentAsString()).isEqualTo(200);

        MvcResult read = mvc.perform(get("/api/v1/admin/settings/payment")
                .header("Authorization", "Bearer " + staffToken)).andReturn();
        JsonNode body = json.readTree(read.getResponse().getContentAsString());
        assertThat(body.get("paybillMerchantName").asText()).isEqualTo("TRENDYSH ACCOSMETIC");
        assertThat(body.get("paybillAccountTemplate").asText()).isEqualTo("<name>");
    }

    @Test
    void aMistypedPlaceholderIsRefusedAndNothingIsSaved() throws Exception {
        saveSettings("400200", "TRENDYSH", "<name>");

        MvcResult refused = saveSettings("400200", "TRENDYSH", "01100123#<nmae>");

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(settingsRepository.findFirstByOrderByCreatedAtAsc().orElseThrow().getPaybillAccountTemplate())
                .isEqualTo("<name>");
    }

    @Test
    void theCheckoutPreviewShowsThePaybillTheMerchantAndTheCustomersNameAsTheAccount() throws Exception {
        saveSettings("400200", "TRENDYSH ACCOSMETIC", "<name>");

        JsonNode preview = preview("name", "Njeri Test", "phone", "0720000111");

        assertThat(preview.get("configured").asBoolean()).isTrue();
        assertThat(preview.get("paybillNumber").asText()).isEqualTo("400200");
        assertThat(preview.get("merchantName").asText()).isEqualTo("TRENDYSH ACCOSMETIC");
        assertThat(preview.get("accountNumber").asText()).isEqualTo("NJERI TEST");
        assertThat(preview.get("accountNumberFinal").asBoolean()).isTrue();
    }

    @Test
    void aTemplateNeedingTheReferenceIsPreviewedAsNotFinalYet() throws Exception {
        saveSettings("400200", "TRENDYSH", "01100123#<ref>");

        JsonNode preview = preview("name", "Njeri");

        assertThat(preview.get("accountNumber").asText()).isEqualTo("01100123#YOUR PAYMENT REFERENCE");
        assertThat(preview.get("accountNumberFinal").asBoolean()).isFalse();
    }

    @Test
    void withNoPaybillNumberTheCheckoutIsToldPaymentDetailsFollowTheOrder() throws Exception {
        saveSettings(null, null, null);

        JsonNode preview = preview();

        assertThat(preview.get("configured").asBoolean()).isFalse();
    }
}
