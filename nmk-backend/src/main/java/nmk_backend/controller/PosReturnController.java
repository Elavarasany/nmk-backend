package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.PosReturnRequest;
import nmk_backend.service.PosReturnService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/sales/{saleId}/returns")
public class PosReturnController {

    private final PosReturnService returnService;

    public PosReturnController(PosReturnService returnService) {
        this.returnService = returnService;
    }

    @GetMapping
    public ResponseEntity<?> getReturns(@PathVariable Long saleId) {
        try {
            return ResponseEntity.ok(returnService.getReturns(saleId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> processReturn(
            @PathVariable Long saleId,
            @Valid @RequestBody PosReturnRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Manager authentication is required"));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    returnService.processReturn(saleId, request, authentication.getName())
            );
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "A return with this idempotency key or return number already exists. Retry the same request with the same key."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid return request" : exception.getMessage()
            ));
        }
    }
}