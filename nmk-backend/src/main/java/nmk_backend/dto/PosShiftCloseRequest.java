package nmk_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PosShiftCloseRequest(
        @NotNull @DecimalMin("0.00") BigDecimal closingCashCount
) {
}