package nmk_backend.dto;

import nmk_backend.entity.InventoryBalance;
import nmk_backend.entity.InventoryLocation;
import java.util.List;
import java.util.Map;

public record PosInventoryReportResponse(
        Long locationId, String locationCode, String locationName, int variantCount,
        long onHandQuantity, long reservedQuantity, long availableQuantity, int lowStockCount,
        List<VariantStock> items
) {
    public record VariantStock(
            Long variantId, Long productId, String productName, String sku, String barcode,
            String variantName, Map<String, Object> attributes, int onHandQuantity,
            int reservedQuantity, int availableQuantity, int reorderLevel, boolean lowStock
    ) {
        public static VariantStock from(InventoryBalance balance) {
            var variant = balance.getVariant();
            var product = variant.getProduct();
            int available = balance.getAvailableQuantity();
            int reorder = balance.getReorderLevel() == null ? 0 : balance.getReorderLevel();
            return new VariantStock(
                    variant.getId(), product.getId(), product.getName(), variant.getSku(),
                    variant.getBarcode(), variant.getVariantName(),
                    java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(variant.getAttributes())),
                    balance.getOnHandQuantity(), balance.getReservedQuantity(), available, reorder,
                    reorder > 0 && available <= reorder
            );
        }
    }

    public static PosInventoryReportResponse from(InventoryLocation location, List<InventoryBalance> balances) {
        return fromRows(location.getId(), location.getCode(), location.getName(),
                balances.stream().map(VariantStock::from).toList());
    }

    public static PosInventoryReportResponse fromRows(
            Long locationId, String locationCode, String locationName, List<VariantStock> rows
    ) {
        List<VariantStock> sorted = rows.stream()
                .sorted(java.util.Comparator.comparing(
                                VariantStock::productName,
                                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(VariantStock::sku,
                                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return new PosInventoryReportResponse(
                locationId, locationCode, locationName, sorted.size(),
                sorted.stream().mapToLong(VariantStock::onHandQuantity).sum(),
                sorted.stream().mapToLong(VariantStock::reservedQuantity).sum(),
                sorted.stream().mapToLong(VariantStock::availableQuantity).sum(),
                (int) sorted.stream().filter(VariantStock::lowStock).count(), sorted
        );
    }
}
