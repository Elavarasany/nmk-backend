package nmk_backend.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrescriptionPayloadValidationTest {

    @Test
    void acceptsNormalManualPrescriptionPayload() {
        Map<String, Object> right = Map.of(
                "sph", "-1.25",
                "cyl", "-0.50",
                "axis", "090",
                "add", ""
        );
        Map<String, Object> left = Map.of(
                "sph", "-1.00",
                "cyl", "-0.25",
                "axis", "085",
                "add", ""
        );
        Map<String, Object> prescription = Map.of(
                "mode", "manual",
                "sameForBothEyes", false,
                "hasCylinder", true,
                "needsAddition", false,
                "right", right,
                "left", left
        );

        assertDoesNotThrow(
                () -> OrderService.validatePrescriptionPayload(prescription)
        );
    }

    @Test
    void rejectsOversizedPrescriptionField() {
        Map<String, Object> prescription = Map.of(
                "mode", "image",
                "imageUrl", "x".repeat(2049)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> OrderService.validatePrescriptionPayload(prescription)
        );
    }

    @Test
    void rejectsExcessivelyNestedPrescriptionData() {
        Map<String, Object> prescription = Map.of(
                "right", Map.of(
                        "nested", Map.of(
                                "deeper", Map.of("value", "1")
                        )
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> OrderService.validatePrescriptionPayload(prescription)
        );
    }

    @Test
    void rejectsUnsupportedCollectionValues() {
        Map<String, Object> prescription = Map.of(
                "mode", "manual",
                "values", java.util.List.of("unexpected")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> OrderService.validatePrescriptionPayload(prescription)
        );
    }

    @Test
    void rejectsSerializedPrescriptionOverEightKilobytes() {
        String largeValue = "x".repeat(1800);
        Map<String, Object> prescription = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            prescription.put("field" + i, Map.of("value", largeValue));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> OrderService.validatePrescriptionPayload(prescription)
        );
    }

    @Test
    void rejectsMapsWithTooManyFields() {
        Map<String, Object> prescription = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            prescription.put("field" + i, "value");
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> OrderService.validatePrescriptionPayload(prescription)
        );
    }
}
