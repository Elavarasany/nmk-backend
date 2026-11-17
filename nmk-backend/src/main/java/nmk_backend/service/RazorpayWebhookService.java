package nmk_backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import nmk_backend.entity.RazorpayWebhookEvent;
import nmk_backend.repository.RazorpayWebhookEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RazorpayWebhookService {

    private final RazorpayWebhookEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final RazorpayWebhookProcessor processor;

    public RazorpayWebhookService(
            RazorpayWebhookEventRepository eventRepository,
            ObjectMapper objectMapper,
            RazorpayWebhookProcessor processor) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.processor = processor;
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
        } catch (DataIntegrityViolationException duplicate) {
            throw duplicate;
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
                // Separate Spring bean: @Transactional is applied by the proxy.
                processor.processOne(id);
            } catch (Exception ignored) {
                // Transaction rollback leaves the event due for a later retry.
                // Never log raw payloads, payment IDs, or customer data.
            }
        }
    }
}
