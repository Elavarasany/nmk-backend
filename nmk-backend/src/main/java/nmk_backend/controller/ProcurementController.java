package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.*;
import nmk_backend.service.ProcurementService;
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

    public ProcurementController(ProcurementService procurementService) {
        this.procurementService = procurementService;
    }

    @GetMapping("/suppliers")
    public ResponseEntity<?> getSuppliers() {
        return ResponseEntity.ok(procurementService.getSuppliers());
    }

    @PostMapping("/suppliers")
    public ResponseEntity<?> createSupplier(@Valid @RequestBody SupplierCreateRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(procurementService.createSupplier(request));
        } catch (DataIntegrityViolationException exception) {
            return conflict("A supplier with this name already exists");
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

    @GetMapping("/purchase-orders")
    public ResponseEntity<?> getPurchaseOrders() {
        return ResponseEntity.ok(procurementService.getPurchaseOrders());
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}")
    public ResponseEntity<?> getPurchaseOrder(@PathVariable Long purchaseOrderId) {
        try {
            return ResponseEntity.ok(procurementService.getPurchaseOrder(purchaseOrderId));
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
    public ResponseEntity<?> submitPurchaseOrder(@PathVariable Long purchaseOrderId) {
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
            Authentication authentication
    ) {
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
            Authentication authentication
    ) {
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