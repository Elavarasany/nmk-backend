package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcurementServiceTest {

    private SupplierRepository supplierRepository;
    private PurchaseOrderRepository purchaseOrderRepository;
    private PurchaseOrderItemRepository purchaseOrderItemRepository;
    private GoodsReceiptRepository goodsReceiptRepository;
    private SupplierReturnRepository supplierReturnRepository;
    private InventoryLocationRepository locationRepository;
    private ProductVariantRepository variantRepository;
    private AdminRepository adminRepository;
    private InventoryService inventoryService;
    private ProcurementService service;

    private Supplier supplier;
    private InventoryLocation location;
    private ProductVariant variant;
    private Admin manager;
    private PurchaseOrder order;
    private PurchaseOrderItem orderItem;

    @BeforeEach
    void setUp() {
        supplierRepository = mock(SupplierRepository.class);
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        purchaseOrderItemRepository = mock(PurchaseOrderItemRepository.class);
        goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        supplierReturnRepository = mock(SupplierReturnRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        adminRepository = mock(AdminRepository.class);
        inventoryService = mock(InventoryService.class);

        service = new ProcurementService(
                supplierRepository, purchaseOrderRepository, purchaseOrderItemRepository,
                goodsReceiptRepository, supplierReturnRepository, locationRepository,
                variantRepository, adminRepository, inventoryService, new ObjectMapper()
        );

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 2L);
        supplier.setName("Optical Frames Supplier");
        supplier.setActive(true);

        location = new InventoryLocation();
        location.setId(1L);
        location.setName("Main Store");
        location.setActive(true);

        Product product = new Product();
        product.setName("Classic Frame");
        variant = new ProductVariant();
        ReflectionTestUtils.setField(variant, "id", 5L);
        variant.setProduct(product);
        variant.setSku("FRAME-005");
        variant.setVariantName("Black");
        variant.setActive(true);

        manager = new Admin();
        manager.setId(7L);
        manager.setUsername("manager.one");

        order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", 20L);
        ReflectionTestUtils.setField(order, "orderNumber", "PO-TEST-002");
        order.setSupplier(supplier);
        order.setLocation(location);
        order.setStatus("ORDERED");
        order.setCreatedByUsername("manager.one");

        orderItem = new PurchaseOrderItem();
        ReflectionTestUtils.setField(orderItem, "id", 200L);
        orderItem.setPurchaseOrder(order);
        orderItem.setVariant(variant);
        orderItem.setOrderedQuantity(10);
        orderItem.setReceivedQuantity(0);
        orderItem.setReturnedQuantity(0);
        orderItem.setUnitCost(new BigDecimal("250.00"));
        orderItem.setLineTotal(new BigDecimal("2500.00"));
        order.getItems().add(orderItem);

        when(adminRepository.findByUsernameIgnoreCase("manager.one")).thenReturn(Optional.of(manager));
    }

    @Test
    void createPurchaseOrderStoresSupplierCostAndSubtotalWithoutChangingStock() {
        AtomicReference<PurchaseOrder> savedOrder = new AtomicReference<>();
        when(purchaseOrderRepository.findByIdempotencyKey("po-create-key-1"))
                .thenAnswer(invocation -> Optional.ofNullable(savedOrder.get()));
        when(supplierRepository.findById(2L)).thenReturn(Optional.of(supplier));
        when(locationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(location));
        when(variantRepository.findById(5L)).thenReturn(Optional.of(variant));
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 21L);
            ReflectionTestUtils.setField(saved, "orderNumber", "PO-TEST-NEW");
            savedOrder.set(saved);
            return saved;
        });

        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest(
                2L, 1L, "Initial purchase",
                List.of(new PurchaseOrderItemRequest(5L, 4, new BigDecimal("250.00"))),
                "po-create-key-1"
        );
        var response = service.createPurchaseOrder(request, "manager.one");
        var replay = service.createPurchaseOrder(request, "manager.one");

        assertEquals("DRAFT", response.status());
        assertEquals(new BigDecimal("1000.00"), response.subtotal());
        assertEquals(4, response.items().get(0).orderedQuantity());
        assertEquals(response.id(), replay.id());
        verify(purchaseOrderRepository, times(1)).saveAndFlush(any(PurchaseOrder.class));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void goodsReceiptAddsInventoryAndTracksPartialDelivery() {
        when(goodsReceiptRepository.findByIdempotencyKey("gr-key-1")).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));
        when(goodsReceiptRepository.saveAndFlush(any(GoodsReceipt.class))).thenAnswer(invocation -> {
            GoodsReceipt receipt = invocation.getArgument(0);
            ReflectionTestUtils.setField(receipt, "id", 70L);
            ReflectionTestUtils.setField(receipt, "receiptNumber", "GR-TEST-070");
            return receipt;
        });
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GoodsReceiptResponse response = service.receiveGoods(
                20L,
                new GoodsReceiptRequest(
                        "Supplier delivery received and checked",
                        "gr-key-1",
                        List.of(new GoodsReceiptItemRequest(200L, 4))
                ),
                "manager.one"
        );

        assertEquals("GR-TEST-070", response.receiptNumber());
        assertEquals(4, orderItem.getReceivedQuantity());
        assertEquals("PARTIALLY_RECEIVED", order.getStatus());
        verify(inventoryService).receivePurchaseOrderStock(
                1L, 5L, 4, "GR:gr-key-1:200", 7L,
                "Supplier delivery received and checked", 70L
        );
    }

    @Test
    void goodsReceiptCannotExceedOutstandingPurchaseOrderQuantity() {
        orderItem.setReceivedQuantity(8);
        when(goodsReceiptRepository.findByIdempotencyKey("gr-key-2")).thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));

        assertThrows(IllegalArgumentException.class, () -> service.receiveGoods(
                20L,
                new GoodsReceiptRequest(
                        "Over delivery attempt", "gr-key-2",
                        List.of(new GoodsReceiptItemRequest(200L, 3))
                ),
                "manager.one"
        ));
        verify(goodsReceiptRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void supplierReturnCannotExceedReceivedQuantityNotPreviouslyReturned() {
        order.setStatus("RECEIVED");
        orderItem.setReceivedQuantity(5);
        orderItem.setReturnedQuantity(2);
        when(supplierReturnRepository.findByIdempotencyKey("supplier-return-key"))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(purchaseOrderItemRepository.findByOrderAndIdForUpdate(20L, 200L))
                .thenReturn(Optional.of(orderItem));

        assertThrows(IllegalArgumentException.class, () -> service.returnGoodsToSupplier(
                20L,
                new SupplierReturnRequest(
                        "Damaged shipment", "supplier-return-key",
                        List.of(new SupplierReturnItemRequest(200L, 4))
                ),
                "manager.one"
        ));
        verify(supplierReturnRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventoryService);
    }
}