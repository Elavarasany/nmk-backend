package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.SupplierBillCreateRequest;
import nmk_backend.dto.SupplierPaymentRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierAccountServiceTest {
    private SupplierRepository supplierRepository;
    private SupplierBillRepository billRepository;
    private SupplierPaymentRepository paymentRepository;
    private SupplierBillLandedCostAllocationRepository allocationRepository;
    private PurchaseOrderRepository purchaseOrderRepository;
    private GoodsReceiptRepository goodsReceiptRepository;
    private AdminRepository adminRepository;
    private PosShiftService posShiftService;
    private SupplierAccountService service;
    private Supplier supplier;
    private Admin admin;

    @BeforeEach
    void setUp() {
        supplierRepository = mock(SupplierRepository.class);
        billRepository = mock(SupplierBillRepository.class);
        paymentRepository = mock(SupplierPaymentRepository.class);
        allocationRepository = mock(SupplierBillLandedCostAllocationRepository.class);
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        adminRepository = mock(AdminRepository.class);
        posShiftService = mock(PosShiftService.class);
        service = new SupplierAccountService(
                supplierRepository, billRepository, paymentRepository, allocationRepository,
                purchaseOrderRepository, goodsReceiptRepository, adminRepository, posShiftService,
                new ObjectMapper()
        );

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 7L);
        supplier.setName("Lens Supplier");
        supplier.setActive(true);

        admin = new Admin();
        admin.setUsername("admin.one");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        when(adminRepository.findByUsernameIgnoreCase("admin.one")).thenReturn(Optional.of(admin));
    }

    @Test
    void partialSupplierPaymentReducesBillBalanceWithoutExceedingIt() {
        SupplierBill bill = bill(12L, new BigDecimal("100.00"));
        when(paymentRepository.findByIdempotencyKey("PAY-12")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(bill));
        when(paymentRepository.sumConfirmedAmountByBillId(12L)).thenReturn(new BigDecimal("20.00"));
        when(paymentRepository.saveAndFlush(any(SupplierPayment.class))).thenAnswer(invocation -> {
            SupplierPayment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 88L);
            return payment;
        });

        var result = service.recordPayment(
                12L,
                new SupplierPaymentRequest(new BigDecimal("30.00"), "BANK", "UTR-7788", null, "PAY-12"),
                "admin.one",
                "ADMIN"
        );

        assertEquals(88L, result.id());
        assertEquals(new BigDecimal("30.00"), result.amount());
        assertEquals("CONFIRMED", result.status());
        assertEquals("BANK", result.paymentMethod());
        verify(paymentRepository).saveAndFlush(any(SupplierPayment.class));
        verifyNoInteractions(posShiftService);
    }

    @Test
    void supplierPaymentCannotExceedOutstandingBillBalance() {
        SupplierBill bill = bill(13L, new BigDecimal("100.00"));
        when(paymentRepository.findByIdempotencyKey("PAY-13")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(bill));
        when(paymentRepository.sumConfirmedAmountByBillId(13L)).thenReturn(new BigDecimal("80.00"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                service.recordPayment(
                        13L,
                        new SupplierPaymentRequest(new BigDecimal("30.00"), "UPI", "UPI-REF-13", null, "PAY-13"),
                        "admin.one",
                        "ADMIN"
                )
        );

        assertTrue(exception.getMessage().contains("exceeds the outstanding"));
        verify(paymentRepository, never()).saveAndFlush(any(SupplierPayment.class));
        verifyNoInteractions(posShiftService);
    }

    @Test
    void landedCostBillAllocatesItsAmountProRataAcrossReceivedGoodsValue() {
        PurchaseOrder order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", 31L);
        ReflectionTestUtils.setField(order, "orderNumber", "PO-31");
        order.setSupplier(supplier);
        order.setStatus("RECEIVED");

        GoodsReceipt receipt = new GoodsReceipt();
        GoodsReceiptItem first = receiptItem(101L, 2, "100.00");
        GoodsReceiptItem second = receiptItem(102L, 1, "100.00");
        receipt.getItems().addAll(List.of(first, second));

        when(supplierRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(supplier));
        when(billRepository.findByIdempotencyKey("LC-31")).thenReturn(Optional.empty(), Optional.empty());
        when(billRepository.existsBySupplierIdAndInvoiceNumberIgnoreCase(7L, "FRT-31")).thenReturn(false);
        when(purchaseOrderRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(order));
        when(goodsReceiptRepository.findByPurchaseOrderIdOrderByCreatedAtDesc(31L)).thenReturn(List.of(receipt));
        when(billRepository.saveAndFlush(any(SupplierBill.class))).thenAnswer(invocation -> {
            SupplierBill bill = invocation.getArgument(0);
            ReflectionTestUtils.setField(bill, "id", 41L);
            return bill;
        });
        when(allocationRepository.save(any(SupplierBillLandedCostAllocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepository.sumAllocatedAmountByBillId(41L)).thenReturn(new BigDecimal("30.00"));
        when(allocationRepository.findByBillIdOrderByIdAsc(41L)).thenAnswer(invocation ->
                List.of(
                        allocation(41L, first, new BigDecimal("20.00")),
                        allocation(42L, second, new BigDecimal("10.00"))
                ));
        when(paymentRepository.sumConfirmedAmountByBillId(41L)).thenReturn(BigDecimal.ZERO);

        var result = service.createBill(
                new SupplierBillCreateRequest(
                        7L, 31L, "FRT-31", LocalDate.of(2026, 11, 22),
                        LocalDate.of(2026, 12, 22), new BigDecimal("30.00"),
                        "LANDED_COST", "LC-31"
                ),
                "admin.one"
        );

        assertEquals("LANDED_COST", result.billType());
        assertEquals(2, result.landedCostAllocationCount());
        assertEquals(new BigDecimal("30.00"), result.landedCostAllocatedAmount());
        var captor = org.mockito.ArgumentCaptor.forClass(SupplierBillLandedCostAllocation.class);
        verify(allocationRepository, times(2)).save(captor.capture());
        assertEquals(new BigDecimal("20.00"), captor.getAllValues().get(0).getAllocatedAmount());
        assertEquals(new BigDecimal("10.00"), captor.getAllValues().get(1).getAllocatedAmount());
    }

    private SupplierBill bill(Long id, BigDecimal amount) {
        SupplierBill bill = new SupplierBill();
        ReflectionTestUtils.setField(bill, "id", id);
        bill.setSupplier(supplier);
        bill.setAmount(amount);
        bill.setStatus("POSTED");
        bill.setBillType("GOODS");
        return bill;
    }

    private GoodsReceiptItem receiptItem(Long id, int quantity, String unitCost) {
        GoodsReceiptItem item = new GoodsReceiptItem();
        ReflectionTestUtils.setField(item, "id", id);
        item.setQuantity(quantity);
        item.setUnitCost(new BigDecimal(unitCost));
        return item;
    }

    private SupplierBillLandedCostAllocation allocation(Long id, GoodsReceiptItem item, BigDecimal amount) {
        SupplierBillLandedCostAllocation allocation = new SupplierBillLandedCostAllocation();
        ReflectionTestUtils.setField(allocation, "id", id);
        allocation.setGoodsReceiptItem(item);
        allocation.setAllocatedAmount(amount);
        return allocation;
    }
}
