package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.*;
import nmk_backend.service.ProcurementService;
import nmk_backend.service.AdminActionAuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/procurement")
public class ProcurementController {

    private final ProcurementService procurementService;
    private final AdminActionAuthorizationService authorizationService;

    public ProcurementController(ProcurementService procurementService, AdminActionAuthorizationService authorizationService) {
        this.procurementService = procurementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/suppliers")
    public ResponseEntity<?> getSuppliers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        try {
            return ResponseEntity.ok(procurementService.getSuppliers(page, size));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/suppliers")
    public ResponseEntity<?> createSupplier(@Valid @RequestBody SupplierCreateRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(procurementService.createSupplier(request));
        } catch (DataIntegrityViolationException exception) {
            // Supplier-name duplicates are translated by the service. Any integrity
            // violation reaching this layer may be a different database constraint.
            return conflict("Supplier could not be saved because a related database constraint was violated");
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PatchMapping("/suppliers/{supplierId}/active")
    public ResponseEntity<?> setSupplierActive(
            @PathVariable Long supplierId,
            @Valid @RequestBody SupplierStatusRequest request
    ) {
        try {
            return ResponseEntity.ok(procurementService.setSupplierActive(supplierId, request.active()));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/cost-report")
    public ResponseEntity<?> getProcurementCostReport(
            @RequestParam Long locationId,
            @RequestParam java.time.LocalDate from,
            @RequestParam java.time.LocalDate to
    ) {
        try {
            return ResponseEntity.ok(procurementService.getProcurementCostReport(locationId, from, to));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/purchase-orders")
    public ResponseEntity<?> getPurchaseOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        try {
            return ResponseEntity.ok(procurementService.getPurchaseOrders(page, size));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}")
    public ResponseEntity<?> getPurchaseOrder(@PathVariable Long purchaseOrderId) {
        try {
            return ResponseEntity.ok(procurementService.getPurchaseOrder(purchaseOrderId));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}/receipts")
    public ResponseEntity<?> getGoodsReceipts(
            @PathVariable Long purchaseOrderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        try {
            return ResponseEntity.ok(procurementService.getGoodsReceipts(purchaseOrderId, page, size));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}/supplier-returns")
    public ResponseEntity<?> getSupplierReturns(
            @PathVariable Long purchaseOrderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        try {
            return ResponseEntity.ok(procurementService.getSupplierReturns(purchaseOrderId, page, size));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/purchase-orders")
    public ResponseEntity<?> createPurchaseOrder(
            @Valid @RequestBody PurchaseOrderCreateRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    procurementService.createPurchaseOrder(request, authentication.getName())
            );
        } catch (DataIntegrityViolationException exception) {
            return conflict("A purchase order could not be saved because a related record changed");
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/submit")
    public ResponseEntity<?> submitPurchaseOrder(
            @PathVariable Long purchaseOrderId,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "PURCHASE_ORDER_SUBMIT");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.ok(procurementService.submitPurchaseOrder(purchaseOrderId));
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/receive")
    public ResponseEntity<?> receiveGoods(
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody GoodsReceiptRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "GOODS_RECEIPT");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    procurementService.receiveGoods(purchaseOrderId, request, authentication.getName())
            );
        } catch (DataIntegrityViolationException exception) {
            return conflict("Goods receipt idempotency key was already used or a related record changed");
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/supplier-returns")
    public ResponseEntity<?> returnGoods(
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody SupplierReturnRequest request,
            @RequestHeader(value = "X-Admin-Action-Authorization", required = false) String authorizationToken,
            Authentication authentication
    ) {
        try {
            authorizationService.consumeAuthorization(authentication.getName(), authorizationToken, "SUPPLIER_RETURN");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", exception.getMessage()));
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    procurementService.returnGoodsToSupplier(purchaseOrderId, request, authentication.getName())
            );
        } catch (DataIntegrityViolationException exception) {
            return conflict("Supplier return idempotency key was already used or a related record changed");
        } catch (IllegalArgumentException exception) {
            return badRequest(exception);
        }
    }

    private ResponseEntity<?> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "message", exception.getMessage() == null ? "Invalid procurement request" : exception.getMessage()
        ));
    }

    private ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }
}