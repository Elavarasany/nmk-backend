package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PosReportServiceTest {

    private PosSaleRepository saleRepository;
    private PosReturnRepository returnRepository;
    private InventoryLocationRepository locationRepository;
    private PosReportService service;

    @BeforeEach
    void setUp() {
        saleRepository = mock(PosSaleRepository.class);
        returnRepository = mock(PosReturnRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        service = new PosReportService(saleRepository, returnRepository, locationRepository);

        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setName("Main Store");
        when(locationRepository.findById(1L)).thenReturn(Optional.of(location));
    }

    @Test
    void reportCombinesSalesDiscountsTenderReturnsAndTopProducts() {
        LocalDate day = LocalDate.of(2026, 11, 21);
        PosSale sale = new PosSale();
        sale.setLocationId(1L);
        sale.setCashierUsername("cashier.one");
        sale.setStatus("COMPLETED");
        sale.setSubtotal(new BigDecimal("1000.00"));
        sale.setDiscountAmount(new BigDecimal("100.00"));
        sale.setGrandTotal(new BigDecimal("900.00"));
        ReflectionTestUtils.setField(sale, "createdAt", day.atTime(10, 30));

        PosPayment cash = new PosPayment();
        cash.setPaymentMethod("CASH");
        cash.setAmount(new BigDecimal("500.00"));
        sale.getPayments().add(cash);
        PosPayment upi = new PosPayment();
        upi.setPaymentMethod("UPI");
        upi.setAmount(new BigDecimal("400.00"));
        sale.getPayments().add(upi);

        PosSaleItem item = new PosSaleItem();
        item.setProductId(15L);
        item.setProductName("Classic Frame");
        item.setSku("FRAME-015");
        item.setQuantity(2);
        item.setLineTotal(new BigDecimal("1000.00"));
        sale.getItems().add(item);

        PosReturn returned = new PosReturn();
        returned.setSale(sale);
        returned.setStatus("COMPLETED");
        returned.setReturnType("RETURN");
        returned.setRefundTotal(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(returned, "createdAt", day.atTime(15, 45));
        PosRefundPayment refund = new PosRefundPayment();
        refund.setPaymentMethod("UPI");
        refund.setAmount(new BigDecimal("200.00"));
        returned.getRefundPayments().add(refund);

        PosReturn voided = new PosReturn();
        voided.setSale(sale);
        voided.setStatus("COMPLETED");
        voided.setReturnType("VOID");
        voided.setRefundTotal(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(voided, "createdAt", day.atTime(16, 15));
        PosRefundPayment voidRefund = new PosRefundPayment();
        voidRefund.setPaymentMethod("CASH");
        voidRefund.setAmount(new BigDecimal("100.00"));
        voided.getRefundPayments().add(voidRefund);

        when(saleRepository.findCompletedSalesForReport(eq(1L), any(), any()))
                .thenReturn(List.of(sale));
        when(returnRepository.findCompletedReturnsForReport(eq(1L), any(), any()))
                .thenReturn(List.of(returned, voided));

        var report = service.getSalesReport(1L, day, day);

        assertEquals(1, report.transactionCount());
        assertEquals(new BigDecimal("1000.00"), report.subtotal());
        assertEquals(new BigDecimal("100.00"), report.discounts());
        assertEquals(new BigDecimal("900.00"), report.netSalesBeforeRefunds());
        assertEquals(1, report.returnCount());
        assertEquals(new BigDecimal("300.00"), report.refunds());
        assertEquals(new BigDecimal("600.00"), report.netSalesAfterRefunds());
        assertEquals(new BigDecimal("500.00"), report.salesByPaymentMethod().get("CASH"));
        assertEquals(new BigDecimal("400.00"), report.salesByPaymentMethod().get("UPI"));
        assertEquals(new BigDecimal("200.00"), report.refundsByPaymentMethod().get("UPI"));
        assertEquals(new BigDecimal("100.00"), report.refundsByPaymentMethod().get("CASH"));
        assertEquals("cashier.one", report.cashiers().get(0).cashierUsername());
        assertEquals(2L, report.topProducts().get(0).quantitySold());
        assertEquals(new BigDecimal("600.00"), report.daily().get(0).netAfterRefunds());
        assertEquals(1, report.daily().get(0).returnCount(), "Voids must not be counted as customer returns");
    }

    @Test
    void rejectsInvalidOrExcessiveReportDateRanges() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.getSalesReport(1L, start, start.minusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> service.getSalesReport(1L, start, start.plusDays(368)));
        verifyNoInteractions(saleRepository, returnRepository);
    }
}