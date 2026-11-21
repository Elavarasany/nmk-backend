package nmk_backend.repository;

import nmk_backend.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    boolean existsByNameIgnoreCase(String name);
    List<Supplier> findAllByOrderByNameAsc();
    Optional<Supplier> findByNameIgnoreCase(String name);
}