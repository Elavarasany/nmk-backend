package nmk_backend.service;

import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class ProcurementMySqlIntegrationTest {
    @Autowired private ProcurementService procurement;
    @Autowired private AdminRepository admins;
    @Autowired private InventoryLocationRepository locations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryBalanceRepository balances;

    @Test
    void purchaseOrderReceiptSupplierReturnAndRetriesStayConsistentOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "mysql-procurement-" + suffix;

        Admin manager = new Admin();
        manager.setUsername(username);
        manager.setPassword("test-only-password");
        admins.saveAndFlush(manager);

        InventoryLocation location = new InventoryLocation();
        location.setCode("PR-" + suffix);
        location.setName("MySQL Procurement " + suffix);
        location.setActive(true);
        location = locations.saveAndFlush(location);

        Product product = new Product();
        product.setName("MySQL Procurement Frame " + suffix);
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = products.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("PR-" + suffix);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variants.saveAndFlush(variant);

        SupplierResponse supplier = procurement.createSupplier(
                new SupplierCreateRequest("Supplier " + suffix, "Contact", "9876543210", null, "Test address")
        );
        PurchaseOrderCreateRequest create = new PurchaseOrderCreateRequest(
                supplier.id(), location.getId(), "MySQL procurement lifecycle",
                List.of(new PurchaseOrderItemRequest(variant.getId(), 5, new BigDecimal("35.00"))),
                "MYSQL-PO-" + suffix
        );
        PurchaseOrderResponse draft = procurement.createPurchaseOrder(create, username);
        assertEquals("DRAFT", draft.status());
        assertEquals(new BigDecimal("175.00"), draft.subtotal());
        assertTrue(balances.findByLocationIdAndVariantId(location.getId(), variant.getId()).isEmpty(),
                "Creating a purchase order must not increase stock");

        PurchaseOrderResponse ordered = procurement.submitPurchaseOrder(draft.id());
        PurchaseOrderResponse submitReplay = procurement.submitPurchaseOrder(draft.id());
        assertEquals("ORDERED", ordered.status());
        assertEquals(ordered.id(), submitReplay.id());

        Long lineId = ordered.items().get(0).id();
        GoodsReceiptRequest firstReceiptRequest = new GoodsReceiptRequest(
                "First partial delivery checked", "MYSQL-GR-1-" + suffix,
                List.of(new GoodsReceiptItemRequest(lineId, 4))
        );
        GoodsReceiptResponse firstReceipt = procurement.receiveGoods(draft.id(), firstReceiptRequest, username);
        GoodsReceiptResponse firstReceiptReplay = procurement.receiveGoods(draft.id(), firstReceiptRequest, username);
        assertEquals(firstReceipt.id(), firstReceiptReplay.id());
        String otherUsername = "mysql-procurement-other-" + suffix;
        Admin otherManager = new Admin();
        otherManager.setUsername(otherUsername);
        otherManager.setPassword("test-only-password");
        admins.saveAndFlush(otherManager);
        assertThrows(IllegalArgumentException.class,
                () -> procurement.receiveGoods(draft.id(), firstReceiptRequest, otherUsername),
                "A different staff member must not replay another user's goods receipt key");
        assertEquals("PARTIALLY_RECEIVED", procurement.getPurchaseOrder(draft.id()).status());
        assertEquals(4, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity(),
                "A retried receipt must not add the same stock twice");

        GoodsReceiptResponse finalReceipt = procurement.receiveGoods(
                draft.id(),
                new GoodsReceiptRequest("Final unit received", "MYSQL-GR-2-" + suffix,
                        List.of(new GoodsReceiptItemRequest(lineId, 1))),
                username
        );
        assertEquals("RECEIVED", procurement.getPurchaseOrder(draft.id()).status());
        assertEquals(5, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity());

        SupplierReturnRequest returnRequest = new SupplierReturnRequest(
                "Two units returned to supplier", "MYSQL-SR-" + suffix,
                List.of(new SupplierReturnItemRequest(lineId, 2))
        );
        SupplierReturnResponse supplierReturn = procurement.returnGoodsToSupplier(draft.id(), returnRequest, username);
        SupplierReturnResponse supplierReturnReplay = procurement.returnGoodsToSupplier(draft.id(), returnRequest, username);
        assertEquals(supplierReturn.id(), supplierReturnReplay.id());
        assertThrows(IllegalArgumentException.class,
                () -> procurement.returnGoodsToSupplier(draft.id(), returnRequest, otherUsername),
                "A different staff member must not replay another user's supplier return key");
        assertEquals(3, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity(),
                "A retried supplier return must not deduct stock twice");

        ProcurementCostReportResponse report = procurement.getProcurementCostReport(
                location.getId(), LocalDate.now(), LocalDate.now()
        );
        assertEquals(5L, report.receivedQuantity());
        assertEquals(2L, report.returnedQuantity());
        assertEquals(new BigDecimal("175.00"), report.receivedCost());
        assertEquals(new BigDecimal("70.00"), report.returnedCost());
        assertEquals(new BigDecimal("105.00"), report.netRecordedProcurementCost());
    }
}
