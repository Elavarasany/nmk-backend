
package nmk_backend.repository;

import nmk_backend.entity.PosSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface PosSaleRepository extends JpaRepository<PosSale, Long> {

    Optional<PosSale> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<PosSale> findByInvoiceNumber(String invoiceNumber);

    List<PosSale> findAllByOrderByCreatedAtDesc();

    List<PosSale> findByLocationIdOrderByCreatedAtDesc(Long locationId);

    List<PosSale> findByCashierUsernameOrderByCreatedAtDesc(String cashierUsername);

    Page<PosSale> findByLocationIdOrderByCreatedAtDesc(Long locationId, Pageable pageable);
}
