package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayRefundReconciliationServiceTest {
    @Mock private OrderRepository orderRepository;
    @Mock private OrderRefundRepository refundRepository;

    private RazorpayRefundReconciliationService service() {
        return new RazorpayRefundReconciliationService(orderRepository, refundRepository);
    }

    private Order paidOrder() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 17L);
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRefundStatus("NONE");
        return order;
    }

    private void stubNewRefund(Order order, long active, long processed) {
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));
        when(refundRepository.findByRazorpayRefundIdForUpdate("rfnd_123"))
                .thenReturn(Optional.empty());
        when(refundRepository.saveAndFlush(any(OrderRefund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRepository.sumActiveAmountPaiseByOrderId(17L)).thenReturn(active);
        when(refundRepository.sumProcessedAmountPaiseByOrderId(17L)).thenReturn(processed);
    }

    @Test
    void pendingRefundIsRecordedWithoutChangingCapturedPayment() {
        Order order = paidOrder();
        stubNewRefund(order, 5000L, 0L);

        service().reconcile("evt_1", "refund.created", "rfnd_123",
                "pay_123", 5000L, "INR", "pending");

        assertEquals("PAID", order.getPaymentStatus());
        assertEquals("PENDING", order.getRefundStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void processedPartialRefundIsReconciledWithoutBlockingOrderPayment() {
        Order order = paidOrder();
        stubNewRefund(order, 5000L, 5000L);

        service().reconcile("evt_2", "refund.processed", "rfnd_123",
                "pay_123", 5000L, "INR", "processed");

        assertEquals("PAID", order.getPaymentStatus());
        assertEquals("PARTIALLY_REFUNDED", order.getRefundStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void fullRefundMarksPaymentAsRefunded() {
        Order order = paidOrder();
        stubNewRefund(order, 12500L, 12500L);

        service().reconcile("evt_3", "refund.processed", "rfnd_123",
                "pay_123", 12500L, "INR", "processed");

        assertEquals("REFUNDED", order.getPaymentStatus());
        assertEquals("REFUNDED", order.getRefundStatus());
        assertEquals("CANCELLED", order.getOrderStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void refundTotalCannotExceedCapturedOrderAmount() {
        Order order = paidOrder();
        stubNewRefund(order, 13000L, 13000L);

        assertThrows(IllegalArgumentException.class, () ->
                service().reconcile("evt_4", "refund.processed", "rfnd_123",
                        "pay_123", 13000L, "INR", "processed"));

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void mismatchedProviderCurrencyIsRejected() {
        Order order = paidOrder();
        when(orderRepository.findByRazorpayPaymentIdForUpdate("pay_123"))
                .thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class, () ->
                service().reconcile("evt_5", "refund.processed", "rfnd_123",
                        "pay_123", 5000L, "USD", "processed"));

        verifyNoInteractions(refundRepository);
        verify(orderRepository, never()).save(any(Order.class));
    }
}
