package nmk_backend.repository;

import nmk_backend.entity.SupplierBillLandedCostAllocation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

public interface SupplierBillLandedCostAllocationRepository extends JpaRepository<SupplierBillLandedCostAllocation, Long> {
    @EntityGraph(attributePaths = {"goodsReceiptItem", "goodsReceiptItem.purchaseOrderItem",
            "goodsReceiptItem.purchaseOrderItem.variant"})
    List<SupplierBillLandedCostAllocation> findByBillIdOrderByIdAsc(Long billId);

    BigDecimal sumAllocatedAmountByBillId(Long billId);
}
