package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.InventoryBalanceResponse;
import nmk_backend.dto.InventoryReceiptRequest;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import nmk_backend.service.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final AdminRepository adminRepository;

    public InventoryController(
            InventoryService inventoryService,
            AdminRepository adminRepository
    ) {
        this.inventoryService = inventoryService;
        this.adminRepository = adminRepository;
    }

    @PostMapping("/receive")
    public ResponseEntity<?> receiveStock(
            @Valid @RequestBody InventoryReceiptRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("message", "Admin authentication is required")
            );
        }

        Admin admin = adminRepository.findByUsername(authentication.getName())
                .orElse(null);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Map.of("message", "Authenticated admin account was not found")
            );
        }

        var balance = inventoryService.receiveStock(
                request.getLocationId(),
                request.getVariantId(),
                request.getQuantity(),
                request.getIdempotencyKey(),
                admin.getId(),
                request.getReason().trim()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InventoryBalanceResponse.from(balance));
    }
}
