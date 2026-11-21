package nmk_backend.repository;

import nmk_backend.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByUsername(String username);
    Optional<Admin> findByUsernameIgnoreCase(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    long countByRoleAndEnabledTrue(String role);
}