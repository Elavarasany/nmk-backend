
package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.exception.*;
import nmk_backend.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryLocationRepository locationRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository variantRepository;

    @Mock
    private InventoryBalanceRepository balanceRepository;

    @Mock
    private StockMovementRepository movementRepository;

    @InjectMocks
    private InventoryService inventoryService;

    private InventoryLocation location;
    private Product product;
    private ProductVariant variant;
    private InventoryBalance balance;

    @BeforeEach
    void setUp() {
        location = new InventoryLocation();
        location.setId(1L);
        location.setCode("MAIN");
        location.setName("Main Store");
        location.setActive(true);

        product = new Product();
        product.setId(3L);
        product.setActive(true);

        variant = new ProductVariant();
        variant.setId(2L);
        variant.setSku("TEST-SKU");
        variant.setVariantName("Standard");
        variant.setActive(true);
        variant.setProduct(product);

        balance = new InventoryBalance();
        balance.setLocation(location);
        balance.setVariant(variant);
        balance.setOnHandQuantity(10);
        balance.setReservedQuantity(2);
        balance.setReorderLevel(1);
    }

    private void stubActiveProductAndVariant() {
        when(variantRepository.findProductIdByVariantId(2L))
                .thenReturn(Optional.of(3L));

        when(productRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.of(product));

        when(variantRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(variant));
    }

    private void stubLocation() {
        when(locationRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(location));
    }

    private void stubNewMovement() {
        when(movementRepository.findByIdempotencyKey(any()))
                .thenReturn(Optional.empty());
    }

    private void stubExistingBalance() {
        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.of(balance));
    }

    @Test
    void receiveStockIncreasesOnHandQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.receiveStock(
                1L, 2L, 5, "RECEIVE-001", 10L, "New stock"
        );

        assertEquals(15, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(13, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("RECEIPT", captor.getValue().getMovementType());
        assertEquals(5, captor.getValue().getQuantityDelta());
        assertEquals(0, captor.getValue().getReservedDelta());
        assertEquals(15, captor.getValue().getOnHandAfter());
    }

    @Test
    void adminReceiptRecordsAdminActorAndLeavesStaffActorNull() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        inventoryService.receiveStockByAdmin(
                1L, 2L, 5, "ADMIN-RECEIVE-001", 99L, "Admin stock receipt"
        );

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());

        assertNull(captor.getValue().getActorStaffId());
        assertEquals(99L, captor.getValue().getActorAdminId());
    }

    @Test
    void adminSaleRecordsAdminActorAndLeavesStaffActorNull() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        inventoryService.sellStockByAdmin(
                1L, 2L, 1, "ADMIN-SALE-001", 99L,
                "Admin POS sale", "POS_SALE", 100L
        );

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());

        assertNull(captor.getValue().getActorStaffId());
        assertEquals(99L, captor.getValue().getActorAdminId());
    }

    @Test
    void receiveStockByAdminRecordsAdminActorWithoutStaffActor() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        inventoryService.receiveStockByAdmin(
                1L, 2L, 4, "ADMIN-RECEIVE-001", 42L, "Admin stock receipt"
        );

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals(42L, captor.getValue().getActorAdminId());
        assertNull(captor.getValue().getActorStaffId());
    }

    @Test
    void reserveStockIncreasesReservedWithoutReducingOnHand() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.reserveStock(
                1L, 2L, 3, "RESERVE-001", null, "Online order"
        );

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(5, result.getReservedQuantity());
        assertEquals(5, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("RESERVATION", captor.getValue().getMovementType());
        assertEquals(0, captor.getValue().getQuantityDelta());
        assertEquals(3, captor.getValue().getReservedDelta());
    }

    @Test
    void releaseReservationReducesOnlyReservedQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.releaseReservation(
                1L, 2L, 2, "RELEASE-001", null, "Order cancelled"
        );

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(3, result.getReservedQuantity());
        assertEquals(7, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("RELEASE", captor.getValue().getMovementType());
        assertEquals(0, captor.getValue().getQuantityDelta());
        assertEquals(-2, captor.getValue().getReservedDelta());
    }

    @Test
    void consumeReservationReducesBothQuantities() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.consumeReservation(
                1L, 2L, 3, "CONSUME-001", null, "Payment confirmed"
        );

        assertEquals(7, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(5, result.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals("CONSUMPTION", captor.getValue().getMovementType());
        assertEquals(-3, captor.getValue().getQuantityDelta());
        assertEquals(-3, captor.getValue().getReservedDelta());
    }

    @Test
    void reserveStockRejectsInsufficientAvailableQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setOnHandQuantity(5);
        balance.setReservedQuantity(4);
        stubExistingBalance();

        assertThrows(
                InsufficientStockException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 2, "RESERVE-002", null, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void releaseReservationRejectsInsufficientReservedQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setReservedQuantity(1);
        stubExistingBalance();

        assertThrows(
                InsufficientStockException.class,
                () -> inventoryService.releaseReservation(
                        1L, 2L, 2, "RELEASE-002", null, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockReducesOnHandButPreservesReservedQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        InventoryBalance result = inventoryService.sellStock(
                1L, 2L, 5, "POS-SALE-001", 10L,
                "POS sale", "POS_SALE", 100L
        );

        assertSame(balance, result);
        assertEquals(5, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());
        assertEquals(3, balance.getAvailableQuantity());

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        StockMovement movement = captor.getValue();

        assertEquals("SALE", movement.getMovementType());
        assertEquals(-5, movement.getQuantityDelta());
        assertEquals(0, movement.getReservedDelta());
        assertEquals("POS-SALE-001", movement.getIdempotencyKey());
        assertEquals("POS_SALE", movement.getReferenceType());
        assertEquals(100L, movement.getReferenceId());
    }

    @Test
    void sellStockByAdminRecordsAdminActorWithoutStaffActor() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        inventoryService.sellStockByAdmin(
                1L, 2L, 2, "ADMIN-SALE-001", 42L,
                "Admin POS sale", "POS_SALE", 123L
        );

        ArgumentCaptor<StockMovement> captor =
                ArgumentCaptor.forClass(StockMovement.class);

        verify(movementRepository).saveAndFlush(captor.capture());

        assertEquals(42L, captor.getValue().getActorAdminId());
        assertNull(captor.getValue().getActorStaffId());
        assertEquals("SALE", captor.getValue().getMovementType());
    }

    @Test
    void sellStockRejectsInsufficientAvailableQuantity() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();
        stubExistingBalance();

        assertThrows(
                InsufficientStockException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 9, "POS-SALE-002", 10L,
                        "POS sale", null, null
                )
        );

        assertEquals(10, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsMissingBalance() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.empty());

        assertThrows(
                InventoryNotFoundException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-003", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsInactiveProduct() {
        product.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-004", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void identicalSaleRetryDoesNotDeductStockTwice() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("SALE");
        existing.setQuantityDelta(-3);
        existing.setReservedDelta(0);

        when(movementRepository.findByIdempotencyKey("POS-SALE-RETRY"))
                .thenReturn(Optional.of(existing));

        when(balanceRepository.findByLocationIdAndVariantId(1L, 2L))
                .thenReturn(Optional.of(balance));

        InventoryBalance result = inventoryService.sellStock(
                1L, 2L, 3, "POS-SALE-RETRY", 10L,
                "Retry POS sale", null, null
        );

        assertSame(balance, result);
        assertEquals(10, balance.getOnHandQuantity());
        assertEquals(2, balance.getReservedQuantity());

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void repeatedIdenticalOperationDoesNotCreateAnotherMovement() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RESERVATION");
        existing.setQuantityDelta(0);
        existing.setReservedDelta(3);

        when(movementRepository.findByIdempotencyKey("RESERVE-RETRY"))
                .thenReturn(Optional.of(existing));

        when(balanceRepository.findByLocationIdAndVariantId(1L, 2L))
                .thenReturn(Optional.of(balance));

        InventoryBalance result = inventoryService.reserveStock(
                1L, 2L, 3, "RESERVE-RETRY", null, "Retry"
        );

        assertSame(balance, result);

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void reusingIdempotencyKeyForDifferentOperationIsRejected() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RECEIPT");
        existing.setQuantityDelta(5);
        existing.setReservedDelta(0);

        when(movementRepository.findByIdempotencyKey("REUSED-KEY"))
                .thenReturn(Optional.of(existing));

        assertThrows(
                InventoryConflictException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 3, "REUSED-KEY", null, "Different operation"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void zeroQuantityIsRejected() {
        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 0, "ZERO-001", null, "Invalid"
                )
        );

        verifyNoInteractions(
                locationRepository,
                productRepository,
                variantRepository,
                balanceRepository,
                movementRepository
        );
    }

    @Test
    void reservationRejectsMissingBalance() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        when(balanceRepository.findByLocationAndVariantForUpdate(1L, 2L))
                .thenReturn(Optional.empty());

        assertThrows(
                InventoryNotFoundException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 1, "MISSING-BALANCE", null, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void receivingStockThatExceedsIntegerLimitIsRejected() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        balance.setOnHandQuantity(Integer.MAX_VALUE);
        balance.setReservedQuantity(0);
        stubExistingBalance();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 1, "OVERFLOW-001", null, "Overflow test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void inventoryOperationRejectsInactiveParentProduct() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        product.setActive(false);

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "INACTIVE-PRODUCT-001", 10L, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void inventoryOperationRejectsInactiveVariant() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        variant.setActive(false);

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "INACTIVE-VARIANT-001", 10L, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void inventoryOperationRejectsInactiveLocation() {
        location.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "INACTIVE-LOCATION-001", 10L, "Test"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void missingParentProductIsRejected() {
        stubLocation();

        when(variantRepository.findProductIdByVariantId(2L))
                .thenReturn(Optional.of(3L));

        when(productRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.empty());

        assertThrows(
                InventoryNotFoundException.class,
                () -> inventoryService.receiveStock(
                        1L, 2L, 5,
                        "MISSING-PRODUCT-001", 10L, "Test"
                )
        );

        verify(variantRepository, never()).findByIdForUpdate(2L);
        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }


    @Test
    void releaseReservationWorksAfterParentProductIsDeactivated() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        product.setActive(false);

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.releaseReservation(
                1L, 2L, 2,
                "RELEASE-INACTIVE-PRODUCT-001",
                null, "Release after product deactivation"
        );

        assertEquals(10, result.getOnHandQuantity());
        assertEquals(3, result.getReservedQuantity());
        assertEquals(7, result.getAvailableQuantity());

        verify(balanceRepository).saveAndFlush(balance);
        verify(movementRepository).saveAndFlush(any(StockMovement.class));
    }

    @Test
    void consumeReservationWorksAfterVariantIsDeactivated() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        variant.setActive(false);

        balance.setReservedQuantity(5);
        stubExistingBalance();

        InventoryBalance result = inventoryService.consumeReservation(
                1L, 2L, 3,
                "CONSUME-INACTIVE-VARIANT-001",
                null, "Payment confirmed after variant deactivation"
        );

        assertEquals(7, result.getOnHandQuantity());
        assertEquals(2, result.getReservedQuantity());
        assertEquals(5, result.getAvailableQuantity());

        verify(balanceRepository).saveAndFlush(balance);
        verify(movementRepository).saveAndFlush(any(StockMovement.class));
    }

    @Test
    void identicalReservationRetryWorksAfterRecordsAreDeactivated() {
        stubLocation();
        stubActiveProductAndVariant();

        location.setActive(false);
        product.setActive(false);
        variant.setActive(false);

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("RESERVATION");
        existing.setQuantityDelta(0);
        existing.setReservedDelta(3);

        when(movementRepository.findByIdempotencyKey("RESERVE-INACTIVE-RETRY"))
                .thenReturn(Optional.of(existing));

        when(balanceRepository.findByLocationIdAndVariantId(1L, 2L))
                .thenReturn(Optional.of(balance));

        InventoryBalance result = inventoryService.reserveStock(
                1L, 2L, 3,
                "RESERVE-INACTIVE-RETRY",
                null, "Retry existing reservation"
        );

        assertSame(balance, result);

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void newReservationIsRejectedWhenParentProductIsInactive() {
        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        product.setActive(false);

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.reserveStock(
                        1L, 2L, 2,
                        "RESERVE-INACTIVE-PRODUCT-001",
                        null, "New reservation"
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }
    @Test
    void sellStockRejectsInactiveVariant() {
        variant.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-005", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsInactiveLocation() {
        location.setActive(false);

        stubLocation();
        stubActiveProductAndVariant();
        stubNewMovement();

        assertThrows(
                InvalidInventoryOperationException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 1, "POS-SALE-006", 10L,
                        "POS sale", null, null
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void sellStockRejectsReusedIdempotencyKeyForDifferentOperation() {
        stubLocation();
        stubActiveProductAndVariant();

        StockMovement existing = new StockMovement();
        existing.setLocation(location);
        existing.setVariant(variant);
        existing.setMovementType("SALE");
        existing.setQuantityDelta(-2);
        existing.setReservedDelta(0);
        existing.setReferenceType("POS_SALE");
        existing.setReferenceId(100L);

        when(movementRepository.findByIdempotencyKey("POS-SALE-CONFLICT"))
                .thenReturn(Optional.of(existing));

        assertThrows(
                InventoryConflictException.class,
                () -> inventoryService.sellStock(
                        1L, 2L, 3, "POS-SALE-CONFLICT", 10L,
                        "Different sale", "POS_SALE", 101L
                )
        );

        verify(balanceRepository, never()).saveAndFlush(any());
        verify(movementRepository, never()).saveAndFlush(any());
    }

}
