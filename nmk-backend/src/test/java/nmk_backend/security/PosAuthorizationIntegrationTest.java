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
    void cashierCannotReadManagerShiftHistory() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/pos/shifts/history?locationId=1")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadShiftHistory() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/pos/shifts/history?locationId=1")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
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
    void managerCannotProcessReturnWithoutFreshPasswordAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/returns")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"shiftId":1,"reason":"test return","idempotencyKey":"test-return-auth",
                   "items":[{"saleItemId":1,"quantity":1,"restockDisposition":"RESTOCK"}],"refundPayments":[]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotVoidWithoutFreshPasswordAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/void")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"shiftId":1,"reason":"test void","idempotencyKey":"test-void-auth","refundPayments":[]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotAdjustInventoryWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/inventory/adjust")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"variantId":1,"quantityDelta":1,"idempotencyKey":"test-adjust-auth","reason":"test"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotReceiveInventoryWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/inventory/receive")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"variantId":1,"quantity":1,"idempotencyKey":"test-receive-auth","reason":"test"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotPostOrCancelStockCountWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/inventory/stock-counts/1/post")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/inventory/stock-counts/1/cancel")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotReceivePurchaseOrderGoodsWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/procurement/purchase-orders/1/receive")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"reason":"test receipt","idempotencyKey":"test-goods-receipt-auth",
                   "items":[{"purchaseOrderItemId":1,"quantity":1}]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotReturnGoodsToSupplierWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/procurement/purchase-orders/1/supplier-returns")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"reason":"test supplier return","idempotencyKey":"test-supplier-return-auth",
                   "items":[{"purchaseOrderItemId":1,"quantity":1}]}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotSubmitPurchaseOrderWithoutFreshAuthorization() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/procurement/purchase-orders/1/submit")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotProcessExchanges() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/pos/sales/1/exchange")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerMustProvideFreshAuthorizationToProcessExchange() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/pos/sales/1/exchange")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"shiftId":1,"reason":"test exchange","idempotencyKey":"test-exchange-auth",
                   "returnItems":[{"saleItemId":1,"quantity":1,"restockDisposition":"RESTOCK"}],
                   "replacementItems":[{"productId":1,"variantId":1,"quantity":1}],
                   "replacementDiscountAmount":0.00,"collectionPayments":[],"refundPayments":[]}
                  """))
                .andExpect(status().isForbidden());
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
    void cashierCannotReadOrStartPhysicalStockCounts() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/inventory/stock-counts/current?locationId=1")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/inventory/stock-counts/start")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"locationId":1,"reason":"security test"}
                  """))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReadCurrentPhysicalStockCount() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(get("/api/admin/inventory/stock-counts/current?locationId=1")
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotAttemptInventoryAdjustmentEvenWithMalformedPayload() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(post("/api/admin/inventory/adjust")
                .header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotOpenStaffAdministrationEndpoints() throws Exception {
        String cashierToken = tokenForRole("CASHIER");
        mockMvc.perform(get("/api/admin/staff")
                .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotAuthorizeAdministratorOnlyDataClearingAction() throws Exception {
        String managerToken = tokenForRole("MANAGER");
        mockMvc.perform(post("/api/admin/auth/authorize-action")
                .header("Authorization", "Bearer " + managerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"password":"test-only-password","action":"CLEAR_ORDERS"}
                  """))
                .andExpect(status().isBadRequest());
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