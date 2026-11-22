package nmk_backend.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineCheckoutRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void rejectsOversizedCustomerFieldsAndCart() {
        CreateOrderRequest request = validRequest();
        request.setCustomerName("N".repeat(256));
        request.setEmail("not-an-email");
        request.setAddress("A".repeat(1001));

        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add(validItem());
        }
        request.setItems(items);

        Set<String> invalidFields = validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(invalidFields.contains("customerName"));
        assertTrue(invalidFields.contains("email"));
        assertTrue(invalidFields.contains("address"));
        assertTrue(invalidFields.contains("items"));
    }

    @Test
    void rejectsInvalidItemIdentifiersQuantityAndPrescriptionBounds() {
        CreateOrderRequest request = validRequest();
        OrderItemRequest item = validItem();
        item.setProductId(0L);
        item.setVariantId(null);
        item.setQuantity(0);
        item.setPrescriptionFileName("F".repeat(256));
        item.setPrescription(Map.of(
                "a", "1", "b", "2", "c", "3", "d", "4", "e", "5",
                "f", "6", "g", "7", "h", "8", "i", "9", "j", "10",
                "k", "11", "l", "12", "m", "13", "n", "14", "o", "15",
                "p", "16", "q", "17", "r", "18", "s", "19", "t", "20",
                "u", "21"
        ));
        request.setItems(List.of(item));

        Set<String> invalidFields = validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(invalidFields.contains("items[0].productId"));
        assertTrue(invalidFields.contains("items[0].variantId"));
        assertTrue(invalidFields.contains("items[0].quantity"));
        assertTrue(invalidFields.contains("items[0].prescriptionFileName"));
        assertTrue(invalidFields.contains("items[0].prescription"));
    }

    @Test
    void acceptsRequestsAtConfiguredFieldLimits() {
        CreateOrderRequest request = validRequest();
        request.setCustomerName("N".repeat(255));
        request.setEmail("customer@example.com");
        request.setAddress("A".repeat(1000));
        request.setItems(java.util.Collections.nCopies(100, validItem()));

        assertTrue(validator.validate(request).isEmpty());
    }

    private static CreateOrderRequest validRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerName("Customer");
        request.setEmail("customer@example.com");
        request.setPhone("9876543210");
        request.setAddress("Street");
        request.setCity("Town");
        request.setState("Tamil Nadu");
        request.setPincode("604201");
        request.setItems(List.of(validItem()));
        return request;
    }

    private static OrderItemRequest validItem() {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setVariantId(1L);
        item.setQuantity(1);
        return item;
    }
}
