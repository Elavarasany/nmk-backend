package nmk_backend.repository;

import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
@Transactional
class InventoryMySqlIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository variantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void mysqlPersistsVariantJsonAttributesAndSellingPriceOverride() {
        Product product = new Product();
        product.setName("MySQL Integration Test Product");
        product.setPrice(new BigDecimal("999.00"));
        product.setStockQuantity(0);
        product.setActive(true);

        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("MYSQL-JSON-SKU-001");
        variant.setVariantName("Black Rectangle Frame");
        variant.setAttributes(Map.of(
                "color", "Black",
                "shape", "Rectangle",
                "material", "TR90"
        ));
        variant.setSellingPriceOverride(new BigDecimal("1199.00"));
        variant.setActive(true);

        variantRepository.saveAndFlush(variant);

        entityManager.clear();

        ProductVariant result = variantRepository
                .findBySku("MYSQL-JSON-SKU-001")
                .orElseThrow();

        assertEquals("Black Rectangle Frame", result.getVariantName());
        assertEquals("Black", result.getAttributes().get("color"));
        assertEquals("Rectangle", result.getAttributes().get("shape"));
        assertEquals("TR90", result.getAttributes().get("material"));
        assertEquals(
                new BigDecimal("1199.00"),
                result.getSellingPriceOverride()
        );
    }
}