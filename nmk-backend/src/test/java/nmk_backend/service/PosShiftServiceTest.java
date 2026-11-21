package nmk_backend.service;

import nmk_backend.dto.PosCashMovementRequest;
import nmk_backend.dto.PosShiftCloseRequest;
import nmk_backend.entity.*;
import nmk_backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PosShiftServiceTest {

    private InventoryLocationRepository locationRepository;
    private PosRegisterRepository registerRepository;
    private PosShiftRepository shiftRepository;
    private PosCashMovementRepository movementRepository;
    private PosShiftService service;
    private PosShift shift;

    @BeforeEach
    void setUp() {
        locationRepository = mock(InventoryLocationRepository.class);
        registerRepository = mock(PosRegisterRepository.class);
        shiftRepository = mock(PosShiftRepository.class);
        movementRepository = mock(PosCashMovementRepository.class);
        service = new PosShiftService(
                locationRepository, registerRepository, shiftRepository, movementRepository
        );

        InventoryLocation location = new InventoryLocation();
        location.setId(1L);
        location.setActive(true);

        PosRegister register = new PosRegister();
        ReflectionTestUtils.setField(register, "id", 3L);
        register.setLocation(location);
        register.setRegisterCode("MAIN");
        register.setActive(true);

        shift = new PosShift();
        ReflectionTestUtils.setField(shift, "id", 9L);
        shift.setRegister(register);
        shift.setOpenedByUsername("cashier.one");
        shift.setOpeningFloat(new BigDecimal("100.00"));
        shift.setStatus("OPEN");
        shift.setIdempotencyKey("SHIFT-OPEN-1");
    }

    @Test
    void concurrentShiftOpenRetryReplaysAfterRegisterLock() {
        shift.setIdempotencyKey("SHIFT-OPEN-RACE");
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("100.00"));
        when(shiftRepository.findByIdempotencyKey("SHIFT-OPEN-RACE"))
                .thenReturn(Optional.empty(), Optional.of(shift));
        when(registerRepository.findByIdForUpdate(3L))
                .thenReturn(Optional.of(shift.getRegister()));

        var response = service.openShift(
                new nmk_backend.dto.PosShiftOpenRequest(
                        3L, new BigDecimal("100.00"), "SHIFT-OPEN-RACE"
                ),
                "cashier.one"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("100.00"), response.expectedCash());
        verify(shiftRepository, times(2)).findByIdempotencyKey("SHIFT-OPEN-RACE");
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
    }

    @Test
    void anotherManagerCannotReplayACashMovementCreatedByDifferentStaff() {
        PosCashMovement existing = movement("CASH_OUT", "OUT", "25.00", shift);
        existing.setIdempotencyKey("CASH-OUT-3");
        existing.setReason("Store supplies");
        when(movementRepository.findByIdempotencyKey("CASH-OUT-3")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.recordCashMovement(
                9L,
                new PosCashMovementRequest("CASH_OUT", new BigDecimal("25.00"),
                        "CASH-OUT-3", "Store supplies"),
                "manager.one",
                "MANAGER"
        ));
        verify(shiftRepository, never()).findByIdForUpdate(anyLong());
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void anotherStaffMemberCannotReplayCashRefundMovement() {
        PosCashMovement existing = movement("CASH_REFUND", "OUT", "25.00", shift);
        existing.setIdempotencyKey("POS-RETURN-CASH:return-key:0");
        existing.setReferenceId(77L);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("POS-RETURN-CASH:return-key:0"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.recordCashRefund(
                shift, new BigDecimal("25.00"), "POS-RETURN-CASH:return-key:0",
                "manager.two", 77L
        ));
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void concurrentCashMovementRetryReplaysAfterTheShiftHasClosed() {
        PosCashMovement existing = movement("CASH_OUT", "OUT", "25.00", shift);
        existing.setIdempotencyKey("CASH-OUT-RACE");
        existing.setReason("Store supplies");
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("75.00"));

        when(movementRepository.findByIdempotencyKey("CASH-OUT-RACE"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        var response = service.recordCashMovement(
                9L,
                new PosCashMovementRequest("CASH_OUT", new BigDecimal("25.00"),
                        "CASH-OUT-RACE", "Store supplies"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("75.00"), response.expectedCash());
        verify(movementRepository, times(2)).findByIdempotencyKey("CASH-OUT-RACE");
        verify(movementRepository, never()).saveAndFlush(any(PosCashMovement.class));
    }

    @Test
    void cashOutCannotExceedCurrentExpectedDrawerCash() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("CASH-OUT-1")).thenReturn(Optional.empty());
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening));

        assertThrows(IllegalArgumentException.class, () ->
                service.recordCashMovement(
                        9L,
                        new PosCashMovementRequest("CASH_OUT", new BigDecimal("101.00"),
                                "CASH-OUT-1", "Petty cash"),
                        "cashier.one",
                        "CASHIER"
                )
        );
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void cashOutIsRecordedAsAnOutflowAndReducesExpectedCash() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByIdempotencyKey("CASH-OUT-2")).thenReturn(Optional.empty());
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening, movement("CASH_OUT", "OUT", "25.00", shift)));
        when(movementRepository.saveAndFlush(any(PosCashMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.recordCashMovement(
                9L,
                new PosCashMovementRequest("CASH_OUT", new BigDecimal("25.00"),
                        "CASH-OUT-2", "Store supplies"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals(new BigDecimal("75.00"), response.expectedCash());
        verify(movementRepository).saveAndFlush(argThat(movement ->
                "CASH_OUT".equals(movement.getMovementType())
                        && "OUT".equals(movement.getDirection())
                        && new BigDecimal("25.00").compareTo(movement.getAmount()) == 0
        ));
    }

    @Test
    void cashVarianceRequiresAnExplanationAtShiftClose() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening));

        assertThrows(IllegalArgumentException.class, () ->
                service.closeShift(
                        9L,
                        new PosShiftCloseRequest(new BigDecimal("95.00"), null),
                        "cashier.one",
                        "CASHIER"
                )
        );
        verify(shiftRepository, never()).saveAndFlush(any());
    }

    @Test
    void shiftCloseStoresExpectedCashVarianceAndReason() {
        PosCashMovement opening = movement("OPENING_FLOAT", "IN", "100.00", shift);
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));
        when(movementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(9L))
                .thenReturn(List.of(opening));
        when(shiftRepository.saveAndFlush(any(PosShift.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("95.00"), "Five rupees short; recounted drawer"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("100.00"), response.expectedCash());
        assertEquals(new BigDecimal("-5.00"), response.cashVariance());
        assertEquals("Five rupees short; recounted drawer", response.closingReason());
        assertNotNull(response.closedAt());
    }

    @Test
    void exactShiftCloseRetryReplaysCommittedClosureWithoutChangingTheCount() {
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("100.00"));
        shift.setClosingCashCount(new BigDecimal("95.00"));
        shift.setCashVariance(new BigDecimal("-5.00"));
        shift.setClosingReason("Drawer recounted");
        shift.setClosedByUsername("cashier.one");
        shift.setClosedAt(java.time.LocalDateTime.of(2026, 11, 21, 18, 0));
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        var response = service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("95.00"), "Drawer recounted"),
                "cashier.one",
                "CASHIER"
        );

        assertEquals("CLOSED", response.status());
        assertEquals(new BigDecimal("95.00"), response.closingCashCount());
        assertEquals(new BigDecimal("-5.00"), response.cashVariance());
        assertEquals("Drawer recounted", response.closingReason());
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
        verify(movementRepository, never()).findByShiftIdOrderByCreatedAtAscIdAsc(9L);
    }

    @Test
    void shiftCloseRetryRejectsDifferentClosingReason() {
        shift.setStatus("CLOSED");
        shift.setClosingCashCount(new BigDecimal("95.00"));
        shift.setClosingReason("Drawer recounted");
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        assertThrows(IllegalArgumentException.class, () -> service.closeShift(
                9L,
                new PosShiftCloseRequest(new BigDecimal("95.00"), "Different explanation"),
                "cashier.one",
                "CASHIER"
        ));
        verify(shiftRepository, never()).saveAndFlush(any(PosShift.class));
    }

    @Test
    void managerShiftHistoryIncludesClosedCashReconciliationDetails() {
        shift.setStatus("CLOSED");
        shift.setExpectedCash(new BigDecimal("100.00"));
        shift.setClosingCashCount(new BigDecimal("95.00"));
        shift.setCashVariance(new BigDecimal("-5.00"));
        shift.setClosingReason("Drawer recounted");
        when(shiftRepository.findTop100ByRegisterLocationIdOrderByOpenedAtDesc(1L))
                .thenReturn(List.of(shift));

        var history = service.getShiftHistory(1L);

        assertEquals(1, history.size());
        assertEquals(9L, history.get(0).id());
        assertEquals("CLOSED", history.get(0).status());
        assertEquals(new BigDecimal("100.00"), history.get(0).expectedCash());
        assertEquals(new BigDecimal("95.00"), history.get(0).closingCashCount());
        assertEquals(new BigDecimal("-5.00"), history.get(0).cashVariance());
        assertEquals("Drawer recounted", history.get(0).closingReason());
        verify(shiftRepository).findTop100ByRegisterLocationIdOrderByOpenedAtDesc(1L);
    }

    @Test
    void shiftHistoryRejectsInvalidLocationIds() {
        assertThrows(IllegalArgumentException.class, () -> service.getShiftHistory(0L));
        verifyNoInteractions(shiftRepository);
    }

    @Test
    void managerCanProcessReturnAgainstAnOpenCashierShiftAtSameLocation() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        PosShift result = service.requireOpenShiftForManagerAction(
                9L, 1L, "manager.one", "MANAGER"
        );

        assertSame(shift, result);
    }

    @Test
    void cashierCannotProcessReturnAgainstAnotherCashiersShift() {
        when(shiftRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(shift));

        assertThrows(IllegalArgumentException.class, () ->
                service.requireOpenShiftForManagerAction(
                        9L, 1L, "cashier.two", "CASHIER"
                )
        );
    }

    private PosCashMovement movement(String type, String direction, String amount, PosShift shift) {
        PosCashMovement movement = new PosCashMovement();
        movement.setShift(shift);
        movement.setMovementType(type);
        movement.setDirection(direction);
        movement.setAmount(new BigDecimal(amount));
        movement.setIdempotencyKey(type + "-" + amount);
        movement.setActorUsername("cashier.one");
        return movement;
    }
}