package nmk_backend.repository;

import nmk_backend.entity.SupplierReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {
    Optional<SupplierReturn> findByIdempotencyKey(String idempotencyKey);
    List<SupplierReturn> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);
    List<SupplierReturn> findByPurchaseOrder_Location_IdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long locationId, java.time.LocalDateTime from, java.time.LocalDateTime toExclusive
    );
}