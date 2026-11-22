package nmk_backend.controller;

import nmk_backend.service.StalePaymentCreationReviewService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/payments/stale-creation-review")
public class StalePaymentCreationReviewController {

    private final StalePaymentCreationReviewService reviewService;

    public StalePaymentCreationReviewController(StalePaymentCreationReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<?> getStalePaymentCreations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "10") int olderThanMinutes
    ) {
        try {
            Page<?> result = reviewService.getStalePaymentCreations(page, size, olderThanMinutes);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
