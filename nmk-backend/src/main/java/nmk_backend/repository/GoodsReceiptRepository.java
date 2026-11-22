package nmk_backend.repository;

import nmk_backend.entity.GoodsReceipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {
    Optional<GoodsReceipt> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<GoodsReceipt> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);

    @Query("""
            select receipt.id
            from GoodsReceipt receipt
            where receipt.purchaseOrder.location.id = :locationId
              and receipt.createdAt >= :from
              and receipt.createdAt < :to
            order by receipt.createdAt asc, receipt.id asc
            """)
    Page<Long> findReportIds(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {
            "items", "items.purchaseOrderItem", "items.purchaseOrderItem.variant",
            "purchaseOrder", "purchaseOrder.supplier"
    })
    List<GoodsReceipt> findAllByIdIn(List<Long> ids);
}
