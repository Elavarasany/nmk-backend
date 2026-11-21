package nmk_backend.service;

import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InsufficientStockException;
import nmk_backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class PosExchangeMySqlTest {
 @Autowired private PosCheckoutService checkoutService;
 @Autowired private PosExchangeService exchangeService;
 @Autowired private PosReportService reportService;
 @Autowired private PosShiftService shiftService;
 @Autowired private InventoryService inventoryService;
 @Autowired private AdminRepository admins;
 @Autowired private InventoryLocationRepository locations;
 @Autowired private ProductRepository products;
 @Autowired private ProductVariantRepository variants;
 @Autowired private InventoryBalanceRepository balances;
 @Autowired private PosSaleItemRepository saleItems;
 @Autowired private PosSaleRepository sales;
 @Autowired private PosReturnRepository returns;
 @Autowired private PosExchangeRepository exchanges;
 @Autowired private PosExchangeCreditSettlementRepository credits;
 @Autowired private PosPaymentRepository payments;
 @Autowired private PosCashMovementRepository cashMovements;
 private record Fixture(Long locationId,Long productId,Long variantId,Long shiftId,String username) {}
 private record Outcome(boolean successful,Throwable failure) {}

 @Test
 void equalValueExchangeAndReplayCommitAtomicallyOnMySql() {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),2,"MYSQL-EX-OPEN-"+UUID.randomUUID(),null,"Exchange MySQL opening stock");
  PosSaleResponse original=checkout(f,"MYSQL-EX-SALE-"+UUID.randomUUID());
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  PosSaleItemRequest replacement=new PosSaleItemRequest();
  replacement.setProductId(f.productId()); replacement.setVariantId(f.variantId()); replacement.setQuantity(1);
  PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Equal value MySQL exchange","MYSQL-EX-KEY-"+UUID.randomUUID(),
   List.of(new PosReturnItemRequest(originalLine,1,"RESTOCK",null)),List.of(replacement),
   new BigDecimal("0.00"),List.of(),List.of());

  PosExchangeResponse first=exchangeService.exchange(original.id(),request,f.username(),"CASHIER");
  PosExchangeResponse replay=exchangeService.exchange(original.id(),request,f.username(),"CASHIER");
  assertEquals(first.id(),replay.id());
  assertEquals(new BigDecimal("100.00"),first.creditApplied());
  assertEquals(new BigDecimal("0.00"),first.amountCollected());
  assertEquals(new BigDecimal("0.00"),first.actualRefund());
  assertTrue(credits.existsByExchangeId(first.id()));
  assertTrue(payments.findBySaleId(first.replacementSaleId()).isEmpty(),
   "Non-cash exchange credit must not be written as a tender row");
  assertEquals(1,returns.findBySaleIdOrderByCreatedAtDesc(original.id()).size());
  assertEquals(1,exchanges.findByOriginalSaleIdOrderByCreatedAtDesc(original.id()).size());
  var report=reportService.getSalesReport(f.locationId(),java.time.LocalDate.now(),java.time.LocalDate.now());
  assertEquals(new BigDecimal("100.00"),report.exchangeCreditApplied(),
   "Exchange credit must be reported separately from payment tenders and refund payments");
  assertEquals(new BigDecimal("0.00"),report.recordedRefundPayments());
  assertEquals(new BigDecimal("100.00"),report.netSalesAfterRefunds());
  InventoryBalance balance=balances.findByLocationIdAndVariantId(f.locationId(),f.variantId()).orElseThrow();
  assertEquals(1,balance.getOnHandQuantity(),"Restock and replacement sale must both commit");
 }

 @Test
 void damagedReturnIsNotRestockedDuringExchangeOnMySql() {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),2,"MYSQL-EX-DAMAGED-OPEN-"+UUID.randomUUID(),null,"Damaged exchange opening stock");
  PosSaleResponse original=checkout(f,"MYSQL-EX-DAMAGED-SALE-"+UUID.randomUUID());
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  PosSaleItemRequest replacement=new PosSaleItemRequest();
  replacement.setProductId(f.productId()); replacement.setVariantId(f.variantId()); replacement.setQuantity(1);
  PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Damaged item exchange","MYSQL-EX-DAMAGED-KEY-"+UUID.randomUUID(),
   List.of(new PosReturnItemRequest(originalLine,1,"DAMAGED","Lens coating scratched")),List.of(replacement),
   new BigDecimal("0.00"),List.of(),List.of());

  PosExchangeResponse result=exchangeService.exchange(original.id(),request,f.username(),"CASHIER");

  assertEquals(new BigDecimal("100.00"),result.returnedValue());
  InventoryBalance balance=balances.findByLocationIdAndVariantId(f.locationId(),f.variantId()).orElseThrow();
  assertEquals(0,balance.getOnHandQuantity(),"Damaged returned stock must not be added back before the replacement sale");
  PosReturn persisted=returns.findBySaleIdOrderByCreatedAtDesc(original.id()).get(0);
  assertEquals("DAMAGED",persisted.getItems().get(0).getRestockDisposition());
  assertEquals("Lens coating scratched",persisted.getItems().get(0).getDispositionReason());
 }

 @Test
 void higherValueReplacementCollectsOnlyTheDifferenceOnMySql() {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),2,"MYSQL-EX-HIGH-OPEN-"+UUID.randomUUID(),null,"Higher exchange original stock");
  PosSaleResponse original=checkout(f,"MYSQL-EX-HIGH-SALE-"+UUID.randomUUID());
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  ProductVariant higher=createReplacementVariant("HIGH-"+UUID.randomUUID(),new BigDecimal("150.00"));
  inventoryService.receiveStock(f.locationId(),higher.getId(),1,"MYSQL-EX-HIGH-NEW-"+UUID.randomUUID(),null,"Higher exchange replacement stock");
  PosSaleItemRequest replacement=new PosSaleItemRequest();
  replacement.setProductId(higher.getProduct().getId()); replacement.setVariantId(higher.getId()); replacement.setQuantity(1);
  PosPaymentRequest collection=new PosPaymentRequest();
  collection.setPaymentMethod("CASH"); collection.setAmount(new BigDecimal("50.00")); collection.setTenderedAmount(new BigDecimal("50.00"));
  PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Higher value exchange","MYSQL-EX-HIGH-KEY-"+UUID.randomUUID(),
   List.of(new PosReturnItemRequest(originalLine,1,"RESTOCK",null)),List.of(replacement),
   new BigDecimal("0.00"),List.of(collection),List.of());

  PosExchangeResponse result=exchangeService.exchange(original.id(),request,f.username(),"CASHIER");
  assertEquals(new BigDecimal("100.00"),result.creditApplied());
  assertEquals(new BigDecimal("50.00"),result.amountCollected());
  assertEquals(new BigDecimal("0.00"),result.actualRefund());
  assertEquals(1,payments.findBySaleId(result.replacementSaleId()).size());
  assertEquals(new BigDecimal("50.00"),payments.findBySaleId(result.replacementSaleId()).get(0).getAmount());
  List<PosCashMovement> cashIn=cashMovements.findByShiftIdOrderByCreatedAtAscIdAsc(f.shiftId()).stream()
   .filter(m->m.getIdempotencyKey().startsWith("POS-EXCHANGE-CASH-IN:")).toList();
  assertEquals(1,cashIn.size(),"Only the actual cash difference should enter the drawer");
  assertEquals("IN",cashIn.get(0).getDirection());
  assertEquals(new BigDecimal("50.00"),cashIn.get(0).getAmount());
 }

 @Test
 void lowerValueReplacementRefundsOnlyTheDifferenceOnMySql() {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),2,"MYSQL-EX-LOW-OPEN-"+UUID.randomUUID(),null,"Lower exchange original stock");
  PosSaleResponse original=checkout(f,"MYSQL-EX-LOW-SALE-"+UUID.randomUUID());
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  ProductVariant lower=createReplacementVariant("LOW-"+UUID.randomUUID(),new BigDecimal("60.00"));
  inventoryService.receiveStock(f.locationId(),lower.getId(),1,"MYSQL-EX-LOW-NEW-"+UUID.randomUUID(),null,"Lower exchange replacement stock");
  PosSaleItemRequest replacement=new PosSaleItemRequest();
  replacement.setProductId(lower.getProduct().getId()); replacement.setVariantId(lower.getId()); replacement.setQuantity(1);
  PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Lower value exchange","MYSQL-EX-LOW-KEY-"+UUID.randomUUID(),
   List.of(new PosReturnItemRequest(originalLine,1,"RESTOCK",null)),List.of(replacement),
   new BigDecimal("0.00"),List.of(),List.of(new PosRefundPaymentRequest("CASH",new BigDecimal("40.00"),null)));

  PosExchangeResponse result=exchangeService.exchange(original.id(),request,f.username(),"CASHIER");
  assertEquals(new BigDecimal("60.00"),result.creditApplied());
  assertEquals(new BigDecimal("0.00"),result.amountCollected());
  assertEquals(new BigDecimal("40.00"),result.actualRefund());
  assertTrue(payments.findBySaleId(result.replacementSaleId()).isEmpty(),
   "Exchange credit must not be misreported as a cash/card/UPI tender");
  PosReturn returnRecord=returns.findBySaleIdOrderByCreatedAtDesc(original.id()).get(0);
  assertEquals(new BigDecimal("100.00"),returnRecord.getRefundTotal(),
   "Sales reversal is the full returned value, not only the cash refund");
  List<PosCashMovement> cashOut=cashMovements.findByShiftIdOrderByCreatedAtAscIdAsc(f.shiftId()).stream()
   .filter(m->m.getIdempotencyKey().startsWith("POS-EXCHANGE-CASH-OUT:")).toList();
  assertEquals(1,cashOut.size(),"Only the actual cash refund should leave the drawer");
  assertEquals("OUT",cashOut.get(0).getDirection());
  assertEquals(new BigDecimal("40.00"),cashOut.get(0).getAmount());
 }

 @Test
 void partialExchangesAllocateOriginalSaleDiscountAndFinalRemainderOnMySql() {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),4,"MYSQL-EX-PARTIAL-OPEN-"+UUID.randomUUID(),null,"Partial exchange original stock");
  PosCheckoutRequest saleRequest=new PosCheckoutRequest();
  saleRequest.setLocationId(f.locationId()); saleRequest.setShiftId(f.shiftId());
  saleRequest.setIdempotencyKey("MYSQL-EX-PARTIAL-SALE-"+UUID.randomUUID());
  saleRequest.setDiscountAmount(new BigDecimal("20.00"));
  PosSaleItemRequest soldLine=new PosSaleItemRequest();
  soldLine.setProductId(f.productId()); soldLine.setVariantId(f.variantId()); soldLine.setQuantity(2);
  saleRequest.setItems(List.of(soldLine));
  PosPaymentRequest originalPayment=new PosPaymentRequest();
  originalPayment.setPaymentMethod("CASH"); originalPayment.setAmount(new BigDecimal("180.00"));
  originalPayment.setTenderedAmount(new BigDecimal("180.00")); saleRequest.setPayments(List.of(originalPayment));
  PosSaleResponse original=checkoutService.checkout(saleRequest,f.username(),"MANAGER");
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  ProductVariant replacementVariant=createReplacementVariant("PARTIAL-"+UUID.randomUUID(),new BigDecimal("90.00"));
  inventoryService.receiveStock(f.locationId(),replacementVariant.getId(),2,"MYSQL-EX-PARTIAL-NEW-"+UUID.randomUUID(),null,"Partial exchange replacement stock");

  PosExchangeResponse first=partialExchange(f,original.id(),originalLine,replacementVariant,"MYSQL-EX-PARTIAL-KEY-1-"+UUID.randomUUID());
  PosExchangeResponse second=partialExchange(f,original.id(),originalLine,replacementVariant,"MYSQL-EX-PARTIAL-KEY-2-"+UUID.randomUUID());

  assertEquals(new BigDecimal("90.00"),first.returnedValue(),
   "A 10% original sale discount must be allocated proportionally to the first returned unit");
  assertEquals(new BigDecimal("90.00"),second.returnedValue(),
   "The final returned unit must receive the exact remaining discounted value");
  assertEquals(new BigDecimal("90.00"),first.creditApplied());
  assertEquals(new BigDecimal("90.00"),second.creditApplied());
  List<PosReturn> allReturns=returns.findBySaleIdOrderByCreatedAtDesc(original.id());
  assertEquals(2,allReturns.size());
  assertEquals(new BigDecimal("180.00"),allReturns.stream().map(PosReturn::getRefundTotal)
   .reduce(BigDecimal.ZERO,BigDecimal::add));
 }

 @Test
 void concurrentExchangesCannotReturnTheSameSoldUnitTwiceOnMySql() throws Exception {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),2,"MYSQL-EX-CONCURRENT-OPEN-"+UUID.randomUUID(),null,"Concurrent exchange opening stock");
  PosSaleResponse original=checkout(f,"MYSQL-EX-CONCURRENT-SALE-"+UUID.randomUUID());
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  ExecutorService executor=Executors.newFixedThreadPool(2);
  CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
  try {
   Future<Outcome> first=executor.submit(()->exchangeOne(f,original.id(),originalLine,ready,start));
   Future<Outcome> second=executor.submit(()->exchangeOne(f,original.id(),originalLine,ready,start));
   assertTrue(ready.await(15,TimeUnit.SECONDS),"Both MySQL exchange workers should be ready");
   start.countDown();
   Outcome a=first.get(30,TimeUnit.SECONDS), b=second.get(30,TimeUnit.SECONDS);
   long successful=List.of(a,b).stream().filter(Outcome::successful).count();
   long rejected=List.of(a,b).stream().filter(o->o.failure() instanceof IllegalArgumentException).count();
   assertEquals(1,successful,"Only one exchange may claim the sold unit");
   assertEquals(1,rejected,"The competing exchange must observe the committed return quantity");
   assertEquals(1,exchanges.findByOriginalSaleIdOrderByCreatedAtDesc(original.id()).size());
   assertEquals(1,returns.findBySaleIdOrderByCreatedAtDesc(original.id()).size());
   InventoryBalance balance=balances.findByLocationIdAndVariantId(f.locationId(),f.variantId()).orElseThrow();
   assertEquals(1,balance.getOnHandQuantity(),"The single exchange must restock and resell exactly one unit");
  } finally {
   start.countDown();
   executor.shutdownNow();
  }
 }

 private Outcome exchangeOne(Fixture f,Long saleId,Long saleItemId,CountDownLatch ready,CountDownLatch start) {
  ready.countDown();
  try {
   if(!start.await(15,TimeUnit.SECONDS)) return new Outcome(false,new TimeoutException("Exchange start signal timed out"));
   PosSaleItemRequest replacement=new PosSaleItemRequest();
   replacement.setProductId(f.productId()); replacement.setVariantId(f.variantId()); replacement.setQuantity(1);
   PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Concurrent MySQL exchange quantity test",
    "MYSQL-EX-CONCURRENT-KEY-"+UUID.randomUUID(),
    List.of(new PosReturnItemRequest(saleItemId,1,"RESTOCK",null)),List.of(replacement),
    new BigDecimal("0.00"),List.of(),List.of());
   exchangeService.exchange(saleId,request,f.username(),"CASHIER");
   return new Outcome(true,null);
  } catch(Throwable failure) { return new Outcome(false,failure); }
 }

 @Test
 void insufficientReplacementStockRollsBackExchangeOnMySql() {
  Fixture f=fixture();
  inventoryService.receiveStock(f.locationId(),f.variantId(),1,"MYSQL-EX-ROLLBACK-OPEN-"+UUID.randomUUID(),null,"Exchange rollback opening stock");
  PosSaleResponse original=checkout(f,"MYSQL-EX-ROLLBACK-SALE-"+UUID.randomUUID());
  Long originalLine=saleItems.findBySaleId(original.id()).get(0).getId();
  PosSaleItemRequest replacement=new PosSaleItemRequest();
  replacement.setProductId(f.productId()); replacement.setVariantId(f.variantId()); replacement.setQuantity(1);
  long salesBefore=sales.count();
  String key="MYSQL-EX-ROLLBACK-KEY-"+UUID.randomUUID();
  PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Rollback failed MySQL exchange",key,
   List.of(new PosReturnItemRequest(originalLine,1,"DAMAGED","Damaged item")),List.of(replacement),
   new BigDecimal("0.00"),List.of(),List.of());

  assertThrows(InsufficientStockException.class,
   ()->exchangeService.exchange(original.id(),request,f.username(),"CASHIER"));
  assertTrue(returns.findBySaleIdOrderByCreatedAtDesc(original.id()).isEmpty(),
   "Failed exchange must roll back the return");
  assertTrue(exchanges.findByIdempotencyKey(key).isEmpty(),
   "Failed exchange must roll back its exchange header");
  assertEquals(salesBefore,sales.count(),"Failed exchange must not leave a replacement sale");
  InventoryBalance balance=balances.findByLocationIdAndVariantId(f.locationId(),f.variantId()).orElseThrow();
  assertEquals(0,balance.getOnHandQuantity(),"Failed replacement sale must not change stock");
 }

 private PosExchangeResponse partialExchange(Fixture f,Long saleId,Long saleItemId,ProductVariant replacementVariant,String key) {
  PosSaleItemRequest replacement=new PosSaleItemRequest();
  replacement.setProductId(replacementVariant.getProduct().getId());
  replacement.setVariantId(replacementVariant.getId()); replacement.setQuantity(1);
  PosExchangeRequest request=new PosExchangeRequest(f.shiftId(),"Partial discounted exchange",key,
   List.of(new PosReturnItemRequest(saleItemId,1,"RESTOCK",null)),List.of(replacement),
   new BigDecimal("0.00"),List.of(),List.of());
  return exchangeService.exchange(saleId,request,f.username(),"CASHIER");
 }

 private ProductVariant createReplacementVariant(String suffix,BigDecimal price) {
  Product product=new Product(); product.setName("MySQL Replacement "+suffix); product.setPrice(price);
  product.setStockQuantity(0); product.setActive(true); product=products.saveAndFlush(product);
  ProductVariant variant=new ProductVariant(); variant.setProduct(product); variant.setSku("EX-"+suffix);
  variant.setVariantName("Replacement "+suffix); variant.setAttributes(Map.of("color","Brown"));
  variant.setActive(true); return variants.saveAndFlush(variant);
 }

 private PosSaleResponse checkout(Fixture f,String key) {
  PosCheckoutRequest request=new PosCheckoutRequest();
  request.setLocationId(f.locationId()); request.setShiftId(f.shiftId()); request.setIdempotencyKey(key);
  request.setDiscountAmount(new BigDecimal("0.00"));
  PosSaleItemRequest item=new PosSaleItemRequest();
  item.setProductId(f.productId()); item.setVariantId(f.variantId()); item.setQuantity(1);
  request.setItems(List.of(item));
  PosPaymentRequest payment=new PosPaymentRequest();
  payment.setPaymentMethod("CASH"); payment.setAmount(new BigDecimal("100.00")); payment.setTenderedAmount(new BigDecimal("100.00"));
  request.setPayments(List.of(payment));
  return checkoutService.checkout(request,f.username());
 }

 private Fixture fixture() {
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
  String username="mysql-exchange-"+suffix;
  Admin admin=new Admin(); admin.setUsername(username); admin.setPassword("test-only-password"); admins.saveAndFlush(admin);
  InventoryLocation location=new InventoryLocation(); location.setCode("EX-"+suffix); location.setName("MySQL Exchange "+suffix);
  location.setActive(true); location=locations.saveAndFlush(location);
  PosRegisterResponse register=shiftService.createRegister(new PosRegisterCreateRequest(location.getId(),"EX-"+suffix,"Exchange Register"));
  Long shift=shiftService.openShift(new PosShiftOpenRequest(register.id(),new BigDecimal("100.00"),"MYSQL-EX-SHIFT-"+suffix),username).id();
  Product product=new Product(); product.setName("MySQL Exchange Frame "+suffix); product.setPrice(new BigDecimal("100.00"));
  product.setStockQuantity(0); product.setActive(true); product=products.saveAndFlush(product);
  ProductVariant variant=new ProductVariant(); variant.setProduct(product); variant.setSku("EX-"+suffix);
  variant.setVariantName("Exchange test variant"); variant.setAttributes(Map.of("color","Black")); variant.setActive(true);
  variant=variants.saveAndFlush(variant);
  return new Fixture(location.getId(),product.getId(),variant.getId(),shift,username);
 }
}
