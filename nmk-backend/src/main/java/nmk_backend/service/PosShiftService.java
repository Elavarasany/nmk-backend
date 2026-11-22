package nmk_backend.service;

import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class PosShiftService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final InventoryLocationRepository locationRepository;
    private final PosRegisterRepository registerRepository;
    private final PosShiftRepository shiftRepository;
    private final PosCashMovementRepository cashMovementRepository;

    public PosShiftService(
            InventoryLocationRepository locationRepository,
            PosRegisterRepository registerRepository,
            PosShiftRepository shiftRepository,
            PosCashMovementRepository cashMovementRepository
    ) {
        this.locationRepository = locationRepository;
        this.registerRepository = registerRepository;
        this.shiftRepository = shiftRepository;
        this.cashMovementRepository = cashMovementRepository;
    }

    @Transactional(readOnly = true)
    public List<PosRegisterResponse> getRegisters(Long locationId) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid locationId is required");
        }
        return registerRepository.findByLocationIdAndActiveTrueOrderByRegisterCode(locationId)
                .stream().map(PosRegisterResponse::from).toList();
    }

    @Transactional
    public PosRegisterResponse createRegister(PosRegisterCreateRequest request) {
        InventoryLocation location = locationRepository.findByIdForUpdate(request.locationId())
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        if (!location.isActive()) {
            throw new IllegalArgumentException("Cannot create a register for an inactive location");
        }

        String code = request.registerCode().trim().toUpperCase(Locale.ROOT);
        if (registerRepository.existsByLocationIdAndRegisterCodeIgnoreCase(location.getId(), code)) {
            throw new IllegalArgumentException("A register with this code already exists at the location");
        }

        PosRegister register = new PosRegister();
        register.setLocation(location);
        register.setRegisterCode(code);
        register.setName(request.name().trim());
        register.setActive(true);
        return PosRegisterResponse.from(registerRepository.saveAndFlush(register));
    }

    @Transactional(readOnly = true)
    public Page<PosShiftResponse> getShiftHistory(Long locationId, int page, int size) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid locationId is required");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("openedAt"), Sort.Order.desc("id")));
        return shiftRepository.findByRegisterLocationId(locationId, pageable)
                .map(this::response);
    }

    @Transactional(readOnly = true)
    public PosShiftResponse getCurrentShift(Long registerId, String username, String role) {
        if (registerId == null || registerId <= 0) {
            throw new IllegalArgumentException("A valid registerId is required");
        }
        PosShift shift = shiftRepository
                .findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(registerId, "OPEN")
                .orElse(null);
        if (shift == null) return null;
        if (!canManageOtherShifts(role) && !shift.getOpenedByUsername().equalsIgnoreCase(username)) {
            return null;
        }
        return response(shift);
    }

    @Transactional
    public PosShiftResponse openShift(PosShiftOpenRequest request, String username) {
        validateActor(username);
        BigDecimal openingFloat = money(request.openingFloat(), "Opening float");
        String key = normalizeKey(request.idempotencyKey());

        var previous = shiftRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            PosShift existing = previous.get();
            if (!Objects.equals(existing.getRegister().getId(), request.registerId())
                    || !existing.getOpenedByUsername().equalsIgnoreCase(username)
                    || existing.getOpeningFloat().compareTo(openingFloat) != 0) {
                throw new IllegalArgumentException("Shift idempotency key was reused with different details");
            }
            return response(existing);
        }

        PosRegister register = registerRepository.findByIdForUpdate(request.registerId())
                .orElseThrow(() -> new InventoryNotFoundException("POS register was not found"));
        // Recheck after taking the register lock so concurrent retries replay the first shift.
        var racedShift = shiftRepository.findByIdempotencyKey(key);
        if (racedShift.isPresent()) {
            PosShift existing = racedShift.get();
            if (!Objects.equals(existing.getRegister().getId(), request.registerId())
                    || !existing.getOpenedByUsername().equalsIgnoreCase(username)
                    || existing.getOpeningFloat().compareTo(openingFloat) != 0) {
                throw new IllegalArgumentException("Shift idempotency key was reused with different details");
            }
            return response(existing);
        }
        if (!register.isActive() || !register.getLocation().isActive()) {
            throw new IllegalArgumentException("POS register or its location is inactive");
        }

        if (shiftRepository.findFirstByRegisterIdAndStatusOrderByOpenedAtDesc(register.getId(), "OPEN").isPresent()) {
            throw new IllegalArgumentException("This register already has an open shift");
        }
        PosShift shift = new PosShift();
        shift.setRegister(register);
        shift.setOpenedByUsername(username);
        shift.setOpeningFloat(openingFloat);
        shift.setStatus("OPEN");
        shift.setIdempotencyKey(key);
        shiftRepository.saveAndFlush(shift);

        if (openingFloat.compareTo(ZERO) > 0) {
            saveCashMovement(
                    shift, "OPENING_FLOAT", "IN", openingFloat,
                    "SHIFT_OPEN", shift.getId(), "SHIFT-OPENING-FLOAT:" + shift.getId(),
                    username, "Opening cash float"
            );
        }
        return response(shift);
    }

    @Transactional
    public PosShiftResponse recordCashMovement(
            Long shiftId,
            PosCashMovementRequest request,
            String username,
            String role
    ) {
        String type = request.movementType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("CASH_IN", "CASH_OUT").contains(type)) {
            throw new IllegalArgumentException("Movement type must be CASH_IN or CASH_OUT");
        }
        BigDecimal amount = money(request.amount(), "Cash movement amount");
        if (amount.compareTo(ZERO) <= 0) {
            throw new IllegalArgumentException("Cash movement amount must be greater than zero");
        }
        String key = normalizeKey(request.idempotencyKey());

        // Idempotent retries must still replay after a shift has subsequently closed.
        var previous = cashMovementRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replayCashMovement(previous.get(), shiftId, type, amount, request.reason(), username, role);
        }

        // Lock the shift before the second idempotency lookup. This serializes
        // same-shift concurrent retries with cash-out and shift-close operations.
        PosShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new InventoryNotFoundException("POS shift was not found"));
        var racedMovement = cashMovementRepository.findByIdempotencyKey(key);
        if (racedMovement.isPresent()) {
            return replayCashMovement(racedMovement.get(), shiftId, type, amount, request.reason(), username, role);
        }
        if (!"OPEN".equals(shift.getStatus())) {
            throw new IllegalArgumentException("The POS shift is not open");
        }
        assertShiftActor(shift, username, role);
        if ("CASH_OUT".equals(type) && amount.compareTo(calculateExpectedCash(shiftId)) > 0) {
            throw new IllegalArgumentException("Cash out cannot exceed the cash currently expected in the drawer");
        }

        saveCashMovement(
                shift, type, "CASH_IN".equals(type) ? "IN" : "OUT",
                amount, null, null, key, username, request.reason().trim()
        );
        return response(shift);
    }

    @Transactional
    public PosShiftResponse closeShift(
            Long shiftId,
            PosShiftCloseRequest request,
            String username,
            String role
    ) {
        PosShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new InventoryNotFoundException("POS shift was not found"));
        assertShiftActor(shift, username, role);

        BigDecimal counted = money(request.closingCashCount(), "Closing cash count");
        String reason = request.reason() == null || request.reason().isBlank()
                ? null : request.reason().trim();
        if ("CLOSED".equals(shift.getStatus())) {
            if (shift.getClosingCashCount() != null
                    && shift.getClosingCashCount().compareTo(counted) == 0
                    && Objects.equals(shift.getClosingReason(), reason)) {
                return response(shift);
            }
            throw new IllegalArgumentException("This shift is already closed with different closing details");
        }
        if (!"OPEN".equals(shift.getStatus())) {
            throw new IllegalArgumentException("Only an open shift can be closed");
        }

        BigDecimal expected = calculateExpectedCash(shift.getId());
        BigDecimal variance = counted.subtract(expected).setScale(2);
        if (variance.compareTo(ZERO) != 0 && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required when the closing cash count differs from expected cash");
        }
        shift.setExpectedCash(expected);
        shift.setClosingCashCount(counted);
        shift.setCashVariance(variance);
        shift.setClosingReason(reason);
        shift.setClosedByUsername(username);
        shift.setStatus("CLOSED");
        shift.setClosedAt(java.time.LocalDateTime.now());
        return response(shiftRepository.saveAndFlush(shift));
    }

    @Transactional(readOnly = true)
    public List<PosCashMovementResponse> getCashMovements(Long shiftId, String username, String role) {
        PosShift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new InventoryNotFoundException("POS shift was not found"));
        assertShiftActor(shift, username, role);
        return cashMovementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(shiftId)
                .stream().map(PosCashMovementResponse::from).toList();
    }

    @Transactional
    public void recordCashSale(
            PosShift shift,
            BigDecimal amount,
            String idempotencyKey,
            String username,
            Long saleId
    ) {
        if (amount == null || amount.compareTo(ZERO) <= 0) return;
        saveCashMovement(
                shift, "CASH_SALE", "IN", amount,
                "POS_SALE", saleId, idempotencyKey, username,
                "Cash tender for POS sale " + saleId
        );
    }

    @Transactional
    public void recordCashRefund(
            PosShift shift,
            BigDecimal amount,
            String idempotencyKey,
            String username,
            Long returnId
    ) {
        if (amount == null || amount.compareTo(ZERO) <= 0) return;
        PosShift locked = shiftRepository.findByIdForUpdate(shift.getId())
                .orElseThrow(() -> new InventoryNotFoundException("POS shift was not found"));
        if (!"OPEN".equals(locked.getStatus())) {
            throw new IllegalArgumentException("Cash refunds require an open shift");
        }

        String key = normalizeKey(idempotencyKey);
        var previous = cashMovementRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            PosCashMovement existing = previous.get();
            if (!Objects.equals(existing.getShift().getId(), locked.getId())
                    || existing.getActorUsername() == null
                    || !existing.getActorUsername().equalsIgnoreCase(username)
                    || !"CASH_REFUND".equals(existing.getMovementType())
                    || existing.getAmount().compareTo(amount) != 0
                    || !Objects.equals(existing.getReferenceId(), returnId)) {
                throw new IllegalArgumentException("Cash refund idempotency key was reused with different details");
            }
            return;
        }

        if (amount.compareTo(calculateExpectedCash(locked.getId())) > 0) {
            throw new IllegalArgumentException("Cash refund exceeds the cash currently expected in the drawer");
        }
        saveCashMovement(
                locked, "CASH_REFUND", "OUT", amount,
                "POS_RETURN", returnId, key, username,
                "Cash refund for return " + returnId
        );
    }

    @Transactional
    public PosShift requireOpenShift(Long shiftId, Long locationId, String username) {
        PosShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new InventoryNotFoundException("An open POS shift is required"));
        if (!"OPEN".equals(shift.getStatus())) {
            throw new IllegalArgumentException("The selected POS shift is not open");
        }
        if (!Objects.equals(shift.getRegister().getLocation().getId(), locationId)) {
            throw new IllegalArgumentException("The shift belongs to a different inventory location");
        }
        if (!shift.getOpenedByUsername().equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("You can only sell against your own open shift");
        }
        return shift;
    }

    @Transactional
    public PosShift requireOpenShiftForManagerAction(
            Long shiftId, Long locationId, String username, String role
    ) {
        PosShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new InventoryNotFoundException("An open POS shift is required"));
        if (!"OPEN".equals(shift.getStatus())) {
            throw new IllegalArgumentException("The selected POS shift is not open");
        }
        if (!Objects.equals(shift.getRegister().getLocation().getId(), locationId)) {
            throw new IllegalArgumentException("The shift belongs to a different inventory location");
        }
        if (!canManageOtherShifts(role)
                && !shift.getOpenedByUsername().equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("You can only process returns on your own open shift");
        }
        return shift;
    }

    private PosShift lockOpenShift(Long shiftId) {
        PosShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new InventoryNotFoundException("POS shift was not found"));
        if (!"OPEN".equals(shift.getStatus())) {
            throw new IllegalArgumentException("The POS shift is not open");
        }
        return shift;
    }

    private PosShiftResponse replayCashMovement(
            PosCashMovement existing,
            Long requestedShiftId,
            String requestedType,
            BigDecimal requestedAmount,
            String requestedReason,
            String username,
            String role
    ) {
        PosShift existingShift = existing.getShift();
        assertShiftActor(existingShift, username, role);
        if (!Objects.equals(existingShift.getId(), requestedShiftId)
                || existing.getActorUsername() == null
                || !existing.getActorUsername().equalsIgnoreCase(username)
                || !Objects.equals(existing.getMovementType(), requestedType)
                || existing.getAmount().compareTo(requestedAmount) != 0
                || !Objects.equals(existing.getReason(), requestedReason == null ? null : requestedReason.trim())) {
            throw new IllegalArgumentException("Cash movement idempotency key was reused with different details");
        }
        return response(existingShift);
    }

    private void assertShiftActor(PosShift shift, String username, String role) {
        if (!canManageOtherShifts(role) && !shift.getOpenedByUsername().equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("You can only manage your own shift");
        }
    }

    private boolean canManageOtherShifts(String role) {
        return "ADMIN".equals(role) || "MANAGER".equals(role);
    }

    private BigDecimal calculateExpectedCash(Long shiftId) {
        return cashMovementRepository.findByShiftIdOrderByCreatedAtAscIdAsc(shiftId).stream()
                .map(movement -> "IN".equals(movement.getDirection())
                        ? movement.getAmount()
                        : movement.getAmount().negate())
                .reduce(ZERO, BigDecimal::add)
                .setScale(2);
    }

    private PosShiftResponse response(PosShift shift) {
        BigDecimal expected = "CLOSED".equals(shift.getStatus()) && shift.getExpectedCash() != null
                ? shift.getExpectedCash()
                : calculateExpectedCash(shift.getId());
        return new PosShiftResponse(
                shift.getId(),
                shift.getRegister().getId(),
                shift.getRegister().getRegisterCode(),
                shift.getRegister().getLocation().getId(),
                shift.getOpenedByUsername(),
                shift.getClosedByUsername(),
                shift.getOpeningFloat(),
                expected,
                shift.getClosingCashCount(),
                shift.getCashVariance(),
                shift.getClosingReason(),
                shift.getStatus(),
                shift.getOpenedAt(),
                shift.getClosedAt()
        );
    }

    private void saveCashMovement(
            PosShift shift, String type, String direction, BigDecimal amount,
            String referenceType, Long referenceId, String key,
            String username, String reason
    ) {
        PosCashMovement movement = new PosCashMovement();
        movement.setShift(shift);
        movement.setMovementType(type);
        movement.setDirection(direction);
        movement.setAmount(amount.setScale(2));
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setIdempotencyKey(normalizeKey(key));
        movement.setActorUsername(username);
        movement.setReason(reason);
        cashMovementRepository.saveAndFlush(movement);
    }

    private BigDecimal money(BigDecimal amount, String label) {
        if (amount == null || amount.scale() > 2 || amount.precision() > 12
                || amount.compareTo(ZERO) < 0) {
            throw new IllegalArgumentException(label + " must be a non-negative amount with up to two decimal places");
        }
        return amount.setScale(2);
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 150) {
            throw new IllegalArgumentException("A valid idempotency key is required");
        }
        return key.trim();
    }

    private void validateActor(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated staff account is required");
        }
    }
}