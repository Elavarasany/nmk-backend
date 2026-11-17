package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.UUID;

@Service
public class PaymentVerificationService {

    private final OrderRepository orderRepository;

    public PaymentVerificationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmationResult confirmPayment(
            String razorpayOrderId,
            String razorpayPaymentId) {

        if (razorpayOrderId == null || razorpayOrderId.isBlank()
                || razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "Razorpay order ID and payment ID are required."
            );
        }

        // Serialize every confirmation for this Razorpay order.
        Order order = orderRepository
                .findByRazorpayOrderIdForUpdate(razorpayOrderId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Order not found")
                );

        // A repeated callback for the SAME captured payment is safe.
        // A different captured payment is not a retry: it is a second charge.
        if ("PAID".equalsIgnoreCase(order.getPaymentStatus())) {
            String recordedPaymentId = order.getRazorpayPaymentId();

            if (recordedPaymentId == null || recordedPaymentId.isBlank()) {
                throw new IllegalStateException(
                        "This order is marked PAID but has no recorded payment ID. " +
                                "Payment requires manual reconciliation."
                );
            }

            if (!recordedPaymentId.equals(razorpayPaymentId)) {
                throw new IllegalStateException(
                        "A different payment is already recorded for this order. " +
                                "The additional captured payment requires refund " +
                                "and manual reconciliation."
                );
            }

            return new ConfirmationResult(order, false);
        }

        // Checkout must still own an active stock reservation.
        if (!order.isStockReserved()) {
            throw new IllegalStateException(
                    "Stock reservation is no longer active. " +
                            "Payment requires manual review."
            );
        }

        order.setRazorpayPaymentId(razorpayPaymentId);
        order.setPaymentStatus("PAID");

        if ("PENDING".equalsIgnoreCase(order.getOrderStatus())) {
            order.setOrderStatus("CONFIRMED");
        }

        if (order.getTrackingToken() == null ||
                order.getTrackingToken().isBlank()) {
            order.setTrackingToken(
                    UUID.randomUUID().toString().replace("-", "")
            );
        }

        // Existing checkout code has already deducted legacy product stock.
        // This flag records that the reservation is consumed; do not deduct twice.
        order.setStockReserved(false);

        Order savedOrder = orderRepository.save(order);

        return new ConfirmationResult(savedOrder, true);
    }

    public record ConfirmationResult(
            Order order,
            boolean newlyConfirmed
    ) {}
}
