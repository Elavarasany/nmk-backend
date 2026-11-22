package nmk_backend.repository;

import nmk_backend.entity.RazorpaySettlementEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RazorpaySettlementEntryRepository extends JpaRepository<RazorpaySettlementEntry, Long> {
    @Query("SELECT entry FROM RazorpaySettlementEntry entry WHERE entry.settlement.id = :settlementId ORDER BY entry.providerCreatedAt DESC, entry.id DESC")
    Page<RazorpaySettlementEntry> findBySettlementId(
            @Param("settlementId") Long settlementId, Pageable pageable);
}
