package nmk_backend.repository;

import nmk_backend.entity.PosExchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PosExchangeRepository extends JpaRepository<PosExchange, Long> {
    Optional<PosExchange> findByIdempotencyKey(String idempotencyKey);
}
