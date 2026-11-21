package nmk_backend.service;

import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryConflictException;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class StockCountService {

    private static final List<String> ACTIVE_STATUSES = List.of("COUNTING", "READY_FOR_REVIEW");

    private final InventoryLocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final ProductVariantRepository variantRepository;
    private final StockCountSessionRepository sessionRepository;
    private final StockCountLineRepository lineRepository;
    private final InventoryService inventoryService;
    private final AdminRepository adminRepository;

    public StockCountService(
            InventoryLocationRepository locationRepository,
            InventoryBalanceRepository balanceRepository,
            ProductVariantRepository variantRepository,
            StockCountSessionRepository sessionRepository,
            StockCountLineRepository lineRepository,
            InventoryService inventoryService,
            AdminRepository adminRepository
    ) {
        this.locationRepository = locationRepository;
        this.balanceRepository = balanceRepository;
        this.variantRepository = variantRepository;
        this.sessionRepository = sessionRepository;
        this.lineRepository = lineRepository;
        this.inventoryService = inventoryService;
        this.adminRepository = adminRepository;
    }

    @Transactional(readOnly = true)
    public StockCountSessionResponse getCurrent(Long locationId) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid locationId is required");
        }
        return sessionRepository.findFirstByLocationIdAndStatusInOrderByStartedAtDesc(
                        locationId, ACTIVE_STATUSES
                )
                .map(StockCountSessionResponse::from)
                .orElse(null);
    }

    @Transactional
    public StockCountSessionResponse start(StockCountStartRequest request, String username) {
        validateActor(username);
        InventoryLocation location = locationRepository.findByIdForUpdate(request.locationId())
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        if (!location.isActive()) {
            throw new IllegalArgumentException("Cannot count stock at an inactive location");
        }
        if (location.isStockCountLocked()) {
            throw new InventoryConflictException("A stock count is already freezing this location");
        }
        if (sessionRepository.findFirstByLocationIdAndStatusInOrderByStartedAtDesc(
                location.getId(), ACTIVE_STATUSES
        ).isPresent()) {
            throw new InventoryConflictException("An active stock count already exists for this location");
        }

        // Freeze stock-changing operations while the physical count is in progress.
        // The location row lock makes this snapshot consistent with inventory movements.
        location.setStockCountLocked(true);
        locationRepository.saveAndFlush(location);

        StockCountSession session = new StockCountSession();
        session.setLocation(location);
        session.setStatus("COUNTING");
        session.setReason(request.reason().trim());
        session.setStartedByUsername(username);
        sessionRepository.saveAndFlush(session);

        Map<Long, InventoryBalance> balances = new HashMap<>();
        balanceRepository.findByLocationId(location.getId()).forEach(
                balance -> balances.put(balance.getVariant().getId(), balance)
        );

        Map<Long, ProductVariant> variants = new LinkedHashMap<>();
        variantRepository.findAll().stream()
                .filter(variant -> variant.getProduct() != null)
                .forEach(variant -> variants.put(variant.getId(), variant));

        for (ProductVariant variant : variants.values()) {
            InventoryBalance balance = balances.get(variant.getId());
            StockCountLine line = new StockCountLine();
            line.setSession(session);
            line.setVariant(variant);
            line.setSnapshotOnHand(balance == null ? 0 : balance.getOnHandQuantity());
            session.getLines().add(line);
        }

        lineRepository.saveAllAndFlush(session.getLines());
        return StockCountSessionResponse.from(session);
    }

    @Transactional
    public StockCountLineResponse recordCount(
            Long sessionId,
            Long lineId,
            StockCountLineRequest request,
            String username
    ) {
        validateActor(username);
        StockCountSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count session was not found"));
        if (!"COUNTING".equals(session.getStatus())) {
            throw new IllegalArgumentException("Only a COUNTING session accepts quantity entries");
        }

        StockCountLine line = lineRepository.findBySessionAndIdForUpdate(sessionId, lineId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count line was not found"));
        line.setCountedQuantity(request.countedQuantity());
        line.setCountedByUsername(username);
        line.setCountedAt(LocalDateTime.now());
        return StockCountLineResponse.from(lineRepository.saveAndFlush(line));
    }

    @Transactional
    public StockCountSessionResponse submit(Long sessionId, String username) {
        validateActor(username);
        StockCountSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count session was not found"));
        if (!"COUNTING".equals(session.getStatus())) {
            throw new IllegalArgumentException("Only a COUNTING session can be submitted for review");
        }

        List<StockCountLine> lines = lineRepository.findBySessionIdOrderByIdAsc(sessionId);
        if (lines.isEmpty() || lines.stream().anyMatch(line -> line.getCountedQuantity() == null)) {
            throw new IllegalArgumentException("Every stock count line must have a physical quantity before submission");
        }

        session.setStatus("READY_FOR_REVIEW");
        session.setSubmittedByUsername(username);
        session.setSubmittedAt(LocalDateTime.now());
        sessionRepository.saveAndFlush(session);
        return StockCountSessionResponse.from(session);
    }

    @Transactional
    public StockCountSessionResponse post(Long sessionId, String username) {
        validateActor(username);

        StockCountSession snapshot = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count session was not found"));
        InventoryLocation location = locationRepository.findByIdForUpdate(snapshot.getLocation().getId())
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        StockCountSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count session was not found"));

        if (!"READY_FOR_REVIEW".equals(session.getStatus())) {
            throw new IllegalArgumentException("Only a submitted stock count can be posted");
        }
        if (!location.isStockCountLocked()) {
            throw new InventoryConflictException("The location stock-count freeze was removed unexpectedly");
        }

        List<StockCountLine> lines = lineRepository.findBySessionIdOrderByIdAsc(sessionId);
        if (lines.isEmpty() || lines.stream().anyMatch(line -> line.getCountedQuantity() == null)) {
            throw new IllegalArgumentException("Every stock count line must have a physical quantity before posting");
        }

        Admin actor = adminRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated staff account was not found"));

        // Keep the location row locked while temporarily clearing the flag and
        // applying all variance movements. No other transaction can interleave.
        location.setStockCountLocked(false);
        locationRepository.saveAndFlush(location);

        for (StockCountLine line : lines) {
            int variance = line.getCountedQuantity() - line.getSnapshotOnHand();
            if (variance == 0) continue;

            inventoryService.applyStockCountVariance(
                    location.getId(),
                    line.getVariant().getId(),
                    variance,
                    "STOCK-COUNT:" + sessionId + ":" + line.getVariant().getId(),
                    actor.getId(),
                    "Physical stock count session " + sessionId + ": " + session.getReason(),
                    sessionId
            );
        }

        session.setStatus("POSTED");
        session.setPostedByUsername(username);
        session.setPostedAt(LocalDateTime.now());
        sessionRepository.saveAndFlush(session);
        return StockCountSessionResponse.from(session);
    }

    @Transactional
    public StockCountSessionResponse cancel(Long sessionId, String username) {
        validateActor(username);
        StockCountSession snapshot = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count session was not found"));
        InventoryLocation location = locationRepository.findByIdForUpdate(snapshot.getLocation().getId())
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        StockCountSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new InventoryNotFoundException("Stock count session was not found"));

        if (!ACTIVE_STATUSES.contains(session.getStatus())) {
            throw new IllegalArgumentException("Only an active stock count can be cancelled");
        }
        location.setStockCountLocked(false);
        locationRepository.saveAndFlush(location);
        session.setStatus("CANCELLED");
        sessionRepository.saveAndFlush(session);
        return StockCountSessionResponse.from(session);
    }

    private void validateActor(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated staff account is required");
        }
    }
}