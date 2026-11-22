package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class RazorpayRefundRequestMySqlTest {
    @Autowired private RazorpayRefundRequestService requestService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderRefundRepository refundRepository;

    @Test
    void persistsAndReusesTheSameRefundRequestOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Order order = new Order();
        order.setCustomerName("Refund request idempotency test");
        order.setTotalAmount(new BigDecimal("125.00"));
        order.setPaymentStatus("PAID");
        order.setRazorpayPaymentId("pay_" + suffix);
        order.setRazorpayOrderId("order_" + suffix);
        order = orderRepository.saveAndFlush(order);

        String key = "refund_" + suffix;
        long before = refundRepository.count();

        var first = requestService.prepare(order.getId(), 5000L,
                "Customer cancellation", key, "admin");
        var retry = requestService.prepare(order.getId(), 5000L,
                "Customer cancellation", key, "admin");

        assertTrue(first.newlyCreated());
        assertFalse(retry.newlyCreated());
        assertEquals(first.localRefundId(), retry.localRefundId());
        assertEquals(first.receipt(), retry.receipt());
        assertEquals(before + 1, refundRepository.count());

        var history = requestService.listForOrder(order.getId());
        assertEquals(1, history.size());
        assertEquals(key, history.getFirst().requestIdempotencyKey());
        assertEquals("PENDING", history.getFirst().status());
    }
}
