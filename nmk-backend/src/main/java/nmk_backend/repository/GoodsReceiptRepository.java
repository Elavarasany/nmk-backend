package nmk_backend.repository;

import nmk_backend.entity.GoodsReceipt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {
    Optional<GoodsReceipt> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<GoodsReceipt> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<GoodsReceipt> findByPurchaseOrder_Location_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long locationId, java.time.LocalDateTime from, java.time.LocalDateTime toExclusive
    );
}
