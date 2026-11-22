package nmk_backend.service;

import nmk_backend.entity.Order;
import nmk_backend.entity.OrderRefund;
import nmk_backend.repository.OrderRefundRepository;
import nmk_backend.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class RazorpayRefundReconciliationService {
    private static final Pattern REFUND_ID = Pattern.compile("rfnd_[A-Za-z0-9]+");
    private static final Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]+");

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;

    public RazorpayRefundReconciliationService(
            OrderRepository orderRepository,
            OrderRefundRepository refundRepository
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reconcile(
            String providerEventId,
            String eventType,
            String refundId,
            String paymentId,
            long amountPaise,
            String currency,
            String providerStatus
    ) {
        if (providerEventId == null || providerEventId.isBlank()
                || eventType == null
                || refundId == null || !REFUND_ID.matcher(refundId).matches()
                || paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()
                || amountPaise <= 0
                || !"INR".equalsIgnoreCase(currency)) {
            throw new IllegalArgumentException("Razorpay refund payload is incomplete or invalid");
        }

        String targetStatus = expectedStatus(eventType, providerStatus);
        Order order = orderRepository.findByRazorpayPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No paid local order matches the Razorpay refund payment"
                ));

        if (!"PAID".equalsIgnoreCase(order.getPaymentStatus())
                && !"REFUNDED".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new IllegalArgumentException(
                    "Refund event references an order that is not recorded as paid"
            );
        }

        long orderAmountPaise;
        try {
            orderAmountPaise = order.getTotalAmount()
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (Exception invalidAmount) {
            throw new IllegalArgumentException("Local order amount cannot be verified");
        }

        Optional<OrderRefund> existing = refundRepository.findByRazorpayRefundIdForUpdate(refundId);
        OrderRefund refund;
        if (existing.isPresent()) {
            refund = existing.get();
            if (!refund.getOrder().getId().equals(order.getId())
                    || !paymentId.equals(refund.getRazorpayPaymentId())
                    || refund.getAmountPaise() != amountPaise
                    || !"INR".equalsIgnoreCase(refund.getCurrency())) {
                throw new IllegalArgumentException(
                        "Razorpay refund ID conflicts with its previously recorded order or amount"
                );
            }

            String currentStatus = refund.getStatus();
            if ("PROCESSED".equals(currentStatus)) {
                if ("PROCESSED".equals(targetStatus) || "PENDING".equals(targetStatus)) {
                    return;
                }
                throw new IllegalArgumentException("A processed refund cannot be changed to failed");
            }
            if ("FAILED".equals(currentStatus)) {
                if ("FAILED".equals(targetStatus) || "PENDING".equals(targetStatus)) {
                    return;
                }
                throw new IllegalArgumentException("A failed refund cannot later be marked processed");
            }

            refund.setStatus(targetStatus);
            refund.setLastProviderEventId(providerEventId);
        } else {
            refund = new OrderRefund();
            refund.setOrder(order);
            refund.setRazorpayRefundId(refundId);
            refund.setRazorpayPaymentId(paymentId);
            refund.setAmountPaise(amountPaise);
            refund.setCurrency("INR");
            refund.setStatus(targetStatus);
            refund.setLastProviderEventId(providerEventId);
        }

        refundRepository.saveAndFlush(refund);

        long activeRefundTotal = Optional.ofNullable(
                refundRepository.sumActiveAmountPaiseByOrderId(order.getId())
        ).orElse(0L);
        if (activeRefundTotal > orderAmountPaise) {
            throw new IllegalArgumentException(
                    "Active Razorpay refunds exceed the original captured order amount"
            );
        }

        long processedRefundTotal = Optional.ofNullable(
                refundRepository.sumProcessedAmountPaiseByOrderId(order.getId())
        ).orElse(0L);
        if (processedRefundTotal > orderAmountPaise) {
            throw new IllegalArgumentException(
                    "Processed Razorpay refunds exceed the original captured order amount"
            );
        }

        if (processedRefundTotal == orderAmountPaise) {
            order.setRefundStatus("REFUNDED");
            order.setPaymentStatus("REFUNDED");
            if ("PENDING".equalsIgnoreCase(order.getOrderStatus())
                    || "CONFIRMED".equalsIgnoreCase(order.getOrderStatus())) {
                order.setOrderStatus("CANCELLED");
            }
        } else if (processedRefundTotal > 0) {
            order.setRefundStatus("PARTIALLY_REFUNDED");
            order.setPaymentStatus("PAID");
        } else if (activeRefundTotal > 0) {
            order.setRefundStatus("PENDING");
            order.setPaymentStatus("PAID");
        } else {
            order.setRefundStatus("FAILED");
            order.setPaymentStatus("PAID");
        }

        orderRepository.save(order);
    }

    private String expectedStatus(String eventType, String providerStatus) {
        String normalizedType = eventType.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = providerStatus == null
                ? "" : providerStatus.trim().toLowerCase(Locale.ROOT);

        return switch (normalizedType) {
            case "refund.created" -> {
                if (!"pending".equals(normalizedStatus) && !"created".equals(normalizedStatus)) {
                    throw new IllegalArgumentException("Razorpay refund.created status is not pending");
                }
                yield "PENDING";
            }
            case "refund.processed" -> {
                if (!"processed".equals(normalizedStatus)) {
                    throw new IllegalArgumentException("Razorpay refund.processed status is not processed");
                }
                yield "PROCESSED";
            }
            case "refund.failed" -> {
                if (!"failed".equals(normalizedStatus)) {
                    throw new IllegalArgumentException("Razorpay refund.failed status is not failed");
                }
                yield "FAILED";
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported Razorpay refund event requires manual review"
            );
        };
    }
}
