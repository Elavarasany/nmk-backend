package nmk_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.entity.Order;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.OrderRepository;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RazorpayWebhookService {

    private static final int MAX_ATTEMPTS = 8;

    private final RazorpayWebhookEventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final RazorpayService razorpayService;
    private final PaymentVerificationService paymentVerificationService;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookService(
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
    public void storeEvent(String eventId, String rawPayload) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 150) {
            throw new IllegalArgumentException("Invalid Razorpay event ID");
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventType = root.path("event").asText("");

            if (eventType.isBlank() || eventType.length() > 100) {
                throw new IllegalArgumentException("Webhook event type is missing or invalid");
            }

            RazorpayWebhookEvent event = new RazorpayWebhookEvent();
            event.setEventId(eventId);
            event.setEventType(eventType);
            event.setRawPayload(rawPayload);
            event.setStatus("RECEIVED");
            eventRepository.saveAndFlush(event);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Webhook body is not valid JSON");
        }
    }

    @Scheduled(fixedDelayString = "${razorpay.webhook.poll-delay-ms:15000}")
    public void processDueEvents() {
        List<Long> ids = eventRepository.findDueEventIds(
                LocalDateTime.now(),
                PageRequest.of(0, 25)
        );

        for (Long id : ids) {
            try {
                processOneInNewTransaction(id);
            } catch (Exception ignored) {
                // The transaction rolls back. The row remains due and will retry.
                // Do not log raw payloads, payment IDs, or customer data.
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOneInNewTransaction(Long eventId) throws Exception {
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
        eventRepository.save(event);

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

        // Only payment.captured can confirm an order. Other signed event types
        // are durably acknowledged but do not change payment/order state.
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

        // Verify against Razorpay's API, not merely the signed webhook JSON.
        // A false result is retried because the provider may not yet report capture.
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
            // Reservation expired or a different payment was already recorded.
            // Do not retry forever or silently mark the order paid.
            throw new ManualReviewException(conflict.getMessage());
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        // Never persist full exception dumps or raw provider payloads.
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    private static class ManualReviewException extends Exception {
        private ManualReviewException(String message) {
            super(message);
        }
    }
}
