package nmk_backend.service;

import nmk_backend.dto.PosSalesReportResponse;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.PosReturnRepository;
import nmk_backend.repository.PosSaleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PosReportService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final PosSaleRepository saleRepository;
    private final PosReturnRepository returnRepository;
    private final InventoryLocationRepository locationRepository;

    public PosReportService(
            PosSaleRepository saleRepository,
            PosReturnRepository returnRepository,
            InventoryLocationRepository locationRepository
    ) {
        this.saleRepository = saleRepository;
        this.returnRepository = returnRepository;
        this.locationRepository = locationRepository;
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
        List<PosSale> sales = saleRepository.findCompletedSalesForReport(
                locationId, fromDateTime, toExclusive
        );
        List<PosReturn> returns = returnRepository.findCompletedReturnsForReport(
                locationId, fromDateTime, toExclusive
        );

        BigDecimal subtotal = ZERO;
        BigDecimal discounts = ZERO;
        BigDecimal netSales = ZERO;
        Map<String, BigDecimal> salesByPayment = new TreeMap<>();
        Map<String, BigDecimal> refundsByPayment = new TreeMap<>();
        Map<String, MutableCashier> cashiers = new TreeMap<>();
        Map<LocalDate, MutableDaily> daily = new TreeMap<>();
        Map<Long, MutableProduct> products = new HashMap<>();

        for (PosSale sale : sales) {
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
                salesByPayment.merge(
                        payment.getPaymentMethod(),
                        payment.getAmount(),
                        BigDecimal::add
                );
            }
            for (PosSaleItem item : sale.getItems()) {
                MutableProduct product = products.computeIfAbsent(
                        item.getProductId(),
                        ignored -> new MutableProduct(item.getProductId(), item.getProductName(), item.getSku())
                );
                product.quantitySold += item.getQuantity();
                product.grossLineTotal = product.grossLineTotal.add(item.getLineTotal());
            }
        }

        BigDecimal refundTotal = ZERO;
        BigDecimal recordedRefundPaymentTotal = ZERO;
        int customerReturnCount = 0;
        for (PosReturn record : returns) {
            refundTotal = refundTotal.add(record.getRefundTotal());
            MutableDaily day = daily.computeIfAbsent(
                    record.getCreatedAt().toLocalDate(), ignored -> new MutableDaily()
            );
            // A full void is a reversal, not a customer return. Keep its refund
            // in the financial totals, but do not inflate the operational return count.
            if ("RETURN".equals(record.getReturnType())) {
                customerReturnCount++;
                day.returnCount++;
            }
            day.refunds = day.refunds.add(record.getRefundTotal());

            for (PosRefundPayment payment : record.getRefundPayments()) {
                refundsByPayment.merge(
                        payment.getPaymentMethod(),
                        payment.getAmount(),
                        BigDecimal::add
                );
                recordedRefundPaymentTotal = recordedRefundPaymentTotal.add(payment.getAmount());
            }
        }

        List<PosSalesReportResponse.CashierTotals> cashierRows = cashiers.entrySet().stream()
                .map(entry -> new PosSalesReportResponse.CashierTotals(
                        entry.getKey(),
                        entry.getValue().transactionCount,
                        entry.getValue().subtotal,
                        entry.getValue().discounts,
                        entry.getValue().netSales
                ))
                .sorted(Comparator.comparing(
                        PosSalesReportResponse.CashierTotals::netSales,
                        Comparator.reverseOrder()
                ))
                .toList();

        List<PosSalesReportResponse.DailyTotals> dailyRows = daily.entrySet().stream()
                .map(entry -> new PosSalesReportResponse.DailyTotals(
                        entry.getKey(),
                        entry.getValue().transactionCount,
                        entry.getValue().netSales,
                        entry.getValue().returnCount,
                        entry.getValue().refunds,
                        entry.getValue().netSales.subtract(entry.getValue().refunds)
                ))
                .toList();

        List<PosSalesReportResponse.ProductTotals> topProducts = products.values().stream()
                .map(product -> new PosSalesReportResponse.ProductTotals(
                        product.productId, product.productName, product.sku,
                        product.quantitySold, product.grossLineTotal
                ))
                .sorted(Comparator.comparing(
                        PosSalesReportResponse.ProductTotals::grossLineTotal,
                        Comparator.reverseOrder()
                ))
                .limit(10)
                .toList();

        return new PosSalesReportResponse(
                locationId, from, to, sales.size(),
                subtotal, discounts, netSales,
                customerReturnCount, refundTotal, refundTotal,
                recordedRefundPaymentTotal, netSales.subtract(refundTotal),
                Collections.unmodifiableMap(salesByPayment),
                Collections.unmodifiableMap(refundsByPayment),
                cashierRows, dailyRows, topProducts
        );
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
        private final String sku;
        private long quantitySold;
        private BigDecimal grossLineTotal = ZERO;

        private MutableProduct(Long productId, String productName, String sku) {
            this.productId = productId;
            this.productName = productName;
            this.sku = sku;
        }
    }
}