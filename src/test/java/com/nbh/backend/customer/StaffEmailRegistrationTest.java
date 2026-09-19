package com.nbh.backend.customer;

import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff and shoppers share one sign-in page and staff are checked first, so a shopper account on
 * a staff email could never be reached. Registration must refuse it (with the same answer as any
 * duplicate email, so it cannot be used to discover who is staff).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffEmailRegistrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private StaffUserRepository staffUsers;
    @MockitoBean private EmailSender emailSender;

    @Test
    void registeringWithAStaffEmailIsRefused() throws Exception {
        String staffEmail = staffUsers.findAll().stream().findFirst().orElseThrow().getEmail();
        assertThat(staffEmail).isNotBlank();

        mvc.perform(post("/api/v1/customer/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + staffEmail.toUpperCase() + "\",\"password\":\"correct-horse-battery\",\"fullName\":\"Not Staff\"}"))
                .andExpect(status().isConflict());
    }
}
