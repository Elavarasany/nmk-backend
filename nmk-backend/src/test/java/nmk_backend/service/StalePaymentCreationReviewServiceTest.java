package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StalePaymentCreationReviewServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Test
    void returnsOnlyPendingOrdersWithAnOutstandingStockReservation() {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PENDING");
        order.setPaymentCreationStatus("IN_PROGRESS");
        ReflectionTestUtils.setField(order, "orderDate", now.minusMinutes(30));
        order.setStockReserved(true);
        order.setInventoryLocationId(7L);
        order.setReservationExpiresAt(now.plusMinutes(15));

        when(orderRepository.findExpiredStalePaymentCreations(
                eq(List.of("IN_PROGRESS", "CREATED")),
                eq("PENDING"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(PageRequest.class)
        )).thenReturn(new PageImpl<>(List.of(order)));

        var page = new StalePaymentCreationReviewService(orderRepository)
                .getStalePaymentCreations(0, 25, 10);

        assertEquals(1, page.getTotalElements());
        var item = page.getContent().get(0);
        assertEquals(new BigDecimal("125.00"), item.totalAmount());
        assertEquals("IN_PROGRESS", item.paymentCreationStatus());
        assertTrue(item.stockReserved());
        assertEquals(7L, item.inventoryLocationId());
        assertTrue(item.ageMinutes() >= 30);
    }

    @Test
    void validatesPaginationAndReviewAgeWithoutQueryingDatabase() {
        var service = new StalePaymentCreationReviewService(orderRepository);
        assertThrows(IllegalArgumentException.class, () -> service.getStalePaymentCreations(-1, 25, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getStalePaymentCreations(0, 101, 10));
        assertThrows(IllegalArgumentException.class, () -> service.getStalePaymentCreations(0, 25, 0));
        verifyNoInteractions(orderRepository);
    }
}
