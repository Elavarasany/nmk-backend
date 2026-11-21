package nmk_backend.repository;

import nmk_backend.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByUsername(String username);
    Optional<Admin> findByUsernameIgnoreCase(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    long countByRoleAndEnabledTrue(String role);

    // Serialize role/status changes so two concurrent requests cannot remove
    // the final active administrator at the same time.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a order by a.id")
    List<Admin> findAllForUpdate();
}