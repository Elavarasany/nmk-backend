package nmk_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Spy;
import nmk_backend.entity.Order;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookProcessorTest {

    @Mock
    private RazorpayWebhookEventRepository eventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private RazorpayService razorpayService;
    @Mock
    private PaymentVerificationService paymentVerificationService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RazorpayWebhookProcessor processor;

    private RazorpayWebhookEvent event(String payload) {
        RazorpayWebhookEvent event = new RazorpayWebhookEvent();
        event.setEventId("evt_test_1");
        event.setEventType("payment.captured");
        event.setRawPayload(payload);
        event.setStatus("RECEIVED");
        event.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        return event;
    }

    @Test
    void nonCaptureEventIsStoredAsProcessedWithoutChangingOrder() throws Exception {
        RazorpayWebhookEvent event = event("""
                {"event":"payment.failed","payload":{}}
                """);
        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));

        processor.processOne(1L);

        assertEquals("PROCESSED", event.getStatus());
        assertEquals(1, event.getAttemptCount());
        verifyNoInteractions(orderRepository, razorpayService, paymentVerificationService);
        verify(eventRepository).save(event);
    }

    @Test
    void capturedPaymentIsVerifiedWithProviderBeforeConfirmation() throws Exception {
        String payload = """
                {
                  "event":"payment.captured",
                  "payload":{"payment":{"entity":{
                    "id":"pay_123",
                    "order_id":"order_123",
                    "amount":12500
                  }}}
                }
                """;
        RazorpayWebhookEvent event = event(payload);
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("125.00"));

        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));
        when(razorpayService.isPaymentCapturedForOrder("pay_123", "order_123", 12500))
                .thenReturn(true);
        when(paymentVerificationService.confirmPayment("order_123", "pay_123"))
                .thenReturn(new PaymentVerificationService.ConfirmationResult(order, true));

        processor.processOne(1L);

        assertEquals("PROCESSED", event.getStatus());
        verify(razorpayService).isPaymentCapturedForOrder("pay_123", "order_123", 12500);
        verify(paymentVerificationService).confirmPayment("order_123", "pay_123");
    }

    @Test
    void providerNotYetReportingCaptureSchedulesRetry() throws Exception {
        String payload = """
                {
                  "event":"payment.captured",
                  "payload":{"payment":{"entity":{
                    "id":"pay_123",
                    "order_id":"order_123",
                    "amount":12500
                  }}}
                }
                """;
        RazorpayWebhookEvent event = event(payload);
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("125.00"));

        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));
        when(razorpayService.isPaymentCapturedForOrder("pay_123", "order_123", 12500))
                .thenReturn(false);

        processor.processOne(1L);

        assertEquals("RETRY", event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getNextAttemptAt());
        verifyNoInteractions(paymentVerificationService);
    }

    @Test
    void mismatchedAmountIsSentToManualReview() throws Exception {
        String payload = """
                {
                  "event":"payment.captured",
                  "payload":{"payment":{"entity":{
                    "id":"pay_123",
                    "order_id":"order_123",
                    "amount":999
                  }}}
                }
                """;
        RazorpayWebhookEvent event = event(payload);
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("125.00"));

        when(eventRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        when(orderRepository.findByRazorpayOrderId("order_123"))
                .thenReturn(Optional.of(order));

        processor.processOne(1L);

        assertEquals("MANUAL_REVIEW", event.getStatus());
        assertTrue(event.getProcessingError().contains("amount"));
        verifyNoInteractions(razorpayService, paymentVerificationService);
    }
}
