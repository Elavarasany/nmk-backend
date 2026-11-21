package nmk_backend.security;

import nmk_backend.entity.Admin;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.InventoryLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    private InventoryLocationRepository locationRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenForRole("ADMIN");
    }

    private String tokenForRole(String role) {
        String username = "pos-security-" + role.toLowerCase() + "-" + java.util.UUID.randomUUID();
        Admin account = new Admin();
        account.setUsername(username);
        account.setPassword("test-only-password");
        account.setRole(role);
        account.setEnabled(true);
        adminRepository.saveAndFlush(account);
        return jwtService.generateToken(username);
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
    void cashierCanAccessPosLocationPicker() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get(POS_LOCATIONS)
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk());
    }


    @Test
    void cashierCannotApplySaleDiscountThroughPosCheckoutApi() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        String body = """
                {
                  "locationId": 1,
                  "shiftId": 1,
                  "discountAmount": 10.00,
                  "idempotencyKey": "cashier-discount-security-test",
                  "items": [{"productId": 1, "variantId": 1, "quantity": 1}],
                  "payments": [{"paymentMethod": "CASH", "amount": 90.00, "tenderedAmount": 90.00}]
                }
                """;

        mockMvc.perform(post("/api/admin/pos/checkout")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierCannotBrowseAllSalesHistory() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/pos/sales")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanAccessSalesHistory() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/pos/sales")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotProcessCustomerReturns() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/returns")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReachReturnRequestValidation() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/returns")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void cashierCannotVoidCompletedSale() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReachSaleVoidRequestValidation() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierCannotVoidCompletedSales() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReachFullVoidRequestValidation() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void cashierCannotReadStockMovementHistory() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/inventory/movements?locationId=1")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadStockMovementHistory() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        InventoryLocation location = new InventoryLocation();
        location.setCode("PM-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        location.setName("POS movement security test");
        location.setActive(true);
        InventoryLocation saved = locationRepository.saveAndFlush(location);

        mockMvc.perform(get("/api/admin/inventory/movements?locationId=" + saved.getId())
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
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