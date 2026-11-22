package nmk_backend.service;

import nmk_backend.dto.RazorpaySettlementProviderSummary;
import nmk_backend.dto.RazorpaySettlementReconEntry;
import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.entity.RazorpaySettlement;
import nmk_backend.entity.RazorpaySettlementEntry;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpaySettlementEntryRepository;
import nmk_backend.repository.RazorpaySettlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpaySettlementPersistenceServiceTest {
    @Mock private RazorpaySettlementRepository settlementRepository;
    @Mock private RazorpaySettlementEntryRepository entryRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderRefundRepository refundRepository;

    private RazorpaySettlementPersistenceService service() {
        return new RazorpaySettlementPersistenceService(
                settlementRepository, entryRepository, orderRepository, refundRepository);
    }

    private void stubPersistence() {
        when(settlementRepository.findByProviderSettlementIdForUpdate("setl_123"))
                .thenReturn(Optional.empty());
        when(settlementRepository.saveAndFlush(any(RazorpaySettlement.class)))
                .thenAnswer(invocation -> {
                    RazorpaySettlement settlement = invocation.getArgument(0);
                    ReflectionTestUtils.setField(settlement, "id", 500L);
                    return settlement;
                });
        when(entryRepository.findBySettlementAndProviderEntityForUpdate(
                eq(500L), anyString(), anyString())).thenReturn(Optional.empty());
        when(entryRepository.save(any(RazorpaySettlementEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(settlementRepository.save(any(RazorpaySettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void matchesPaymentAndRefundAndBalancesProviderSettlement() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 12L);
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayOrderId("order_123");
        order.setRazorpayPaymentId("pay_123");

        OrderRefund refund = new OrderRefund();
        ReflectionTestUtils.setField(refund, "id", 77L);
        refund.setOrder(order);
        refund.setRazorpayRefundId("rfnd_123");
        refund.setRazorpayPaymentId("pay_123");
        refund.setAmountPaise(1000L);
        refund.setCurrency("INR");
        refund.setStatus("PROCESSED");

        when(orderRepository.findByRazorpayOrderId("order_123")).thenReturn(Optional.of(order));
        when(orderRepository.findByRazorpayPaymentId("pay_123")).thenReturn(Optional.of(order));
        when(refundRepository.findByRazorpayRefundId("rfnd_123")).thenReturn(Optional.of(refund));
        stubPersistence();

        LocalDateTime now = LocalDateTime.now();
        RazorpaySettlementProviderSummary summary = new RazorpaySettlementProviderSummary(
                "setl_123", 96100L, "processed", 2900L, 0L,
                "INR", "utr_123", now, "{}");
        RazorpaySettlementReconEntry payment = new RazorpaySettlementReconEntry(
                "pay_123", "payment", 0L, 97100L, 100000L, "INR",
                2900L, 0L, false, true, now, now, "setl_123",
                "utr_123", "", "order_123", "card", "", "{}");
        RazorpaySettlementReconEntry providerRefund = new RazorpaySettlementReconEntry(
                "rfnd_123", "refund", 1000L, 0L, 1000L, "INR",
                0L, 0L, false, true, now, now, "setl_123",
                "utr_123", "pay_123", "order_123", "card", "", "{}");

        var result = service().persist(
                java.util.List.of(summary),
                java.util.List.of(payment, providerRefund),
                1, 0);

        assertEquals(1, result.settlementCount());
        assertEquals(2, result.entryCount());
        ArgumentCaptor<RazorpaySettlement> captor = ArgumentCaptor.forClass(RazorpaySettlement.class);
        verify(settlementRepository, atLeastOnce()).save(captor.capture());
        RazorpaySettlement saved = captor.getAllValues().getLast();
        assertEquals("PROVIDER_RECONCILED", saved.getReconciliationStatus());
        assertTrue(saved.isTotalsBalanced());
        assertEquals(2, saved.getMatchedEntries());
        assertEquals(0, saved.getUnmatchedEntries());
        assertEquals(96100L, saved.getNetRowsPaise());
    }

    @Test
    void marksTransfersForManualReviewEvenWhenProviderTotalsBalance() {
        stubPersistence();
        LocalDateTime now = LocalDateTime.now();
        RazorpaySettlementProviderSummary summary = new RazorpaySettlementProviderSummary(
                "setl_123", -100L, "processed", 0L, 0L,
                "INR", "utr_123", now, "{}");
        RazorpaySettlementReconEntry transfer = new RazorpaySettlementReconEntry(
                "trf_123", "transfer", 100L, 0L, 100L, "INR",
                0L, 0L, false, true, now, now, "setl_123",
                "utr_123", "", "", "", "", "{}");

        service().persist(java.util.List.of(summary), java.util.List.of(transfer), 1, 0);

        ArgumentCaptor<RazorpaySettlement> captor = ArgumentCaptor.forClass(RazorpaySettlement.class);
        verify(settlementRepository, atLeastOnce()).save(captor.capture());
        RazorpaySettlement saved = captor.getAllValues().getLast();
        assertEquals("REVIEW_REQUIRED", saved.getReconciliationStatus());
        assertEquals(1, saved.getManualReviewEntries());
        assertTrue(saved.isTotalsBalanced());
    }
}
