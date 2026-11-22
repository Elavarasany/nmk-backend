package nmk_backend.service;

import nmk_backend.dto.PosSalesReportResponse;
import nmk_backend.dto.PosInventoryReportResponse;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.InventoryBalanceRepository;
import nmk_backend.repository.PosReturnRepository;
import nmk_backend.repository.PosSaleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PosReportService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int REPORT_BATCH_SIZE = 200;

    private final PosSaleRepository saleRepository;
    private final PosReturnRepository returnRepository;
    private final InventoryLocationRepository locationRepository;
    private final nmk_backend.repository.PosExchangeCreditSettlementRepository exchangeCreditRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;

    public PosReportService(
            PosSaleRepository saleRepository,
            PosReturnRepository returnRepository,
            InventoryLocationRepository locationRepository,
            nmk_backend.repository.PosExchangeCreditSettlementRepository exchangeCreditRepository,
            InventoryBalanceRepository inventoryBalanceRepository
    ) {
        this.saleRepository = saleRepository;
        this.returnRepository = returnRepository;
        this.locationRepository = locationRepository;
        this.exchangeCreditRepository = exchangeCreditRepository;
        this.inventoryBalanceRepository = inventoryBalanceRepository;
    }

    @Transactional(readOnly = true)
    public PosInventoryReportResponse getInventoryReport(Long locationId) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid inventory location is required");
        }
        InventoryLocation location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        return PosInventoryReportResponse.from(location, inventoryBalanceRepository.findByLocationId(locationId));
    }

    @Transactional(readOnly = true)
    public PosSalesReportResponse getSalesReport(Long locationId, LocalDate from, LocalDate to) {
        if (locationId == null || locationId <= 0 || from == null || to == null) {
            throw new IllegalArgumentException("Location, start date and end date are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("Report date range cannot exceed 367 calendar days");
        }
        locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));

        var fromDateTime = from.atStartOfDay();
        var toExclusive = to.plusDays(1).atStartOfDay();

        BigDecimal exchangeCreditApplied = Optional.ofNullable(
                exchangeCreditRepository.sumExchangeCreditApplied(locationId, fromDateTime, toExclusive)
        ).orElse(ZERO);

        ReportAccumulator totals = new ReportAccumulator();
        int pageNumber = 0;
        Page<PosSale> salePage;
        do {
            salePage = saleRepository.findCompletedSalesForReport(
                    locationId, fromDateTime, toExclusive,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "createdAt", "id"))
            );
            for (PosSale sale : salePage.getContent()) {
                totals.addSale(sale);
            }
        } while (salePage.hasNext());

        pageNumber = 0;
        Page<PosReturn> returnPage;
        do {
            returnPage = returnRepository.findCompletedReturnsForReport(
                    locationId, fromDateTime, toExclusive,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "createdAt", "id"))
            );
            for (PosReturn record : returnPage.getContent()) {
                totals.addReturn(record);
            }
        } while (returnPage.hasNext());

        return totals.toResponse(locationId, from, to, exchangeCreditApplied);
    }

    private static class ReportAccumulator {
        private int transactionCount;
        private BigDecimal subtotal = ZERO;
        private BigDecimal discounts = ZERO;
        private BigDecimal netSales = ZERO;
        private BigDecimal refundTotal = ZERO;
        private BigDecimal salesReversals = ZERO;
        private BigDecimal recordedRefundPaymentTotal = ZERO;
        private int customerReturnCount;
        private final Map<String, BigDecimal> salesByPayment = new TreeMap<>();
        private final Map<String, BigDecimal> refundsByPayment = new TreeMap<>();
        private final Map<String, MutableCashier> cashiers = new TreeMap<>();
        private final Map<LocalDate, MutableDaily> daily = new TreeMap<>();
        private final Map<Long, MutableProduct> products = new HashMap<>();

        private void addSale(PosSale sale) {
            transactionCount++;
            subtotal = subtotal.add(sale.getSubtotal());
            discounts = discounts.add(sale.getDiscountAmount());
            netSales = netSales.add(sale.getGrandTotal());

            MutableCashier cashier = cashiers.computeIfAbsent(
                    sale.getCashierUsername(), ignored -> new MutableCashier()
            );
            cashier.transactionCount++;
            cashier.subtotal = cashier.subtotal.add(sale.getSubtotal());
            cashier.discounts = cashier.discounts.add(sale.getDiscountAmount());
            cashier.netSales = cashier.netSales.add(sale.getGrandTotal());

            MutableDaily day = daily.computeIfAbsent(
                    sale.getCreatedAt().toLocalDate(), ignored -> new MutableDaily()
            );
            day.transactionCount++;
            day.netSales = day.netSales.add(sale.getGrandTotal());

            for (PosPayment payment : sale.getPayments()) {
                salesByPayment.merge(payment.getPaymentMethod(), payment.getAmount(), BigDecimal::add);
            }
            for (PosSaleItem item : sale.getItems()) {
                MutableProduct product = products.computeIfAbsent(
                        item.getProductId(),
                        ignored -> new MutableProduct(item.getProductId(), item.getProductName())
                );
                product.quantitySold += item.getQuantity();
                product.grossLineTotal = product.grossLineTotal.add(item.getLineTotal());
            }
        }

        private void addReturn(PosReturn record) {
            refundTotal = refundTotal.add(record.getRefundTotal());
            if ("VOID".equals(record.getReturnType())) {
                salesReversals = salesReversals.add(record.getRefundTotal());
            }
            MutableDaily day = daily.computeIfAbsent(
                    record.getCreatedAt().toLocalDate(), ignored -> new MutableDaily()
            );
            // A full void is a reversal, not a customer return.
            if ("RETURN".equals(record.getReturnType())) {
                customerReturnCount++;
                day.returnCount++;
            }
            day.refunds = day.refunds.add(record.getRefundTotal());

            for (PosRefundPayment payment : record.getRefundPayments()) {
                refundsByPayment.merge(payment.getPaymentMethod(), payment.getAmount(), BigDecimal::add);
                recordedRefundPaymentTotal = recordedRefundPaymentTotal.add(payment.getAmount());
            }
        }

        private PosSalesReportResponse toResponse(
                Long locationId, LocalDate from, LocalDate to, BigDecimal exchangeCreditApplied
        ) {
            List<PosSalesReportResponse.CashierTotals> cashierRows = cashiers.entrySet().stream()
                    .map(entry -> new PosSalesReportResponse.CashierTotals(
                            entry.getKey(), entry.getValue().transactionCount,
                            entry.getValue().subtotal, entry.getValue().discounts, entry.getValue().netSales
                    ))
                    .sorted(Comparator.comparing(
                            PosSalesReportResponse.CashierTotals::netSales, Comparator.reverseOrder()
                    ))
                    .toList();

            List<PosSalesReportResponse.DailyTotals> dailyRows = daily.entrySet().stream()
                    .map(entry -> new PosSalesReportResponse.DailyTotals(
                            entry.getKey(), entry.getValue().transactionCount, entry.getValue().netSales,
                            entry.getValue().returnCount, entry.getValue().refunds,
                            entry.getValue().netSales.subtract(entry.getValue().refunds)
                    ))
                    .toList();

            List<PosSalesReportResponse.ProductTotals> topProducts = products.values().stream()
                    .map(product -> new PosSalesReportResponse.ProductTotals(
                            product.productId, product.productName, product.quantitySold, product.grossLineTotal
                    ))
                    .sorted(Comparator.comparing(
                            PosSalesReportResponse.ProductTotals::grossLineTotal, Comparator.reverseOrder()
                    ))
                    .limit(10)
                    .toList();

            return new PosSalesReportResponse(
                    locationId, from, to, transactionCount,
                    subtotal, discounts, netSales,
                    customerReturnCount, refundTotal, salesReversals,
                    recordedRefundPaymentTotal, exchangeCreditApplied, netSales.subtract(refundTotal),
                    Collections.unmodifiableMap(salesByPayment),
                    Collections.unmodifiableMap(refundsByPayment),
                    cashierRows, dailyRows, topProducts
            );
        }
    }

    private static class MutableCashier {
        private int transactionCount;
        private BigDecimal subtotal = ZERO;
        private BigDecimal discounts = ZERO;
        private BigDecimal netSales = ZERO;
    }

    private static class MutableDaily {
        private int transactionCount;
        private BigDecimal netSales = ZERO;
        private int returnCount;
        private BigDecimal refunds = ZERO;
    }

    private static class MutableProduct {
        private final Long productId;
        private final String productName;
        private long quantitySold;
        private BigDecimal grossLineTotal = ZERO;

        private MutableProduct(Long productId, String productName) {
            this.productId = productId;
            this.productName = productName;
        }
    }
}