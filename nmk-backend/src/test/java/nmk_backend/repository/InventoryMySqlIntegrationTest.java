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
    private ProductService productService;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductVariantRepository variantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Test
    void deletingProductDeactivatesItAndPreservesInventoryAndMovementHistory() {
        Product product = new Product();
        product.setName("Soft Delete Integration Product");
        product.setPrice(new BigDecimal("1499.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("SOFT-DELETE-" + java.util.UUID.randomUUID());
        variant.setVariantName("Preserved Variant");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variantRepository.saveAndFlush(variant);

        InventoryLocation location = new InventoryLocation();
        location.setCode("SD-" + java.util.UUID.randomUUID());
        location.setName("Soft Delete Test Location");
        location.setActive(true);
        location = entityManager.persist(location);
        entityManager.flush();

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(4);
        balance.setReservedQuantity(0);
        balance.setReorderLevel(0);
        balanceRepository.saveAndFlush(balance);

        ProductVariant savedVariant = variant;
        inventoryService.receiveStock(
                location.getId(), savedVariant.getId(), 0,
                "SOFT-DELETE-TEST-" + java.util.UUID.randomUUID(),
                null, "Audit movement before product deactivation"
        );

        productService.deleteProduct(product.getId());
        entityManager.flush();
        entityManager.clear();

        Product persistedProduct = productRepository.findById(product.getId()).orElseThrow();
        ProductVariant persistedVariant = variantRepository.findById(savedVariant.getId()).orElseThrow();
        InventoryBalance persistedBalance = balanceRepository
                .findByLocationIdAndVariantId(location.getId(), savedVariant.getId())
                .orElseThrow();

        assertFalse(persistedProduct.isActive());
        assertTrue(persistedVariant.isActive());
        assertEquals(4, persistedBalance.getOnHandQuantity());
        assertTrue(movementRepository.findByLocationIdAndVariantIdOrderByCreatedAtDesc(
                location.getId(), savedVariant.getId()
        ).size() >= 1);
    }

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