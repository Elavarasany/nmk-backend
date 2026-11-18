
package nmk_backend.repository;

import nmk_backend.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity - :quantity,
            p.version = p.version + 1
        WHERE p.id = :productId
          AND p.active = true
          AND p.stockQuantity >= :quantity
    """)
    int reserveStock(
            @Param("productId") Long productId,
            @Param("quantity") Integer quantity
    );

    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity + :quantity,
            p.version = p.version + 1
        WHERE p.id = :productId
    """)
    int releaseStock(
            @Param("productId") Long productId,
            @Param("quantity") Integer quantity
    );
}
