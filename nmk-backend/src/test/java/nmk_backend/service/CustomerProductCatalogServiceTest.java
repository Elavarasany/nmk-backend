package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerProductCatalogServiceTest {

    private ProductRepository productRepository;
    private ProductVariantRepository variantRepository;
    private InventoryLocationRepository locationRepository;
    private InventoryBalanceRepository balanceRepository;
    private CustomerProductCatalogService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        locationRepository = mock(InventoryLocationRepository.class);
        balanceRepository = mock(InventoryBalanceRepository.class);

        service = new CustomerProductCatalogService(
                productRepository,
                variantRepository,
                locationRepository,
                balanceRepository,
                "AVP-01"
        );
    }

    @Test
    void returnsActiveVariantsWithRealIdsAndLocationAvailableStock() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setAttributes(Map.of("color", "Black"));
        variant.setSellingPriceOverride(new BigDecimal("125.00"));
        variant.setActive(true);

        InventoryBalance balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(8);
        balance.setReservedQuantity(3);

        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of(balance));
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(productRepository.findAll()).thenReturn(List.of(product));

        var result = service.getActiveProducts();

        assertEquals(1, result.size());
        var item = result.get(0);
        assertEquals(10L, item.id());
        assertEquals(new BigDecimal("125.00"), item.price());
        assertEquals(5, item.stockQuantity());
        assertEquals(1, item.variants().size());
        assertEquals(25L, item.variants().get(0).id());
        assertEquals(new BigDecimal("125.00"), item.variants().get(0).price());
        assertEquals(5, item.variants().get(0).availableQuantity());
        assertEquals("Black", item.variants().get(0).attributes().get("color"));
    }

    @Test
    void returnsZeroAvailableWhenVariantHasNoLocationBalance() {
        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setCode("AVP-01");
        location.setActive(true);

        Product product = new Product();
        product.setId(10L);
        product.setName("Classic Frame");
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(true);

        ProductVariant variant = new ProductVariant();
        variant.setId(25L);
        variant.setProduct(product);
        variant.setVariantName("Black");
        variant.setActive(true);

        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.of(location));
        when(balanceRepository.findByLocationId(1L)).thenReturn(List.of());
        when(variantRepository.findAll()).thenReturn(List.of(variant));
        when(productRepository.findAll()).thenReturn(List.of(product));

        var result = service.getActiveProducts();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).stockQuantity());
        assertEquals(0, result.get(0).variants().get(0).availableQuantity());
    }

    @Test
    void rejectsMissingCheckoutLocationConfiguration() {
        CustomerProductCatalogService unconfigured = new CustomerProductCatalogService(
                productRepository,
                variantRepository,
                locationRepository,
                balanceRepository,
                " "
        );

        assertThrows(ResponseStatusException.class, unconfigured::getActiveProducts);
        verifyNoInteractions(locationRepository, balanceRepository, variantRepository, productRepository);
    }

    @Test
    void rejectsMissingOrInactiveCheckoutLocation() {
        when(locationRepository.findByCode("AVP-01")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, service::getActiveProducts);
        verifyNoInteractions(balanceRepository, variantRepository, productRepository);
    }
}
