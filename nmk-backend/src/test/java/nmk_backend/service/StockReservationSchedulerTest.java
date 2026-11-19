
package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockReservationSchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RazorpayService razorpayService;

    @Mock
    private OrderService orderService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private StockReservationScheduler scheduler;

    @BeforeEach
    void setUp() {

        scheduler = new StockReservationScheduler(
                orderRepository,
                razorpayService,
                orderService,
                transactionManager
        );
    }

    private Order expiredOrder(String creationStatus, String razorpayOrderId) {
        Order order = new Order();
        order.setStockReserved(true);
        order.setPaymentStatus("PENDING");
        order.setPaymentCreationStatus(creationStatus);
        order.setRazorpayOrderId(razorpayOrderId);
        order.setReservationExpiresAt(
                LocalDateTime.now().minusMinutes(10)
        );
        return order;
    }

    private void arrangeExpiredOrder(Order order) {
        when(orderRepository.findExpiredReservationIds(
                any(LocalDateTime.class)
        )).thenReturn(List.of(10L));

        when(orderRepository.findById(10L))
                .thenReturn(Optional.of(order));
    }

    @Test
    void shouldRetainStockWhenRazorpayOrderIdIsMissing() {
        Order order = expiredOrder("IN_PROGRESS", null);
        arrangeExpiredOrder(order);

        scheduler.expireReservations();

        verifyNoInteractions(razorpayService);
        verify(orderRepository, never()).findByIdForUpdate(anyLong());
        verify(orderService, never()).releaseReservation(anyLong());
    }

    @Test
    void shouldRetainStockWhenPaymentCreationIsInProgress() {
        Order order = expiredOrder("IN_PROGRESS", "order_123");
        arrangeExpiredOrder(order);

        scheduler.expireReservations();

        verifyNoInteractions(razorpayService);
        verify(orderRepository, never()).findByIdForUpdate(anyLong());
        verify(orderService, never()).releaseReservation(anyLong());
    }

    @Test
    void shouldRetainStockWhenRazorpayReportsPaymentNeedsRetention()
            throws Exception {
        Order order = expiredOrder("CREATED", "order_123");
        arrangeExpiredOrder(order);

        when(razorpayService.hasUnsettledOrSuccessfulPayment("order_123"))
                .thenReturn(true);

        scheduler.expireReservations();

        verify(orderRepository, never()).findByIdForUpdate(anyLong());
        verify(orderService, never()).releaseReservation(anyLong());
    }

    @Test
    void shouldRetainStockWhenRazorpayLookupFails() throws Exception {
        Order order = expiredOrder("CREATED", "order_123");
        arrangeExpiredOrder(order);

        when(razorpayService.hasUnsettledOrSuccessfulPayment("order_123"))
                .thenThrow(new RuntimeException("Provider unavailable"));

        scheduler.expireReservations();

        verify(orderRepository, never()).findByIdForUpdate(anyLong());
        verify(orderService, never()).releaseReservation(anyLong());
    }

    @Test
    void shouldReleaseStockWhenRazorpayConfirmsNoPaymentNeedsRetention()
            throws Exception {
        Order order = expiredOrder("CREATED", "order_123");
        arrangeExpiredOrder(order);

        when(razorpayService.hasUnsettledOrSuccessfulPayment("order_123"))
                .thenReturn(false);

        when(orderRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(order));

        scheduler.expireReservations();

        verify(orderRepository).findByIdForUpdate(10L);
        verify(orderService).releaseReservation(10L);
    }

    @Test
    void shouldNotReleaseWhenLockedOrderIsNoLongerEligible()
            throws Exception {
        Order snapshot = expiredOrder("CREATED", "order_123");
        arrangeExpiredOrder(snapshot);

        when(razorpayService.hasUnsettledOrSuccessfulPayment("order_123"))
                .thenReturn(false);

        Order lockedOrder = expiredOrder("CREATED", "order_123");
        lockedOrder.setPaymentStatus("PAID");

        when(orderRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(lockedOrder));

        scheduler.expireReservations();

        verify(orderRepository).findByIdForUpdate(10L);
        verify(orderService, never()).releaseReservation(anyLong());
    }
    @Test
    void shouldNotReleaseWhenLockedPaymentCreationIsNoLongerCreated()
            throws Exception {
        Order snapshot = expiredOrder("CREATED", "order_123");
        arrangeExpiredOrder(snapshot);

        when(razorpayService.hasUnsettledOrSuccessfulPayment("order_123"))
                .thenReturn(false);

        Order lockedOrder = expiredOrder("IN_PROGRESS", "order_123");

        when(orderRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(lockedOrder));

        scheduler.expireReservations();

        verify(orderRepository).findByIdForUpdate(10L);
        verify(orderService, never()).releaseReservation(anyLong());
    }
    private void stubTransactionManager() {
    when(transactionManager.getTransaction(
            any(TransactionDefinition.class)
    )).thenReturn(transactionStatus);
}
}
