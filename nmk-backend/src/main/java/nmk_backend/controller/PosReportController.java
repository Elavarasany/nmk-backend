package nmk_backend.controller;

import nmk_backend.service.PosReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<?> getInventoryReport(@RequestParam Long locationId) {
        try {
            return ResponseEntity.ok(reportService.getInventoryReport(locationId));
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
            @RequestParam LocalDate to
    ) {
        try {
            return ResponseEntity.ok(reportService.getSalesReport(locationId, from, to));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", exception.getMessage() == null ? "Invalid report request" : exception.getMessage()
            ));
        }
    }
}