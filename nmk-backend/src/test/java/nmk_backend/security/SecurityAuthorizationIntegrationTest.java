
package nmk_backend.security;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import nmk_backend.security.JwtService;

import nmk_backend.repository.AppointmentRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class SecurityAuthorizationIntegrationTest {
    @Autowired
private AdminRepository adminRepository;

@Autowired
private JwtService jwtService;

@Autowired
private PasswordEncoder passwordEncoder;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }
    @Test
void validAdminTokenCanAccessAdminProductList() throws Exception {

    String username = "security-test-admin";

    adminRepository.findByUsername(username)
            .ifPresent(adminRepository::delete);

    Admin admin = new Admin();
    admin.setUsername(username);
    admin.setPassword(passwordEncoder.encode("TestOnlyPassword123!"));

    adminRepository.saveAndFlush(admin);

    try {
        String token = jwtService.generateToken(username);

        mockMvc.perform(get("/api/admin/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

    } finally {
        adminRepository.findByUsername(username)
                .ifPresent(adminRepository::delete);
    }
}

    @Test
    void publicProductListCanBeAccessedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Test
    void clearingAppointmentsWithoutActionAuthorizationReturnsForbidden()
            throws Exception {

        String username = "appointment-security-test-admin";

        adminRepository.findByUsername(username)
                .ifPresent(adminRepository::delete);

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode("TestOnlyPassword123!"));

        adminRepository.saveAndFlush(admin);

        try {
            String token = jwtService.generateToken(username);

            long appointmentsBefore = appointmentRepository.count();

            mockMvc.perform(delete("/api/appointments/clear")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("Fresh password authorization is required"));

            assertEquals(appointmentsBefore, appointmentRepository.count());

        } finally {
            adminRepository.findByUsername(username)
                    .ifPresent(adminRepository::delete);
        }
    }

    @Test
    void clearingAppointmentsWithInvalidActionAuthorizationReturnsForbidden()
            throws Exception {

        String username = "appointment-invalid-token-test-admin";

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode("TestOnlyPassword123!"));

        adminRepository.saveAndFlush(admin);

        try {
            String token = jwtService.generateToken(username);
            long appointmentsBefore = appointmentRepository.count();

            mockMvc.perform(delete("/api/appointments/clear")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Admin-Action-Authorization", "invalid-test-token"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("Invalid or expired authorization"));

            assertEquals(appointmentsBefore, appointmentRepository.count());

        } finally {
            adminRepository.findByUsername(username)
                    .ifPresent(adminRepository::delete);
        }
    }

    @Test
    void productCreationIsBlockedWithoutAdminToken() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized product\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void productUpdateIsBlockedWithoutAdminToken() throws Exception {
        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized update\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProductListIsBlockedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProductDeletionIsBlockedWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/admin/products/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminOrdersAreBlockedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isForbidden());
    }
}
