package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.PosVoidRequest;
import nmk_backend.service.PosReturnService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/sales/{saleId}/void")
public class PosVoidController {

    private final PosReturnService returnService;

    public PosVoidController(PosReturnService returnService) {
        this.returnService = returnService;
    }

    @PostMapping
    public ResponseEntity<?> voidSale(
            @PathVariable Long saleId,
            @Valid @RequestBody PosVoidRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Manager authentication is required"));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    returnService.processVoid(saleId, request, authentication.getName())
            );
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "A void with this idempotency key or return number already exists. Retry the same request with the same key."
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid void request" : exception.getMessage()
            ));
        }
    }
}