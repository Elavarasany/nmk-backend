package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentVerificationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private PaymentVerificationService service;

    @Test
    void samePaymentIdIsAnIdempotentRetry() {
        Order order = new Order();
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_existing");

        when(orderRepository.findByRazorpayOrderIdForUpdate("order_1"))
                .thenReturn(Optional.of(order));

        PaymentVerificationService.ConfirmationResult result =
                service.confirmPayment("order_1", "pay_existing");

        assertFalse(result.newlyConfirmed());
        assertSame(order, result.order());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void differentPaymentIdForPaidOrderRequiresReconciliation() {
        Order order = new Order();
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_existing");

        when(orderRepository.findByRazorpayOrderIdForUpdate("order_1"))
                .thenReturn(Optional.of(order));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.confirmPayment("order_1", "pay_second")
        );

        assertTrue(error.getMessage().contains("additional captured payment"));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void paidOrderWithoutRecordedPaymentIdFailsClosed() {
        Order order = new Order();
        order.setPaymentStatus("PAID");

        when(orderRepository.findByRazorpayOrderIdForUpdate("order_1"))
                .thenReturn(Optional.of(order));

        assertThrows(
                IllegalStateException.class,
                () -> service.confirmPayment("order_1", "pay_new")
        );

        verify(orderRepository, never()).save(any());
    }

    @Test
    void missingPaymentIdentifiersAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.confirmPayment("order_1", " ")
        );

        verifyNoInteractions(orderRepository);
    }
}
