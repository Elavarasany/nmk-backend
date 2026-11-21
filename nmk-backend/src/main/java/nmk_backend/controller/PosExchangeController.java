package nmk_backend.controller;
import jakarta.validation.Valid;
import nmk_backend.dto.PosExchangeRequest;
import nmk_backend.service.PosExchangeService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin/pos/sales/{saleId}/exchange")
public class PosExchangeController {
 private final PosExchangeService service;
 public PosExchangeController(PosExchangeService service) { this.service=service; }
 @PostMapping
 public ResponseEntity<?> exchange(@PathVariable Long saleId,@Valid @RequestBody PosExchangeRequest request,Authentication auth) {
  if(auth==null||auth.getName()==null||auth.getName().isBlank())
   return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message","POS staff authentication is required"));
  String role=auth.getAuthorities().stream().map(a->a.getAuthority()).filter(a->a.startsWith("ROLE_"))
   .map(a->a.substring(5)).findFirst().orElse("CASHIER");
  try { return ResponseEntity.status(HttpStatus.CREATED).body(service.exchange(saleId,request,auth.getName(),role)); }
  catch(DataIntegrityViolationException ex) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","Exchange key already exists; retry the identical request.")); }
  catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("message",ex.getMessage()==null?"Invalid exchange request":ex.getMessage())); }
 }
}