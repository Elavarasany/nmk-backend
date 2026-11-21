package nmk_backend.dto;
import nmk_backend.entity.InventoryBalance;
import nmk_backend.entity.InventoryLocation;
import java.util.List;
import java.util.Map;
public record PosInventoryReportResponse(
 Long locationId,String locationCode,String locationName,int variantCount,
 long onHandQuantity,long reservedQuantity,long availableQuantity,int lowStockCount,
 List<VariantStock> items
) {
 public record VariantStock(Long variantId,Long productId,String productName,String sku,String barcode,
  String variantName,Map<String,Object> attributes,int onHandQuantity,int reservedQuantity,
  int availableQuantity,int reorderLevel,boolean lowStock) {
  public static VariantStock from(InventoryBalance b) {
   var v=b.getVariant(); var p=v.getProduct();
   int available=b.getAvailableQuantity(), reorder=b.getReorderLevel()==null?0:b.getReorderLevel();
   return new VariantStock(v.getId(),p.getId(),p.getName(),v.getSku(),v.getBarcode(),v.getVariantName(),
    java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(v.getAttributes())),b.getOnHandQuantity(),b.getReservedQuantity(),available,reorder,reorder>0&&available<=reorder);
  }
 }
 public static PosInventoryReportResponse from(InventoryLocation location,List<InventoryBalance> balances) {
  List<VariantStock> rows=balances.stream().map(VariantStock::from)
   .sorted(java.util.Comparator.comparing(VariantStock::productName,java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
    .thenComparing(VariantStock::sku,java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
  return new PosInventoryReportResponse(location.getId(),location.getCode(),location.getName(),rows.size(),
   rows.stream().mapToLong(VariantStock::onHandQuantity).sum(),
   rows.stream().mapToLong(VariantStock::reservedQuantity).sum(),
   rows.stream().mapToLong(VariantStock::availableQuantity).sum(),
   (int)rows.stream().filter(VariantStock::lowStock).count(),rows);
 }
}
