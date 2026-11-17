package nmk_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.entity.Order;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
public class RazorpayWebhookProcessor {

    private static final int MAX_ATTEMPTS = 8;

    private final RazorpayWebhookEventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final RazorpayService razorpayService;
    private final PaymentVerificationService paymentVerificationService;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookProcessor(
            RazorpayWebhookEventRepository eventRepository,
            OrderRepository orderRepository,
            RazorpayService razorpayService,
            PaymentVerificationService paymentVerificationService,
            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.razorpayService = razorpayService;
        this.paymentVerificationService = paymentVerificationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void processOne(Long eventId) throws Exception {
        RazorpayWebhookEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElse(null);

        if (event == null
                || !("RECEIVED".equals(event.getStatus())
                || "RETRY".equals(event.getStatus()))) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (event.getNextAttemptAt() != null
                && event.getNextAttemptAt().isAfter(now)) {
            return;
        }

        event.setStatus("PROCESSING");
        event.setProcessingStartedAt(now);
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setProcessingError(null);

        try {
            processPayload(event);
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            event.setNextAttemptAt(null);
            event.setProcessingError(null);

        } catch (ManualReviewException permanent) {
            event.setStatus("MANUAL_REVIEW");
            event.setProcessingError(safeMessage(permanent));

        } catch (Exception retryable) {
            if (event.getAttemptCount() >= MAX_ATTEMPTS) {
                event.setStatus("MANUAL_REVIEW");
                event.setProcessingError("Retry limit reached: " + safeMessage(retryable));
            } else {
                event.setStatus("RETRY");
                long delaySeconds = Math.min(
                        3600L,
                        15L << Math.min(event.getAttemptCount() - 1, 8)
                );
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
                event.setProcessingError(safeMessage(retryable));
            }
        }

        eventRepository.save(event);
    }

    private void processPayload(RazorpayWebhookEvent event) throws Exception {
        JsonNode root = objectMapper.readTree(event.getRawPayload());
        String eventType = root.path("event").asText("");

        // Only a signed payment.captured event can confirm an order.
        // Other event types remain durably recorded but do not change order state.
        if (!"payment.captured".equals(eventType)) {
            return;
        }

        JsonNode payment = root.path("payload").path("payment").path("entity");
        String paymentId = payment.path("id").asText("");
        String razorpayOrderId = payment.path("order_id").asText("");

        if (paymentId.isBlank() || razorpayOrderId.isBlank()
                || !payment.path("amount").canConvertToInt()) {
            throw new ManualReviewException("Captured payment payload is incomplete");
        }

        Order order = orderRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow(() ->
                        new ManualReviewException("No local order matches the captured payment")
                );

        int expectedPaise;
        try {
            expectedPaise = order.getTotalAmount()
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .intValueExact();
        } catch (Exception invalidAmount) {
            throw new ManualReviewException("Local order amount cannot be verified");
        }

        if (payment.path("amount").asInt() != expectedPaise) {
            throw new ManualReviewException("Captured payment amount does not match the order");
        }

        // Verify captured status, provider order ID and amount against Razorpay API.
        // A false result is retried because provider state may not yet be consistent.
        boolean captured = razorpayService.isPaymentCapturedForOrder(
                paymentId,
                razorpayOrderId,
                expectedPaise
        );

        if (!captured) {
            throw new IllegalStateException(
                    "Razorpay has not yet confirmed the captured payment"
            );
        }

        try {
            paymentVerificationService.confirmPayment(razorpayOrderId, paymentId);
        } catch (IllegalStateException | IllegalArgumentException conflict) {
            // Expired reservation or a different captured payment: preserve for
            // manual refund/reconciliation; never mark the webhook as successful.
            throw new ManualReviewException(conflict.getMessage());
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    private static class ManualReviewException extends Exception {
        private ManualReviewException(String message) {
            super(message);
        }
    }
}
