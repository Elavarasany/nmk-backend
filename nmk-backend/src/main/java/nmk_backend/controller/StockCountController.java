package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.dto.StockCountLineRequest;
import nmk_backend.dto.StockCountStartRequest;
import nmk_backend.service.StockCountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory/stock-counts")
public class StockCountController {

    private final StockCountService stockCountService;

    public StockCountController(StockCountService stockCountService) {
        this.stockCountService = stockCountService;
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(@RequestParam Long locationId) {
        try {
            return ResponseEntity.ok(stockCountService.getCurrent(locationId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable Long sessionId) {
        try {
            return ResponseEntity.ok(stockCountService.getSession(sessionId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(
            @Valid @RequestBody StockCountStartRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    stockCountService.start(request, authentication.getName())
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PatchMapping("/{sessionId}/lines/{lineId}")
    public ResponseEntity<?> recordCount(
            @PathVariable Long sessionId,
            @PathVariable Long lineId,
            @Valid @RequestBody StockCountLineRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(stockCountService.recordCount(
                    sessionId, lineId, request, authentication.getName()
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/submit")
    public ResponseEntity<?> submit(
            @PathVariable Long sessionId,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(stockCountService.submit(sessionId, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/post")
    public ResponseEntity<?> post(
            @PathVariable Long sessionId,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(stockCountService.post(sessionId, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable Long sessionId,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(stockCountService.cancel(sessionId, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}