package nmk_backend.service;

import nmk_backend.dto.StalePaymentCreationResponse;
import nmk_backend.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class StalePaymentCreationReviewService {

    private final OrderRepository orderRepository;

    public StalePaymentCreationReviewService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Page<StalePaymentCreationResponse> getStalePaymentCreations(
            int page, int size, int olderThanMinutes
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        if (olderThanMinutes < 1 || olderThanMinutes > 10080) {
            throw new IllegalArgumentException("olderThanMinutes must be between 1 and 10080");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(olderThanMinutes);
        return orderRepository
                .findExpiredStalePaymentCreations(
                        java.util.List.of("IN_PROGRESS", "CREATED"),
                        "PENDING",
                        cutoff,
                        now,
                        PageRequest.of(page, size, Sort.by(Sort.Order.asc("orderDate"), Sort.Order.asc("id")))
                )
                .map(order -> StalePaymentCreationResponse.from(order, now));
    }
}
