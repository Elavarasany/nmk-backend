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
  InventoryBalance balance=balances.findByLocationIdAndVariantId(f.locationId(),f.variantId()).orElseThrow();
  assertEquals(1,balance.getOnHandQuantity(),"Restock and replacement sale must both commit");
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
