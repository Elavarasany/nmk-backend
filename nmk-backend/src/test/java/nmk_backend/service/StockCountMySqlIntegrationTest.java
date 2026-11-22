package nmk_backend.service;

import nmk_backend.dto.StockCountLineRequest;
import nmk_backend.dto.StockCountStartRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class StockCountMySqlIntegrationTest {

    @Autowired private StockCountService stockCounts;
    @Autowired private InventoryService inventory;
    @Autowired private AdminRepository admins;
    @Autowired private InventoryLocationRepository locations;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private InventoryBalanceRepository balances;
    @Autowired private StockMovementRepository movements;

    @Test
    void postingAndRetryingStockCountAppliesEachVarianceExactlyOnceOnMySql() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String counter = "mysql-count-counter-" + suffix;
        String reviewer = "mysql-count-reviewer-" + suffix;

        Admin counterAccount = new Admin();
        counterAccount.setUsername(counter);
        counterAccount.setPassword("test-only-password");
        admins.saveAndFlush(counterAccount);

        Admin reviewerAccount = new Admin();
        reviewerAccount.setUsername(reviewer);
        reviewerAccount.setPassword("test-only-password");
        admins.saveAndFlush(reviewerAccount);

        InventoryLocation location = new InventoryLocation();
        location.setCode("SC-" + suffix);
        location.setName("MySQL Stock Count " + suffix);
        location.setActive(true);
        location = locations.saveAndFlush(location);

        Product product = new Product();
        product.setName("MySQL Count Frame " + suffix);
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product.setActive(true);
        product = products.saveAndFlush(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("SC-" + suffix);
        variant.setVariantName("Count Test Variant");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setActive(true);
        variant = variants.saveAndFlush(variant);

        inventory.receiveStock(location.getId(), variant.getId(), 4,
                "MYSQL-STOCK-COUNT-OPENING-" + suffix, null, "Stock count integration fixture");

        var started = stockCounts.start(
                new StockCountStartRequest(location.getId(), "MySQL stock count posting integration test"),
                counter
        );
        assertEquals("COUNTING", started.status());
        assertTrue(locations.findById(location.getId()).orElseThrow().isStockCountLocked());

        for (var line : started.lines()) {
            int physicalQuantity = line.variantId().equals(variant.getId())
                    ? 3 : line.snapshotOnHand();
            stockCounts.recordCount(started.id(), line.id(),
                    new StockCountLineRequest(physicalQuantity), counter);
        }

        var submitted = stockCounts.submit(started.id(), counter);
        assertEquals("READY_FOR_REVIEW", submitted.status());
        assertThrows(IllegalArgumentException.class, () -> stockCounts.post(started.id(), counter),
                "The counter must not approve their own physical count");

        var posted = stockCounts.post(started.id(), reviewer);
        assertEquals("POSTED", posted.status());
        assertEquals(reviewer, posted.postedByUsername());
        assertFalse(locations.findById(location.getId()).orElseThrow().isStockCountLocked());

        InventoryBalance finalBalance = balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow();
        assertEquals(3, finalBalance.getOnHandQuantity());
        String varianceKey = "STOCK-COUNT:" + started.id() + ":" + variant.getId();
        assertTrue(movements.existsByIdempotencyKey(varianceKey));
        long movementsAfterFirstPost = movements.count();

        var replay = stockCounts.post(started.id(), reviewer);
        assertEquals("POSTED", replay.status());
        assertEquals(movementsAfterFirstPost, movements.count(),
                "A repeated post must not create additional stock movements");
        assertEquals(3, balances.findByLocationIdAndVariantId(location.getId(), variant.getId())
                .orElseThrow().getOnHandQuantity());
    }
}
