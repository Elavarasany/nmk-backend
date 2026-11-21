package nmk_backend.security;

import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosAuthorizationIntegrationTest {

    private static final String POS_LOCATIONS = "/api/admin/pos/locations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private JwtService jwtService;

    private String adminToken;

    @BeforeEach
    void setUp() {
        String username = "pos-security-test-" + java.util.UUID.randomUUID();
        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword("test-only-password");
        adminRepository.saveAndFlush(admin);
        adminToken = jwtService.generateToken(username);
    }

    @Test
    void anonymousRequestCannotAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)).andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerTokenCannotAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validAdminJwtCanAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "ordinary-user", roles = "USER")
    void authenticatedNonAdminCannotAccessPosEndpoint() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "cashier", roles = "CASHIER")
    void cashierCanAccessPosLocationPicker() throws Exception {
        mockMvc.perform(get(POS_LOCATIONS)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "cashier", roles = "CASHIER")
    void cashierCannotBrowseAllSalesHistory() throws Exception {
        mockMvc.perform(get("/api/admin/pos/sales")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager", roles = "MANAGER")
    void managerCanAccessSalesHistory() throws Exception {
        mockMvc.perform(get("/api/admin/pos/sales")).andExpect(status().isOk());
    }

    @Test
    void disabledStaffAccountCannotUsePreviouslyIssuedJwt() throws Exception {
        String username = "disabled-" + java.util.UUID.randomUUID();
        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword("test-only-password");
        admin.setEnabled(false);
        adminRepository.saveAndFlush(admin);
        String token = jwtService.generateToken(username);

        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}