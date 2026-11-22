package nmk_backend.controller;

import nmk_backend.service.PosReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/pos/reports")
public class PosReportController {

    private final PosReportService reportService;

    public PosReportController(PosReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/inventory")
    public ResponseEntity<?> getInventoryReport(@RequestParam Long locationId, Authentication authentication) {
        try {
            return ResponseEntity.ok(reportService.getInventoryReport(locationId, authentication.getName(), role(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid inventory report request" : exception.getMessage()
            ));
        }
    }

    @GetMapping("/sales")
    public ResponseEntity<?> getSalesReport(
            @RequestParam Long locationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(reportService.getSalesReport(locationId, from, to, authentication.getName(), role(authentication)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid report request" : exception.getMessage()
            ));
        }
    }
    private String role(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("ROLE_")).map(value -> value.substring(5))
                .findFirst().orElse("CASHIER");
    }
}