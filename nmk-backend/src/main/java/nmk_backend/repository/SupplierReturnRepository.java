package nmk_backend.repository;

import nmk_backend.entity.SupplierReturn;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {
    Optional<SupplierReturn> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<SupplierReturn> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<SupplierReturn> findByPurchaseOrder_Location_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long locationId, java.time.LocalDateTime from, java.time.LocalDateTime toExclusive
    );
}
