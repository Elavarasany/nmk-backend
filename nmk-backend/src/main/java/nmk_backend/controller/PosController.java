package nmk_backend.controller;

import nmk_backend.dto.PosCheckoutRequest;
import nmk_backend.dto.PosSaleResponse;
import nmk_backend.service.PosCheckoutService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos")
public class PosController {

    private final PosCheckoutService checkoutService;

    public PosController(PosCheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(
            @Valid @RequestBody PosCheckoutRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Admin authentication is required"));
        }

        try {
            PosSaleResponse response = checkoutService.checkout(
                    request,
                    authentication.getName()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (DataIntegrityViolationException exception) {
            // Covers simultaneous requests racing on the unique sale/stock idempotency keys.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message",
                    "A checkout with this idempotency key is already being processed or was already recorded. Retry using the same key to retrieve the completed sale."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message",
                    exception.getMessage() == null ? "Invalid checkout request" : exception.getMessage()
            ));
        }
    }
}
