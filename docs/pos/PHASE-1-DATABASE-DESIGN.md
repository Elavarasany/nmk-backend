# NMK Opticals POS — Phase 1 Database Foundation

**Status:** Design document; no schema or application code has been changed by this document.  
**Repository branch:** `master`  
**Scope:** Plan the database foundation for connected in-store POS while preserving the existing online checkout flow.

## 1. Goals and non-goals

### Goals

- Keep one Spring Boot backend and one MySQL database for the website, admin dashboard and physical-store POS.
- Reuse the existing product catalogue.
- Introduce shared customer profiles without rewriting historical order data.
- Introduce separate physical-store sales and sale items.
- Establish auditable inventory movements and a safe way to represent online reservations.
- Preserve payment, invoice, return and prescription history.
- Plan a controlled migration with a rollback and reconciliation strategy.

### Non-goals for this phase

- No POS UI or API implementation.
- No production database changes.
- No automatic conversion of existing orders into sales.
- No destructive changes to existing tables.
- NMK is currently **not GST-registered**. The initial POS design must not assume a GSTIN or apply GST as though NMK were registered. Keep the sales and invoice model extensible so applicable tax handling can be introduced if registration status changes. Confirm the legally appropriate invoice format and other applicable obligations before launch.

## 2. Current implementation inspected

The following existing backend files were inspected on the `master` branch:

- `entity/Product.java`
- `entity/Order.java`
- `entity/OrderItem.java`
- `entity/Customer.java`
- `entity/RazorpayWebhookEvent.java`
- `repository/ProductRepository.java`
- `repository/OrderRepository.java`
- `service/OrderService.java`
- `service/PaymentVerificationService.java`
- `service/RazorpayService.java`
- `controller/RazorpayController.java`
- `controller/OrderController.java`
- `controller/AdminOrderController.java`
- `controller/ProductController.java`
- `service/StockReservationScheduler.java`
- `src/main/resources/application.properties`

### Existing product and stock behaviour

- `products` currently contains a `stockQuantity` integer.
- `ProductRepository.reserveStock(...)` atomically subtracts quantity if the product is active and sufficient stock is available.
- `ProductRepository.releaseStock(...)` adds quantity back.
- `OrderService.createPendingOrder(...)` combines duplicate product lines, reserves stock, and marks the order as stock-reserved with a 15-minute expiry.
- `OrderService.releaseReservationForOrder(...)` restores the quantities and marks the order cancelled/failed.
- `PaymentVerificationService.confirmPayment(...)` locks the order, rejects confirmation without an active reservation, marks payment paid, and changes `stockReserved` to false without deducting stock a second time.
- `RazorpayController` verifies the Razorpay signature and checks captured payment, order ID and amount before confirming.
- `Customer.java` is currently an empty class.
- Online customer name, email, phone and address are stored as fields on `customer_orders`.
- `order_items` stores product ID, product-name snapshot, quantity, price and prescription-related fields.
- `application.properties` currently uses Hibernate `ddl-auto=update`; a controlled migration approach is required before production schema evolution.

### Important stock interpretation

The current reservation implementation deducts reserved units from `products.stockQuantity` at checkout. On payment confirmation, the deduction remains and the reservation flag is cleared. On cancellation/expiry, the deduction is reversed.

Therefore, the current `stockQuantity` should not be assumed to mean the shop's physical on-hand quantity. At a given moment it is closer to stock available after active online reservations, subject to successful cleanup and reconciliation.

**Do not initialize a new on-hand inventory quantity by blindly copying the current `stockQuantity`.** Before migration, reconcile:
1. Physical count in the shop.
2. Current `products.stockQuantity`.
3. All orders with `stockReserved = true`.
4. Paid/fulfilled order history and any pending cleanup work.
5. Any manual stock adjustments not represented in the database.

The scheduled cleanup entry point has now been located in `service/StockReservationScheduler.java`. It runs every 60 seconds, queries expired reservation IDs, checks the corresponding Razorpay order/payment attempts before releasing stock, and retains the reservation if the payment state is unsettled, successful or cannot be established. Before release it locks the order and rechecks `stockReserved`, pending payment status and expiry, then calls the existing order-service release path. Preserve this fail-closed behaviour during the inventory migration.

### Additional stock and transaction audit findings

- `controller/OrderController.java` creates a pending order/reserves stock before creating the Razorpay order. If payment-order creation fails, it attempts to release the reservation. The failure path should remain idempotent and be covered by integration tests.
- `controller/AdminOrderController.java` routes cancellation of eligible unpaid pending orders through `OrderService`, but paid-order cancellation is rejected because a refund workflow is not implemented. Preserve this restriction until returns/refunds are built.
- `controller/ProductController.java` exposes product creation that saves directly through `ProductRepository`, bypassing `ProductService` validation. Product creation should eventually be routed through one validated service path.
- Product administration currently allows stock quantity to be changed through product updates. This is not an adequate long-term inventory workflow: stock changes must become explicit, authorized adjustments with a reason and a stock-movement record.
- The inspected admin order controller includes a clear-all-orders operation. Historical financial/order records should not be routinely deleted; replace destructive history-clearing workflows with controlled retention/archival and auditable operations before production POS history is introduced.
- No dedicated stock movement ledger exists in the inspected entity set. The new ledger must be written atomically with every inventory change.

The scheduler checks Razorpay before releasing stock, but it is not a substitute for reconciliation. Monitoring and operational handling are still needed for reservations that remain held because Razorpay is unavailable or the payment state is ambiguous.

## 3. Target conceptual model

### Shared customer records

#### `customers`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `name VARCHAR(150)`
- `phone VARCHAR(20)`
- `email VARCHAR(254)`
- `created_at DATETIME NOT NULL`
- `updated_at DATETIME NOT NULL`
- `active BOOLEAN NOT NULL DEFAULT TRUE`

Phone is the primary lookup key for POS workflows, but the final uniqueness rule must account for missing/blank phone numbers and normalized formats. Do not assume every walk-in customer must be registered. A walk-in sale may have a null customer reference.

#### `customer_addresses`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `customer_id BIGINT NOT NULL`
- `address_line VARCHAR(1000)`
- `city VARCHAR(120)`
- `state VARCHAR(120)`
- `pincode VARCHAR(20)`
- `is_default BOOLEAN NOT NULL DEFAULT FALSE`
- `created_at DATETIME NOT NULL`

Relationship: one customer to many addresses.

### Online orders remain online orders

Keep the existing `customer_orders` and `order_items` tables and their current payment/tracking workflow.

Potential additive change after migration design is approved:

- Add nullable `customer_id` to `customer_orders`.
- Preserve the existing customer name, phone, email and address fields as historical order-time snapshots.
- Do not require every existing order to be linked to a customer.
- Do not convert online orders into POS sales.

### Physical-store sales

#### `sales`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `invoice_number VARCHAR(50) NOT NULL UNIQUE`
- `customer_id BIGINT NULL`
- `subtotal DECIMAL(12,2) NOT NULL`
- `discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `tax_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `total_amount DECIMAL(12,2) NOT NULL`
- `sale_status VARCHAR(30) NOT NULL`
- `sold_at DATETIME NOT NULL`
- `created_by BIGINT NULL`
- `notes VARCHAR(1000) NULL`
- `created_at DATETIME NOT NULL`

A sale can be a walk-in sale without a customer profile. Invoice numbering and tax fields must follow the business's confirmed invoice requirements.

#### `sale_items`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `sale_id BIGINT NOT NULL`
- `product_id BIGINT NULL`
- `product_name VARCHAR(255) NOT NULL`
- `sku VARCHAR(100) NULL`
- `quantity INT NOT NULL`
- `unit_price DECIMAL(12,2) NOT NULL`
- `discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `tax_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `line_total DECIMAL(12,2) NOT NULL`

The product name, SKU and unit price are sale-time snapshots. Product deletion or later catalogue edits must not rewrite an old invoice. Decide the product foreign-key deletion policy before implementing it; historical sale items must remain readable.

### Payments

#### `payments`

Initial POS-oriented proposal:

- `id BIGINT PRIMARY KEY`
- `sale_id BIGINT NOT NULL`
- `payment_method VARCHAR(30) NOT NULL` — CASH, UPI, CARD, or another explicitly supported method
- `amount DECIMAL(12,2) NOT NULL`
- `payment_status VARCHAR(30) NOT NULL`
- `reference_number VARCHAR(150) NULL`
- `paid_at DATETIME NULL`
- `created_at DATETIME NOT NULL`

Multiple payment rows per sale support split payments. Payment rows need idempotency/reference constraints appropriate to the payment provider. Do not mix POS payment rows into the existing Razorpay online payment flow without a separate design review.

### Inventory

#### `inventory`

One summary row per product initially:

- `id BIGINT PRIMARY KEY`
- `product_id BIGINT NOT NULL UNIQUE`
- `on_hand_quantity INT NOT NULL`
- `reserved_quantity INT NOT NULL DEFAULT 0`
- `reorder_level INT NOT NULL DEFAULT 0`
- `updated_at DATETIME NOT NULL`

Available quantity is calculated as:

`available_quantity = on_hand_quantity - reserved_quantity`

The POS must not sell reserved online stock.

#### `stock_movements`

Append-only stock audit ledger:

- `id BIGINT PRIMARY KEY`
- `product_id BIGINT NOT NULL`
- `movement_type VARCHAR(40) NOT NULL`
- `quantity INT NOT NULL`
- `reference_type VARCHAR(40) NULL`
- `reference_id BIGINT NULL`
- `notes VARCHAR(1000) NULL`
- `created_by BIGINT NULL`
- `created_at DATETIME NOT NULL`

Movement types should be explicit, for example opening balance, purchase receipt, POS sale, online sale, return restock, damage/write-off and manual adjustment. Define whether quantity is stored signed or as a positive quantity plus direction before coding; use one convention consistently.

Every inventory-changing transaction must update the summary and write its movement record in the same database transaction.

### Prescriptions and private files

A future `prescriptions` table should link prescription data to a customer and to either an online order item or a POS sale item. Store structured eye values in typed columns or validated JSON according to the final prescription model.

Prescription images/documents belong in private Oracle Object Storage. Store an object key and metadata in MySQL, not Oracle credentials or image bytes. Access must be authorized by the backend; use short-lived access URLs where appropriate. Do not make prescription buckets public.

### Returns and refunds

Proposed future tables:

- `returns`: original sale, return number, reason, status, refund amount, timestamps and staff reference.
- `return_items`: original sale item, returned quantity, refund amount and quantity approved for restock.

Never delete or overwrite the original sale to process a return. Record the return/refund as a separate auditable transaction. Restock only items physically inspected and approved for resale.

### Suppliers and purchasing

Later additions:

- `suppliers`
- `purchase_orders`
- `purchase_order_items`
- Goods-receipt records

Receiving stock should increase on-hand inventory and write stock movements in the same transaction.

## 4. Relationships

- `customers 1 -> many customer_addresses`
- `customers 1 -> many customer_orders` (nullable link; preserve historical snapshots)
- `customers 1 -> many sales` (nullable for walk-ins)
- `sales 1 -> many sale_items`
- `sales 1 -> many payments`
- `products 1 -> 1 inventory`
- `products 1 -> many stock_movements`
- `products 1 -> many sale_items` (nullable or restricted deletion policy; preserve item snapshots)
- `sale_items 1 -> many return_items`
- Prescriptions may reference an online order item or a POS sale item, with a constraint ensuring the intended ownership model.

## 5. Required integrity and concurrency rules

1. Use MySQL InnoDB and foreign keys for relational integrity.
2. Use `DECIMAL(12,2)` for money; never use floating-point money fields.
3. Add unique constraints for invoice numbers and product SKU/barcode values where present.
4. Normalize phone numbers before customer matching; define a safe policy for duplicate or missing phone values.
5. Use a transaction for sale creation, payment recording, stock deduction and stock movement insertion.
6. Lock inventory rows or use guarded atomic updates so concurrent cashiers cannot sell the same remaining unit.
7. Check available stock, not just on-hand stock.
8. Make payment confirmation and sale submission idempotent; repeated requests must not duplicate payments, invoices or stock movements.
9. Keep stock movements and financial transaction history auditable; do not silently overwrite or delete history.
10. Define refund, cancellation and restock transitions explicitly.
11. Add indexes for customer phone, sale invoice number, sale date/status, payment references, product SKU/barcode, stock movement product/date and order/customer lookup.
12. Use database constraints for non-negative quantities and valid amounts where supported and practical.

## 6. Safe migration sequence — proposed, not executed

1. Confirm the invoice format and applicable legal requirements for a business that is currently not GST-registered. Do not calculate or represent GST as collected unless the business becomes registered and the rules are updated.
2. Finish auditing the online reservation cleanup/scheduler path and all stock-changing code paths.
3. Back up the current database and verify that a restore is possible.
4. Take a physical stock count and reconcile it against database stock and active online reservations.
5. Agree on the final schema and migration tool/versioning strategy (prefer Flyway or Liquibase over relying on Hibernate `ddl-auto=update` for production changes).
6. Create additive tables and nullable columns only; do not drop or rename existing production columns in the first migration.
7. Initialize inventory from a verified reconciliation snapshot, not by blindly copying `products.stockQuantity`.
8. Record opening balances and reservation balances with a documented reconciliation reference.
9. Link existing orders to customer profiles only where matching is reliable; retain all original customer snapshot fields.
10. Test online checkout, payment success, duplicate verification, cancellation, expiry, POS sale, split payment, concurrent sale attempts and rollback against a staging database.
11. Compare stock totals and transaction counts before and after migration.
12. Deploy only after backups, staging tests and restore verification pass.

### Rollback principles

- Never roll back by deleting transaction history.
- Prefer additive schema changes.
- If application deployment must be reverted, retain backward-compatible columns/tables until the old version no longer needs them.
- Restore production data only from a verified backup and with a documented recovery decision.
- Do not delete the old database or old image store until migration and recovery have been verified.

## 7. Open decisions before implementation

- NMK is currently not GST-registered. Confirm the required non-GST invoice wording/format with the business's accountant or appropriate adviser; revisit tax configuration if registration status changes.
- Should customer phone numbers be mandatory for creating a saved customer profile? Walk-in sales should still be possible without one.
- What is the desired invoice prefix and numbering policy?
- Which staff roles are required initially, and how do they map to the existing admin authentication model?
- What are the physical stock count and outstanding online reservations at cutover?
- The expired-reservation scheduler is `StockReservationScheduler`; verify its runtime scheduling configuration and add monitoring/test coverage before production cutover.
- Are multiple branches/locations required? Initial design assumes one shop location but should avoid blocking future expansion.

## 8. Immediate next audit task

Before creating entities or migrations, locate and inspect:
- The scheduled job that calls expired reservation cleanup.
- Every service/controller that changes `stockQuantity`.
- The order status transitions after successful payment, cancellation, refund and fulfilment.
- Existing admin/customer endpoints and authentication/role structure.
- Product SKU/barcode fields and product variant/size representation.

**No application code, schema, database records, credentials, deployment settings or other repository files are changed by this design document.**

## 9. Detailed proposed relational schema (review baseline)

This is a logical schema baseline for review, not an executable migration. Final DDL must be validated against the deployed MySQL version and existing data.

### 9.1 Common conventions

- Use InnoDB for transactional tables and BIGINT auto-increment primary keys.
- Use DATETIME(6) consistently for audit timestamps.
- Use DECIMAL(12,2) for INR money; never FLOAT or DOUBLE.
- Use integer quantities for discrete stock units.
- Validate statuses in the service layer and add database CHECK constraints where supported.
- Never store card numbers, CVV, UPI PINs, payment credentials or provider secrets.

### 9.2 customers

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK, auto-increment |
| name | VARCHAR(150) | Nullable for anonymous walk-in |
| phone | VARCHAR(20) | Nullable |
| phone_normalized | VARCHAR(20) | Nullable; normalized lookup value |
| email | VARCHAR(254) | Nullable |
| active | BOOLEAN | NOT NULL, default TRUE |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

Initially index phone_normalized without enforcing uniqueness. Review duplicate and missing phone handling before adding a unique constraint. Walk-in sales must not require a saved customer.

### 9.3 customer_addresses

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| customer_id | BIGINT | NOT NULL, FK to customers |
| address_line | VARCHAR(1000) | Nullable |
| city | VARCHAR(120) | Nullable |
| state | VARCHAR(120) | Nullable |
| pincode | VARCHAR(20) | Nullable; string preserves leading zeroes |
| is_default | BOOLEAN | NOT NULL, default FALSE |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

Index customer_id and is_default. Enforce at most one default address per customer in a service transaction.

### 9.4 inventory_locations

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| code | VARCHAR(30) | NOT NULL, UNIQUE |
| name | VARCHAR(120) | NOT NULL |
| active | BOOLEAN | NOT NULL, default TRUE |
| created_at | DATETIME(6) | NOT NULL |

Seed one initial location for the Avalurpet shop. This location dimension avoids a redesign if a second branch is added later; multi-branch UI is not part of the initial scope.

### 9.5 inventory

One row per product per location.

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| product_id | BIGINT | NOT NULL, FK to products |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| on_hand_quantity | INT | NOT NULL, >= 0 |
| reserved_quantity | INT | NOT NULL, default 0, >= 0 |
| reorder_level | INT | NOT NULL, default 0, >= 0 |
| version | BIGINT | NOT NULL, default 0; optional optimistic lock |
| updated_at | DATETIME(6) | NOT NULL |

Unique constraint: product_id + location_id. Available quantity is calculated as on_hand_quantity minus reserved_quantity; do not store it as an independently editable field. Enforce reserved_quantity <= on_hand_quantity in service logic and with a CHECK constraint where supported.

### 9.6 stock_movements

Append-only audit ledger. Correct mistakes with a new reversing movement, never by editing old movement rows.

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| product_id | BIGINT | NOT NULL, FK to products |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| movement_type | VARCHAR(40) | NOT NULL |
| quantity_delta | INT | NOT NULL, signed; positive adds on-hand, negative removes |
| on_hand_after | INT | NOT NULL |
| reserved_delta | INT | NOT NULL, default 0; positive reserves, negative releases |
| reserved_after | INT | NOT NULL |
| reference_type | VARCHAR(40) | Nullable, e.g. POS_SALE, ONLINE_ORDER, PURCHASE_RECEIPT, RETURN, ADJUSTMENT |
| reference_id | BIGINT | Nullable |
| idempotency_key | VARCHAR(100) | Nullable; unique when supplied |
| reason | VARCHAR(500) | Required for manual adjustments/write-offs |
| created_by | BIGINT | Nullable until staff model is finalized |
| created_at | DATETIME(6) | NOT NULL |

Indexes: product_id + location_id + created_at; reference_type + reference_id. Use a unique index for supplied idempotency keys after validating MySQL nullable-unique behaviour. Update inventory summary and insert its movement in the same transaction.

Movement types include OPENING_BALANCE, POS_SALE, ONLINE_RESERVATION, ONLINE_RESERVATION_RELEASE, ONLINE_ORDER_FULFILLED, PURCHASE_RECEIPT, RETURN_RESTOCK, DAMAGE_WRITE_OFF and MANUAL_ADJUSTMENT.

Important: current online checkout deducts products.stockQuantity when reserving. The new model must not reserve inventory and deduct it again at payment. Design the reservation-to-fulfilment transition against the actual online order lifecycle before switching code.

### 9.7 sales

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| invoice_number | VARCHAR(50) | NOT NULL, UNIQUE, immutable once issued |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| customer_id | BIGINT | Nullable FK to customers |
| sale_status | VARCHAR(30) | NOT NULL; DRAFT, COMPLETED, VOIDED, PARTIALLY_RETURNED, RETURNED |
| subtotal | DECIMAL(12,2) | NOT NULL, >= 0 |
| discount_amount | DECIMAL(12,2) | NOT NULL, default 0, >= 0 |
| tax_amount | DECIMAL(12,2) | NOT NULL, default 0 |
| total_amount | DECIMAL(12,2) | NOT NULL, >= 0 |
| customer_name_snapshot | VARCHAR(150) | Nullable |
| customer_phone_snapshot | VARCHAR(20) | Nullable |
| notes | VARCHAR(1000) | Nullable |
| sold_at | DATETIME(6) | Nullable until completion |
| created_by | BIGINT | Nullable until staff identity model is finalized |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

Indexes: location_id + sold_at; customer_id + sold_at; sale_status + sold_at. Allocate an invoice number only on completion, unless an accountant-approved numbering policy requires otherwise.

NMK is currently not GST-registered. Do not require GSTIN, calculate or represent GST as collected, or hardcode a GST rate. Keep tax fields for future extensibility; initial tax behaviour should be zero unless qualified review identifies another applicable tax treatment. Confirm invoice wording and applicable obligations with the business's accountant/adviser before launch.

### 9.8 sale_items

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| sale_id | BIGINT | NOT NULL, FK to sales |
| product_id | BIGINT | Nullable FK to products |
| product_name_snapshot | VARCHAR(255) | NOT NULL |
| sku_snapshot | VARCHAR(100) | Nullable |
| barcode_snapshot | VARCHAR(100) | Nullable |
| quantity | INT | NOT NULL, > 0 |
| unit_price | DECIMAL(12,2) | NOT NULL, >= 0 |
| discount_amount | DECIMAL(12,2) | NOT NULL, default 0, >= 0 |
| tax_amount | DECIMAL(12,2) | NOT NULL, default 0 |
| line_total | DECIMAL(12,2) | NOT NULL, >= 0 |
| prescription_required | BOOLEAN | NOT NULL, default FALSE |
| prescription_data | JSON | Nullable; structured values only |
| created_at | DATETIME(6) | NOT NULL |

Index sale_id. Product name, SKU/barcode and price snapshots are authoritative for historic invoices. Prefer archiving products and restricting deletion once referenced.

### 9.9 sale_payments

This is the POS tender table and must remain separate from the existing Razorpay online payment flow.

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| sale_id | BIGINT | NOT NULL, FK to sales |
| payment_method | VARCHAR(30) | NOT NULL: CASH, UPI, CARD, OTHER |
| amount | DECIMAL(12,2) | NOT NULL, > 0 |
| payment_status | VARCHAR(30) | NOT NULL: PENDING, COMPLETED, FAILED, REFUNDED, PARTIALLY_REFUNDED |
| reference_number | VARCHAR(150) | Nullable provider/bank reference; no secrets |
| idempotency_key | VARCHAR(100) | Nullable, unique when supplied |
| paid_at | DATETIME(6) | Nullable until successful |
| created_at | DATETIME(6) | NOT NULL |

Index sale_id + payment_status and reference_number. Multiple completed rows support split tender. The sum of completed payments must equal the amount due before a sale is fully paid. A pending UPI/card initiation is not collected money. Record refunds separately; do not delete the original payment.

### 9.10 invoice_sequences

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| financial_year | VARCHAR(9) | NOT NULL; India financial-year format to be finalized |
| prefix | VARCHAR(20) | NOT NULL |
| next_number | BIGINT | NOT NULL, > 0 |
| updated_at | DATETIME(6) | NOT NULL |

Unique constraint: location_id + financial_year + prefix. Allocate numbers by locking the sequence row inside the sale transaction. Never use MAX(invoice_number) + 1 or an unlocked counter. Confirm numbering and cancellation treatment with the accountant/adviser.

### 9.11 returns and return_items

returns:
- id BIGINT PK; return_number VARCHAR(50) NOT NULL UNIQUE; sale_id BIGINT NOT NULL FK to sales.
- return_status VARCHAR(30) NOT NULL: REQUESTED, APPROVED, REJECTED, COMPLETED.
- reason VARCHAR(500) NOT NULL; refund_amount DECIMAL(12,2) NOT NULL default 0.
- created_by BIGINT and approved_by BIGINT nullable until staff model is finalized.
- created_at DATETIME(6) NOT NULL; completed_at DATETIME(6) nullable.

return_items:
- id BIGINT PK; return_id BIGINT NOT NULL FK to returns; sale_item_id BIGINT NOT NULL FK to sale_items.
- quantity INT NOT NULL and > 0; restock_quantity INT NOT NULL default 0 and <= quantity.
- refund_amount DECIMAL(12,2) NOT NULL and >= 0; inspection_notes VARCHAR(500) nullable.

Completed returns across all return records must not exceed the originally sold quantity. Restock only after physical inspection and approval, using a separate inventory movement. Refund execution must be recorded and idempotent. A refund request is not a completed refund.

### 9.12 Product identifiers and deletion policy

The current Product entity has no dedicated SKU or barcode fields. Add nullable SKU and barcode only after reviewing catalogue data and defining legacy assignment. Enforce uniqueness for non-empty normalized values; do not treat repeated empty strings as valid identifiers. Barcode scanners can initially work as keyboard input.

Do not physically delete products referenced by sales, stock movements, purchase receipts or returns. Prefer deactivation/archive. Financial and inventory foreign-key deletion behaviour should be RESTRICT/NO ACTION; never cascade-delete transaction history.

### 9.13 Transaction boundaries and invariants

1. POS completion: lock inventory rows in stable product/location order; validate available quantities; write sale and items; record completed tender(s); decrement on-hand; write stock movements; allocate invoice number; commit atomically.
2. Split tender: payment rows and sale completion are atomic. A partially paid sale requires an explicitly designed receivable/credit policy.
3. Duplicate requests: require an idempotency key for sale submission and payment callbacks; retries return the original result rather than duplicate invoices, payments or movements.
4. Online reservation: reserve from the same shared inventory available balance. Preserve the existing Razorpay fail-closed expiry behaviour until a tested replacement is deployed.
5. Locking: use row-level locks or guarded atomic updates. For multi-product transactions, lock in deterministic order; retry known transient deadlocks only with idempotency.
6. Stock ledger: inventory summary and movement ledger update in the same transaction. Normal product-edit APIs must not overwrite inventory balances.
7. Returns: preserve original sale/payment; create separate return/refund records and stock movements.
8. Money: compute totals server-side using BigDecimal; validate discounts, line totals, payment sums and refund caps.
9. Authorization: enforce cashier/manager/admin permissions in backend services/controllers, not only the UI. Staff identity and role references need a separate authentication design review.
10. Audit: price overrides, discounts, manual stock adjustments, voids, returns and refunds must record actor, timestamp and reason.

### 9.14 Sign-off gates before implementation

- Confirm SKU/barcode assignment and whether product variants are separately stocked.
- Confirm staff/cashier roles and actor references.
- Confirm invoice numbering and invoice wording with the accountant/adviser.
- Confirm whether POS is cash-and-carry only or supports later collection/delivery.
- Define online stock transitions from reservation through payment, packing, dispatch, cancellation and refund.
- Perform physical stock count and reconcile every active online reservation at cutover.
- Choose Flyway or Liquibase and write explicit additive migrations; do not rely on Hibernate schema auto-update for production.
- Test rollback, concurrent checkout, duplicate submissions, payment retries, reservation expiry, partial returns and database restore in staging.

**Phase 1 schema status:** proposed for review only. No Java entities, repositories, controllers, SQL migration files, database records or production settings are changed by this section.


## 10. Confirmed business decisions and schema amendments

The following decisions were confirmed during Phase 1 review.

### 10.1 Variant-level stock

Each sellable product variant must have its own stock quantity. For example, different frame colours or sizes are distinct stock-keeping variants and must not share one quantity.

Add a `product_variants` table:

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| product_id | BIGINT | NOT NULL, FK to the existing products table |
| variant_code | VARCHAR(100) | NOT NULL; unique within a product |
| attributes | JSON | NOT NULL; structured values such as colour and size |
| barcode | VARCHAR(100) | Nullable; unique when supplied after normalization |
| is_active | BOOLEAN | NOT NULL, default true |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

A product with no selectable options still gets one default variant. This keeps inventory and POS sale lines consistent: every stock-tracked sale refers to a specific variant, never to an ambiguous parent product.

**Required amendment to Sections 9.5, 9.6 and 9.8:** inventory balances, stock movements and sale items must reference `variant_id` (FK to `product_variants`) rather than using only `product_id` as the stock identity. Keep `product_id` on sale-item snapshots if useful for reporting, but variant is the authoritative stock identity. Inventory uniqueness becomes location_id + variant_id. Stock movement records should retain both variant_id and a product/variant description snapshot where audit readability requires it.

Existing online order lines and the current `products.stockQuantity` model must be mapped to default or newly created variants during a controlled migration. Do not create variant rows or migrate balances blindly: first reconcile current product stock, physical count, and active online reservations. Online checkout and POS must then reserve/decrement the same variant-level inventory without double deduction.

### 10.2 POS payment policy

- A POS sale can be completed only when fully paid.
- Multiple tender types are allowed in one sale (split payment), such as cash plus UPI.
- Phase 1 does not support partial payment, customer credit, or an outstanding customer balance.
- Payment rows must be recorded individually, and the sum of successful/collected payments must equal the amount due before the sale is marked paid/completed.
- Customer credit/receivables are explicitly deferred to a later phase and must not be simulated by leaving a Phase 1 sale partially paid.

### 10.3 Invoice numbering

Invoice numbering restarts with a new sequence for each Indian financial year (April to March), and the sequence is scoped by inventory location and configured prefix. Example format for discussion: `NMK/26-27/0001`.

The `invoice_sequences` design in Section 9.10 applies: allocate the next number by locking the matching location + financial_year + prefix row inside the sale transaction. Never calculate the next invoice number using MAX + 1. Final invoice format, prefix, treatment of void/cancelled invoices, and applicable invoice wording must be checked with the accountant/adviser before production use.

### 10.4 Updated sign-off status

Confirmed: variant-level stock, full settlement with split tender, and financial-year invoice sequence reset.

Still requiring review before implementation: exact product/variant mapping from the current catalogue, staff roles and permissions, online reservation-to-fulfilment stock lifecycle, physical stock reconciliation, migration strategy, and accountant review of invoice format and wording.

**Phase 1 remains a design document only.** No application code, SQL migration, live database or production configuration is changed by these decisions.


### 10.5 Customer catalogue and variant selection

The confirmed storefront behaviour is **one product listing per parent product, with selectable variants** (colour/size) on the product detail or selection flow. Do not create a separate customer-facing product card for every colour/size combination by default.

- The parent product remains the catalogue and marketing identity (name, description, brand, category and shared imagery).
- Each sellable combination of attributes is a distinct `product_variants` row. Its attributes identify the exact option combination (for example, colour = Black and size = Medium).
- Each variant owns its own SKU/barcode and location-specific inventory balance. A customer must select an available variant before adding the item to the cart.
- The cart line, checkout request, online order item and POS sale item must carry the selected `variant_id`. Stock validation, reservation, release, fulfilment and deduction must all use that same variant ID.
- The product page should show only valid combinations that actually exist. Do not let the UI construct an unconfigured colour/size combination from independent option lists.
- Availability shown to customers must reflect sellable available quantity (on-hand minus active reservations), not raw on-hand quantity. The backend remains authoritative and must revalidate stock during reservation and sale completion.
- Products without selectable options receive one default variant, so every stock-controlled cart/order/sale line still resolves to a variant.
- The admin catalogue workflow must allow staff with appropriate permissions to create, edit, deactivate and identify variants, and to maintain stock per variant through auditable inventory operations. Ordinary product edits must not overwrite variant inventory.
- Barcode/SKU lookup in POS resolves directly to the exact variant. Searching by parent product may show its available variants for cashier selection.

#### Existing catalogue and online-order migration

The current implementation treats each product row as one stock identity: `products.stockQuantity` is stored on the parent product, size/colour are ordinary specification attributes, and existing `order_items` refer to `product_id` only. The migration must therefore be explicit and staged:

1. Create a default variant for each existing product as the safe initial mapping. Do not split a legacy product into multiple variants merely because its JSON specifications mention colour or size; first review catalogue data and determine whether those combinations are truly separately stocked.
2. For products confirmed to have multiple separately stocked combinations, create the required variant rows and map each physical quantity only after a physical count and reservation reconciliation.
3. Add a nullable variant reference to existing order items first. Backfill historical/current order lines to the appropriate default or reviewed variant while preserving their original product IDs and sale-time snapshots.
4. Update cart, checkout, order creation, stock reservation, reservation expiry/release, payment confirmation and fulfilment paths to use variant-level inventory consistently. Preserve the current Razorpay fail-closed reservation cleanup behaviour and ensure payment confirmation does not deduct reserved stock a second time.
5. Keep compatibility during rollout: do not remove or stop maintaining the legacy stock field until all application paths are migrated, reconciled and verified. Define a rollback point before switching the source of truth.
6. Verify existing online orders, pending reservations, duplicate payment verification, cancellation/expiry and POS sales against staging data before enabling variant-level stock in production.

**Important:** this decision changes both customer catalogue behaviour and the stock identity used by checkout/POS. It is not merely a visual selector. No frontend or backend implementation is included in this Phase 1 document update.


### 10.6 Initial POS staff roles and permissions

The initial POS permission model uses three roles. These roles are a starting authorization policy for the first shop location; they do not imply that the existing admin authentication implementation already supports staff accounts or these permissions. Inspect and design the authentication/authorization integration before implementation.

| Capability | OWNER_ADMIN | MANAGER | CASHIER |
|---|---|---|---|
| Access POS and create sales | Yes | Yes | Yes |
| Accept and record POS tender | Yes | Yes | Yes |
| View and reprint permitted invoices | Yes | Yes | Yes |
| View customer information | Full access | Operational access | Minimum details needed for the sale |
| View operational reports | Yes | Yes | No, unless explicitly permitted |
| Manage staff accounts and roles | Yes | No | No |
| Change business/POS settings | Yes | No | No |
| Create/edit/deactivate catalogue products and variants | Yes | As explicitly granted | No |
| Perform manual stock adjustments | Yes | Yes, with reason and audit record | No |
| Apply discounts | Yes | Within configured policy; approve exceptions | Only within a configured cashier limit |
| Approve discounts above cashier limit | Yes | Yes, within manager authority | No |
| Void a completed sale | Yes, with reason and audit record | Yes, with reason and audit record | No |
| Approve/perform returns and refunds | Yes, with reason and audit record | Yes, with reason and audit record | No |
| Delete or rewrite completed sales/payment history | No routine deletion; preserve audit history | No | No |

#### Authorization and audit requirements

- Enforce every permission in the Spring Boot backend service/controller layer. Hiding or disabling a frontend button is not authorization.
- Give every staff member an individual account. Do not use shared cashier or manager credentials. Staff identity must be attributable to every sale and sensitive action.
- A staff account must have an assigned role, an active/disabled state and a controlled account lifecycle. Owner/Admin alone can create staff accounts, assign roles and disable accounts.
- Cashiers may create and fully settle sales, record permitted tender types, and view/reprint invoices. They must not edit or delete completed sales, alter recorded payments, adjust stock, approve their own restricted actions, or issue refunds independently.
- Managers may perform operational approvals within their assigned authority. Actions such as stock adjustments, voids, returns and refunds require a reason and an audit record. A manager must not grant themselves Owner/Admin privileges.
- Discount limits must be configurable by the business; do not hardcode a percentage or amount in the schema. A discount exceeding the cashier's configured limit requires manager or Owner/Admin approval. Record the applied discount, approving staff member and reason when approval is required.
- Record actor, timestamp, action, affected sale/payment/variant, before-and-after values where relevant, and reason for sensitive operations including price overrides, discounts, stock adjustments, voids, returns and refunds.
- Never implement a permission path that silently deletes or rewrites completed financial history. Correct mistakes through explicit void, return, refund or reversing inventory transactions.
- The exact mapping to existing admin authentication, JWT claims, staff identity tables and endpoint permissions must be reviewed before coding. Do not assume the current single-admin login can safely represent multiple staff roles.

**Confirmed initial policy:** three roles — OWNER_ADMIN, MANAGER and CASHIER — with backend-enforced permissions, individual staff accounts and auditable sensitive actions. Exact discount thresholds and detailed endpoint-to-permission mapping remain implementation decisions.

**Phase 1 remains a design document only.** This section does not implement staff accounts, authentication changes, authorization code, database tables or UI.


### 10.7 POS fulfilment and collection workflow

The initial POS release supports both immediate take-away and paid sales collected later. Payment settlement and physical fulfilment are separate concepts: a sale may be fully paid while still awaiting preparation or customer collection.

#### Fulfilment data and statuses

Add a distinct `fulfilment_status` to the proposed `sales` record; do not overload `sale_status` or `payment_status`.

Initial fulfilment statuses:

- `PENDING_PREPARATION`: the sale is fully paid and recorded, but the items are not yet ready for handover.
- `READY_FOR_COLLECTION`: preparation is complete and the customer can collect the items.
- `COLLECTED`: the items have been handed over to the customer.
- `CANCELLED`: fulfilment has been cancelled. This status does not itself mean that payment has been refunded; any eligible refund must be a separate, recorded and idempotent refund transaction.

Add or provide equivalent auditable fields on the sale:

- `expected_collection_at DATETIME(6) NULL`
- `ready_for_collection_at DATETIME(6) NULL`
- `collected_at DATETIME(6) NULL`
- `collected_by BIGINT NULL` — staff actor who confirms handover
- `fulfilment_updated_at DATETIME(6) NOT NULL`

The sale's existing `created_by` identifies the staff member who records the sale. Record the staff actor and timestamp for every fulfilment status transition in an audit history; do not rely only on the latest status field.

#### Workflow rules

1. **Immediate collection:** after full payment is successfully recorded and the sale is committed, set fulfilment status to `COLLECTED` and record the handover timestamp and staff actor.
2. **Collect later:** after full payment and sale commit, set fulfilment status to `PENDING_PREPARATION`. An expected collection date/time may be recorded. When preparation is complete, an authorized staff member changes the status to `READY_FOR_COLLECTION`. At handover, staff confirm `COLLECTED` and the system records who handed over the items and when.
3. **Payment is independent:** every POS sale must still be fully settled before it is committed as a sale. Phase 1 continues to exclude partial payment, customer credit and outstanding balances. A paid sale awaiting collection is not an unpaid sale.
4. **Stock is deducted once:** decrement the relevant variant's on-hand inventory and write the corresponding stock movement atomically when the fully paid POS sale is recorded. Changing fulfilment status to ready or collected must never deduct stock again.
5. **Cancellation and refunds:** do not erase the sale, payment or stock movement. A cancellation after sale completion requires an explicit authorized cancellation/return decision and a separately recorded refund where applicable. Restock only after physical inspection and approval, using a reversing/return stock movement. Never infer that a cancelled fulfilment automatically restores stock or refunds money.
6. **Authorization and audit:** only authorized staff may change fulfilment status. Record actor, timestamp and reason for cancellation or exceptional status changes. Cashiers may confirm ordinary handover if permitted by the role policy; exceptions require manager or Owner/Admin authority.

Home delivery is deferred from the initial POS release. The initial workflow covers immediate handover at the shop and later customer collection at the shop. Partial fulfilment of individual lines is also deferred unless a later workflow decision explicitly requires it.

**Confirmed initial policy:** support immediate collection and fully paid collect-later sales; keep payment and fulfilment statuses separate; deduct stock once at sale recording; defer home delivery and partial payment/credit. No Java implementation, SQL migration or UI is included in this document update.

### 10.8 Shared inventory lifecycle for online orders and POS

Online checkout and physical POS sales must use one shared, variant-level inventory source of truth. The inventory balance is scoped by inventory location and product variant, as defined in Sections 10.1 and 9.5–9.6.

#### Inventory quantity meanings

- `on_hand_quantity` is the physical quantity currently held in sellable shop inventory, including units temporarily reserved for unpaid online checkout.
- `reserved_quantity` is the subset of on-hand units held for active online checkout/payment attempts.
- `available_quantity = on_hand_quantity - reserved_quantity`.
- All quantities must remain non-negative, and reserved quantity must never exceed on-hand quantity.

#### Required stock transitions

| Event | on_hand_quantity | reserved_quantity | available quantity |
|---|---:|---:|---:|
| Create a valid online checkout reservation | No change | Increase | Decrease |
| Confirm successful online payment | Decrease | Decrease by the same quantity | No further change |
| Safely release failed, cancelled or expired online reservation | No change | Decrease | Increase |
| Complete a fully paid POS sale | Decrease | No change | Decrease |
| Pack, mark ready, dispatch or hand over an already-paid online order | No change | No change | No change |
| Approve a physically inspected return for resale | Increase by approved resale quantity | No change | Increase |

1. **Online reservation:** lock or atomically update the relevant location/variant inventory row. Reserve only when available quantity is sufficient. Increase reserved quantity and write an idempotent reservation movement in the same database transaction. Do not reduce on-hand quantity at reservation time in this target model.
2. **Online payment success:** after verified provider payment, atomically decrease both on-hand and reserved quantities by the reserved amount, and write the corresponding fulfilment/sale movement. This converts the held units into sold units; it must not deduct the stock a second time while leaving the reservation in place. Payment confirmation and this conversion must be idempotent.
3. **Payment failure, cancellation or expiry:** release only the reserved quantity after the payment provider state is safely established as not paid. Do not increase on-hand quantity, because it was not reduced when the reservation was created. Write one idempotent reservation-release movement.
4. **Uncertain payment state:** preserve the existing Razorpay fail-closed behaviour. If Razorpay is unavailable, the attempt is unsettled, or payment state cannot be established, keep the reservation held for reconciliation. Never release stock merely because a timeout elapsed. Add operational monitoring and a safe reconciliation path for reservations that remain held.
5. **POS sale:** lock inventory rows in a deterministic order and permit sale only when on-hand minus reserved is sufficient. On successful, fully paid sale completion, decrease on-hand quantity and write the POS sale movement atomically with the sale and tender records. POS must never consume units reserved for online checkout.
6. **Fulfilment:** packing, preparation, ready-for-collection, dispatch, delivery and customer handover are fulfilment events, not additional stock deductions. The inventory deduction/conversion occurs once at the appropriate sale/payment commitment transition.
7. **Returns:** refund processing and inventory restocking are separate decisions. Increase on-hand quantity only for the quantity physically received, inspected and explicitly approved for resale. Record the return movement and approval actor; damaged or non-resalable units must not be returned to sellable stock.

#### Concurrency, ledger and integrity

- Every inventory transition must update the inventory summary and append its stock movement in the same database transaction.
- Use row locks or guarded atomic updates, stable lock ordering for multi-variant operations, and idempotency keys/reference constraints to prevent duplicate reservation, release, payment conversion, POS sale or return movements.
- Validate available quantity on the backend at the moment of reservation or POS completion; storefront/cart quantities are informational and are never authoritative.
- Keep an auditable reference to the originating online order/order item or POS sale/sale item, the location and variant, quantity deltas, resulting balances, actor/system source, timestamp and reason.
- Do not allow ordinary product or variant edit endpoints to overwrite inventory balances. Corrections must use authorized, reasoned stock-adjustment movements.
- If a payment callback or retry is received more than once, return the prior result and do not repeat inventory or ledger changes.

#### Migration and cutover from the current implementation

The existing online checkout currently subtracts reserved quantities directly from `products.stockQuantity` and restores them on safe reservation release. That legacy field therefore behaves like available stock while reservations are active; it is not equivalent to the target model's physical on-hand quantity.

Do not switch the source of truth by copying `products.stockQuantity` directly into `on_hand_quantity`. Before cutover:

1. Back up the database and verify a restore in a non-production environment.
2. Freeze or tightly control stock-changing operations for the cutover window.
3. Count physical stock by the final product variant and inventory location.
4. Identify and reconcile every active online reservation, pending payment, paid order and unresolved Razorpay attempt.
5. Derive and validate opening on-hand and reserved balances from the physical count and verified reservation/order state. Record the reconciliation snapshot and opening movements with a traceable reference.
6. Implement and test the new reservation, payment-conversion, safe-release and POS deduction transitions together. Preserve the fail-closed Razorpay scheduler behaviour throughout rollout.
7. Run staging tests for concurrent online checkout and POS sales, duplicate payment callbacks, provider outages, reservation expiry, cancellation, retries, returns and rollback.
8. Reconcile inventory totals, reservation totals, stock movement ledger and order/payment records before enabling the new inventory source of truth.
9. Retain a documented rollback point and compatibility plan. Do not remove or stop maintaining the legacy stock field until every application path has been migrated and verified.

**Confirmed Phase 1 policy:** website orders and POS sales share one variant/location inventory system; online checkout reserves available stock; verified payment converts the reservation into a stock deduction; safe failure/expiry releases only the reservation; POS deducts unreserved stock immediately on fully paid sale completion; and approved returns restock only after inspection. This is a target design, not a claim that the current implementation already behaves this way. No application code, SQL migration, database record or production configuration is changed by this section.

### 10.9 SKU and barcode strategy

Every sellable product variant must have an internal NMK SKU. The SKU is the primary human-readable stock identifier used by POS, inventory operations, stock movements and reporting.

1. **SKU generation:** Generate SKUs within NMK using a controlled, collision-safe sequence (for example, `NMK-000001`). Enforce global uniqueness across the catalogue. Do not derive the canonical SKU solely from product name, colour, size or other mutable attributes; changing a product label or variant attribute must not change its SKU.
2. **Required per variant:** Every active sellable variant, including a product with no selectable options (its default variant), must have one SKU before it can be sold through POS or online checkout.
3. **Barcode is optional:** Store a manufacturer barcode when one is available and verified. A variant can be sold without a barcode. Do not assume every supplier barcode is present, valid or unique.
4. **Barcode uniqueness and normalization:** Normalize barcode values consistently before comparison and storage. Enforce uniqueness for non-empty normalized barcode values across the catalogue. Keep missing barcodes as NULL rather than empty strings. Reject duplicate or invalid barcode assignments instead of silently assigning them to another variant.
5. **POS product lookup:** Support scanning a barcode as keyboard input. Also allow staff to find a variant by exact SKU and search by product name or variant attributes (such as colour and size). Barcode scanning must not be the only way to find or sell an item.
6. **NMK-generated barcodes:** The data model may support an NMK-generated barcode later if operationally required, but do not assume that one is required for the initial release. The internal SKU remains mandatory regardless.
7. **Controlled legacy assignment:** During migration, review the existing product catalogue and map each sellable variant to a stable SKU. Identify and resolve duplicate or malformed existing barcode values before enforcing uniqueness. Do not blindly generate variants or identifiers without reconciling the catalogue and existing online order references.
8. **Audit and immutability:** Do not silently change or reuse an SKU after sales or stock movements reference it. Any exceptional correction or barcode reassignment must be authorized and auditable, preserving historical transaction snapshots.

This is a logical design rule only. It does not authorize immediate schema migration, automatic SKU assignment, barcode printing, or application-code changes. Those require a separately reviewed implementation and migration plan.

### 10.10 Product and variant pricing

Use parent-product pricing as the default and allow an explicit selling-price override on an individual variant. This supports a simple catalogue while allowing variants with genuinely different prices.

1. **Parent default:** The parent product stores the default selling price used by its variants unless a variant has its own override.
2. **Variant override:** A variant may define an explicit selling-price override. When present, that override is the effective selling price for that variant; otherwise, use the current parent-product default.
3. **Single effective price:** POS and online product flows must resolve and display the effective price for the selected variant. The customer must see the selected variant's price before adding it to the cart or completing checkout.
4. **Server-side authority:** The backend must resolve and validate the applicable price when creating a sale or order. Never trust a price supplied by the browser or POS client as authoritative. Any authorized discount must be represented separately and validated against the staff member's permissions and configured limits.
5. **Historical sale snapshot:** Each completed sale item must preserve the actual unit price, discount and applicable price/tax calculation inputs or snapshots used at the time of sale. Later parent or variant price changes must never alter existing invoices, reports or return calculations.
6. **Price changes:** Updating a parent default may affect variants that inherit the parent price, but must not overwrite explicit variant overrides. Updating a variant override must affect only that variant. Record who made a price change and when; apply the appropriate authorization and audit rules.
7. **Money representation:** Store monetary values using fixed-precision decimal types, not floating-point types. Define currency consistently as INR for the initial NMK deployment. GST is not to be represented as collected while NMK is not GST-registered; invoice/tax wording remains subject to professional confirmation.
8. **Migration and compatibility:** Before implementation, audit existing product price fields and frontend/backend payloads. Preserve current customer-facing prices during migration, resolve invalid or inconsistent values, and ensure online orders and POS use the same effective-price rule. Do not silently reprice existing carts or confirmed orders.

This is a logical design decision only. It does not authorize immediate schema migration, price updates, or application-code changes. Implementation requires a separately reviewed migration and compatibility plan.

### 10.11 POS discounts and approval controls

The POS must support controlled discounts at both sale-item and whole-bill level. Discounts are separate from the catalogue selling price and must never rewrite the parent product price, variant override or historical sale price.

1. **Discount scope:** Support an item-level discount on an individual sale item and a bill-level discount on the eligible sale subtotal. Record the scope and the sale item reference where applicable.
2. **Discount types:** Support a fixed INR amount and a percentage discount. Validate that the entered value is positive and that the resulting discount does not exceed the eligible amount. The final payable amount must never become negative.
3. **Discount calculation:** Calculate discounts on the backend using the effective variant selling price and quantity. Define and consistently apply currency rounding. Prevent unintended stacking or duplicate application of discounts; the configured policy must determine whether bill-level discounts apply before or after item-level discounts.
4. **Owner-configured limits:** Store discount permissions and limits as configurable business settings, with scope for role, discount type and applicable amount or percentage. Do not hardcode a cashier percentage or rupee threshold in application code.
5. **Cashier permissions:** A cashier may apply discounts only within the currently configured cashier limit. The backend must enforce the limit for every sale; hiding or disabling a frontend control is not sufficient authorization.
6. **Manager approval:** A discount above the cashier's permitted limit requires approval by an authorized manager, within the manager's own configured authority. Capture the approving staff member and approval time. An approval must be explicitly recorded before the discounted sale can be completed.
7. **Owner authority:** The owner administrator can configure discount limits and perform authorized overrides. Owner overrides must also be recorded and auditable; they must not bypass calculation, amount validation or transaction integrity rules.
8. **Audit trail:** For every applied, changed, rejected or approved discount, retain the actor, approver where applicable, timestamp, discount scope and type, entered value, original eligible amount, calculated discount and resulting amount. Preserve the reason or note when required by the configured approval policy.
9. **Completed-sale integrity:** Once a sale is completed, its item prices, discounts, approvals and final totals are immutable historical transaction data. Corrections must use the authorized void, return, refund or adjustment workflow; do not edit the original completed sale or change catalogue prices to represent a discount.
10. **Returns and reporting:** Preserve enough item-level and bill-level discount allocation detail to calculate a fair return value and produce accurate net-sales and discount reports. A bill-level discount must be allocated consistently across sale items for return calculations, without changing the original invoice snapshot.
11. **Server-side totals:** The backend must calculate and persist line totals, discount totals and final payable amount from trusted catalogue prices and authorized discount inputs. Recalculate and validate the total atomically when completing the sale; do not accept client-calculated totals as authoritative.

This is a logical design decision only. It does not authorize immediate schema migration, discount-setting changes, or application-code changes. Implementation requires a separately reviewed database and API design, including the exact approval workflow and rounding/allocation rules.

### 10.12 Returns, refunds and exchanges

Returns, refunds and exchanges must be recorded as controlled transactions linked to the original completed POS sale. Never delete or rewrite the original sale, sale items, invoice or payment records to represent a return.

1. **Original sale reference:** Every POS return must reference the original completed sale and the specific sale item(s) being returned. Validate that the returned quantity does not exceed the quantity sold less quantities already returned. Prevent duplicate or concurrent returns from exceeding the remaining returnable quantity.
2. **Return reasons and condition:** Record a mandatory return reason, returned quantity, inspection result and receiving staff member. Capture supporting notes where required by the configured policy.
3. **Approval controls:** Require manager authorization for ordinary returns under the initial policy. Permit owner-admin override with a recorded reason. Enforce permissions on the backend and preserve the approver identity and timestamp.
4. **Separate return and refund states:** A return decision, physical receipt/inspection, stock disposition and money refund are distinct facts. Track them separately so that receiving an item does not falsely imply that money has been refunded, and initiating a refund does not falsely imply that it has settled.
5. **Refund processing:** Where supported, refund through the original tender/payment method. Record the refund amount, method, provider or transaction reference, initiating staff member, approval and timestamps. Cash refunds require authorized approval and a recorded cash-out reference. Do not mark a refund as successful until the actual cash disbursement or payment-provider confirmation is verified. Support pending, failed and completed refund outcomes without losing retry history.
6. **Refund limits:** The cumulative amount refunded for a sale must not exceed the eligible amount paid, adjusted for prior completed refunds and the applicable item/bill discount allocation. Enforce this atomically on the backend to prevent duplicate or concurrent over-refunds.
7. **Exchange representation:** Model an exchange as a return of the original item plus a separate new sale for the replacement item(s), linked by a common exchange reference. Collect any additional amount due through the normal POS tender flow. If the replacement is cheaper, record any eligible difference refund through the normal refund workflow. Do not overwrite the original sale or silently net transactions without an auditable record.
8. **Stock inspection and disposition:** Returning an item must not automatically increase sellable inventory. After physical inspection, an authorized staff member must classify it as resaleable, damaged/defective, or otherwise non-sellable. Only approved resaleable quantities may be restocked into the appropriate location. Damaged or non-sellable items must be recorded separately and excluded from available stock.
9. **Stock ledger:** Every approved restock or non-sellable disposition must create an auditable inventory movement linked to the return and return item. Record location, variant, quantity, disposition, actor, reason and resulting balance. Do not create a stock movement merely because a refund was requested or completed.
10. **Audit and immutable history:** Preserve original sale/payment records and append return, inspection, approval, refund and inventory events. Record who performed each action and when. Corrections must use authorized compensating records rather than deleting historical transactions.
11. **Eligibility policy:** Keep return window, eligible product categories, condition requirements and any exceptions configurable by the owner rather than hardcoding a universal period into the initial schema. Configure and operate the policy in accordance with applicable consumer-law obligations and professional advice.
12. **Online order boundary:** This section defines the initial physical POS return/exchange workflow. Online order cancellation, return pickup, shipping and Razorpay refund integration must be separately mapped to the existing online order/payment lifecycle before implementation; do not assume POS refund logic automatically covers online orders.

This is a logical design decision only. It does not authorize immediate schema migration, refund execution, inventory changes or application-code changes. Implementation requires separately reviewed return/refund state transitions, payment-provider capabilities, authorization rules and concurrency/idempotency tests.

### 10.13 Cash register, cashier shifts and daily closing

The POS must record cashier shifts and reconcile physical cash independently from non-cash tenders. A shift belongs to one cash register and the staff member responsible for it. The initial deployment may use one register, but the data model must support multiple registers at a location.

1. **Register and location:** Each register belongs to an inventory/business location and has a stable identifier. A register may have only one open shift at a time. Enforce this invariant transactionally in the database/backend, not only in the user interface.
2. **Shift opening:** An authorized cashier or manager opens a shift by recording the opening timestamp, register, responsible staff member and counted opening cash float. Preserve who opened it and any handover/approval details.
3. **Shift ownership:** Attribute every POS sale, tender/payment, refund cash-out and cash movement to the relevant active shift and register. Define an authorized manager handover procedure; do not allow two active shifts to operate the same register concurrently.
4. **Cash sales and expected drawer balance:** Include only cash actually received through the shift's cash tenders in the expected physical drawer balance. UPI, card and other non-cash tenders must be recorded separately and must never be added to expected drawer cash.
5. **Cash movements:** Record each non-sale cash-in or cash-out (for example, adding float or an approved petty-cash withdrawal) as a separate movement with amount, direction, reason, actor, timestamp and approval where required. Do not edit the opening float or completed movement records to force a reconciliation.
6. **Expected cash calculation:** Calculate expected closing cash from the opening float plus cash received, plus authorized cash-in, less authorized cash-out and cash refunds paid from the drawer. Derive the result from recorded transactions; do not trust a manually entered expected amount.
7. **Shift closing:** The responsible cashier enters the actual counted closing cash and submits the shift for closing/review. Persist the count, expected amount, difference, submitter and timestamps. Keep the calculation reproducible from the underlying tender and cash-movement records.
8. **Discrepancies:** Record shortage or excess as a discrepancy with its amount and, where available, a reason or explanatory note. Never silently change the expected amount, erase a discrepancy or create an unexplained balancing movement. A correction must be a separately authorized and auditable transaction.
9. **Manager review:** Use an owner-configured discrepancy threshold and approval policy. Require manager review when the configured threshold or other policy condition is met. Record reviewer, decision, reason and time. The owner may configure the policy and perform authorized overrides, with an audit record.
10. **Non-cash reconciliation:** Track UPI and card tender totals separately by method, provider/reference and shift. Provide the information needed to compare POS-recorded collections with provider/bank settlement records. A POS tender marked accepted is not proof that the provider has settled the funds; preserve pending, failed, reversed and confirmed states where applicable.
11. **Shift finalization and audit:** Keep shift lifecycle states and prevent a finalized shift from being silently reopened or edited. Any authorized reopening or post-close correction must record the actor, reason, approval and before/after references. Preserve opening, sales, tender, movement, closing, discrepancy and review history.
12. **Failure and concurrency safety:** Make shift opening, sale/tender association, cash movement and closing operations transactional and idempotent where retries are possible. Prevent sales from being assigned to a closed shift and prevent duplicate cash movements or duplicate closing submissions.
13. **Reports:** Provide shift-level and daily summaries showing opening float, cash sales, non-cash sales by method, cash-in/out, cash refunds, expected cash, counted cash, discrepancy and review status. Daily totals must be derived from completed transactions and clearly distinguish sales from payment-provider settlement.

This is a logical design decision only. It does not authorize immediate schema migration, cash drawer hardware integration, provider settlement automation or application-code changes. Implementation requires separately reviewed register/shift tables, tender states, cash movement types, permissions and reconciliation tests.

### 10.14 Supplier and purchase management

The POS back office must maintain supplier records and a traceable purchase/receiving workflow for frames, lenses and accessories. Purchase documents, physical receipt, supplier settlement and inventory movements are separate events.

1. **Supplier records:** Maintain a supplier record with supplier name, contact person, phone, address and internal notes. Supplier GSTIN and other tax identifiers are optional fields where applicable; NMK is currently not GST-registered, so the initial workflow must not require NMK GSTIN or assume GST is collected. Tax treatment and supplier-document handling must be confirmed with an accountant/adviser.
2. **Purchase document:** Record a unique purchase reference/number, supplier, document date, created-by staff member, line items, quantities, unit purchase costs and applicable additional charges such as freight. Preserve supplier invoice/reference details and document totals as historical snapshots.
3. **Variant-level lines:** Each purchase line must reference the exact product variant/SKU and record ordered quantity, unit cost and any line-level adjustment. Preserve product/SKU descriptions as snapshots so later catalogue edits do not rewrite purchase history.
4. **Purchase lifecycle:** Distinguish draft, ordered/confirmed, partially received, fully received and cancelled states as appropriate. A purchase order or draft purchase entry must not increase on-hand or available inventory.
5. **Physical goods receipt:** Record one or more receipt events against the purchase. Only quantities physically received, checked and accepted by an authorized staff member may increase sellable on-hand inventory. Support partial receipts and prevent accepted quantities from exceeding the outstanding ordered quantity unless an authorized, documented over-receipt exception is allowed.
6. **Damaged, missing and rejected goods:** Record received, accepted, damaged/rejected and missing quantities separately, with reason and notes. Damaged or rejected items must not increase sellable inventory. If the business later chooses to track them physically, use a separate non-sellable/quarantine disposition rather than treating them as available stock.
7. **Inventory ledger:** Every accepted receipt creates an idempotent stock movement linked to the purchase and receipt line, location, variant, quantity, actor and timestamp. Prevent duplicate receipt submissions from increasing stock twice. Do not directly overwrite inventory balances from purchase screens.
8. **Purchase cost and expenses:** Preserve actual purchase unit cost and additional attributable costs as purchase history. Keep purchase cost separate from selling price. Define the allocation of freight or other landed costs consistently before implementation; do not silently distribute costs differently across reports.
9. **Inventory valuation:** Support weighted-average cost reporting as the initial valuation approach, calculated from accepted inventory receipts and the existing inventory value/balance. Keep the underlying receipt and adjustment history so the valuation can be audited and recalculated. Returns, write-offs, opening balances and corrections must follow explicit valuation rules approved before implementation; do not infer cost from current selling price.
10. **Supplier settlement:** Track supplier payment/settlement separately from purchase creation and goods receipt. The initial design may record payment date, amount, method and supplier reference, but must not imply that a purchase is paid merely because goods were received. Any future payable/credit workflow requires separately defined approval and reconciliation rules.
11. **Purchase returns and supplier credits:** Link each supplier return to the original purchase and accepted receipt quantities. Reduce inventory only when goods are actually dispatched/handed back and the return movement is recorded. Track supplier refund, credit note or replacement separately from physical stock movement; do not assume a supplier credit automatically means money was received.
12. **Permissions and audit:** Cashiers cannot create, edit, receive or return purchases. Managers may create purchase records and record physical receipts within assigned permissions. Owner-admin controls supplier settings and sensitive adjustments/overrides. Preserve actor, approver, timestamps, reasons and before/after references for changes; do not delete purchase or receipt history referenced by stock movements.
13. **Reports:** Provide supplier purchase history, ordered versus received quantities, damaged/rejected/missing quantities, accepted receipt value, purchase-cost trends, supplier settlement records and stock valuation summaries. Clearly distinguish ordered value, received value and paid/settled amount.

This is a logical design decision only. It does not authorize immediate schema migration, supplier onboarding, purchase payment execution, inventory adjustment or application-code changes. Implementation requires separately reviewed purchase/receipt/return tables, cost allocation and weighted-average valuation rules, permissions, and idempotent inventory transaction tests.

### 10.15 Stock adjustments and physical stock counting

Inventory balances must not be directly edited by ordinary product or POS screens. Stock corrections must use an auditable adjustment workflow, and physical stock counts must be reconciled against the shared variant/location inventory model without disturbing active online reservations.

1. **Adjustment reasons:** Support controlled reason codes including damaged, lost/missing, found/excess, supplier correction, data-entry correction and other. Require explanatory notes for "other" and for configured exceptional cases. Record the affected variant, location, quantity and direction.
2. **Physical count sessions:** Allow authorized staff to open a count session for the full location, selected categories or selected variants. Record session scope, start time, counter(s), location and status. A count session is a snapshot/work process; opening or entering counts must not change live inventory.
3. **Count quantities:** Record the physically counted quantity per variant and location, count time, counter and any notes. Support recounts and independent verification for discrepancies. Preserve the original count entries and revisions rather than silently replacing them.
4. **System comparison:** At reconciliation, compare the physical count with the appropriate system quantity using a documented cutoff/snapshot. The comparison must account for stock movements occurring during the count window, such as sales, receipts, returns and transfers if later supported. Do not treat a stale opening quantity as current without reconciling intervening movements.
5. **Online reservations:** Distinguish physical on-hand quantity from reserved quantity and available quantity. A physical count must not accidentally clear or consume active online reservations. Reconcile pending orders, payment attempts and reservations before applying a count-based correction. Preserve the fail-closed rule for uncertain payment state.
6. **Proposed adjustment:** Generate a proposed adjustment for the verified difference, with expected system balance, counted physical balance, movement cutoff, calculated delta and reason. A count result alone must not automatically post an inventory movement.
7. **Approval:** Require manager review and approval for ordinary adjustments. Apply owner-configured thresholds and exceptional-case rules for owner-admin approval; do not hardcode a universal rupee or quantity threshold. Cashiers may participate in counting if authorized but cannot independently approve or post stock adjustments.
8. **Posting and concurrency:** Post an approved adjustment as an atomic stock movement against the current inventory balance, validating that the count basis is still valid and that intervening movements have been reconciled. If the balance changed after counting, require recalculation/recount or an explicit approved reconciliation; never blindly overwrite the latest balance with the counted number.
9. **Ledger and audit:** Every posted adjustment must create an append-only stock movement linked to the count session or adjustment request. Record previous/resulting balances, on-hand and reserved deltas as applicable, variant, location, reason, notes, actor, approver, timestamps and idempotency reference. Do not delete or rewrite historical stock movements.
10. **Damaged and non-sellable stock:** A count may identify goods that physically exist but are not sellable. Record their disposition separately from available stock. Moving items into or out of quarantine/non-sellable status must be an authorized, traceable inventory movement and must not increase sellable availability unless inspection approves it.
11. **Reports and closure:** Provide count-session reports showing system basis, physical count, intervening movements, variance, proposed adjustment, approval status and posted result. Close a session only after every discrepancy is resolved, explicitly accepted as unresolved under owner policy, or assigned a documented follow-up.
12. **Direct-edit prohibition:** Do not expose a general-purpose stock quantity field that silently overwrites inventory. All opening balances, corrections, write-offs and count variances must use controlled stock movements with reason, permissions and audit history.

This is a logical design decision only. It does not authorize immediate stock changes, count execution, schema migration or application-code changes. Implementation requires separately reviewed count-session/adjustment tables, cutoff and movement-reconciliation rules, authorization limits and concurrent online/POS test coverage.

### 10.16 Product catalogue lifecycle

The product catalogue must support a controlled lifecycle that separates whether a product is sellable, visible online, or retained only for historical and operational reference. Removing a product from the active catalogue must never erase transaction or inventory history.

1. **Active:** The product and its eligible variants may be sold through the channels enabled for them. Product-level and variant-level availability must be validated by the backend.
2. **Inactive:** Temporarily prevent new sales through the configured channels while retaining the product, variants, stock, reservations and historical records. Reactivation requires an authorized user and must revalidate stock and other operational constraints.
3. **Discontinued:** Mark a product as no longer offered for new sales. Preserve its records for existing orders, returns, warranty/service obligations, purchase history, stock reconciliation and reporting. Resolve or explicitly handle active reservations and outstanding operational commitments before discontinuation.
4. **Archived:** Hide the product from normal active catalogue and operational selection screens while retaining it in historical views and authorized administrative searches. Archiving is not deletion and must not bypass outstanding stock, reservation, return, purchase or service obligations.
5. **Channel availability:** Store online visibility separately from physical POS sellability. A shop-only product may be unavailable on the customer website but remain sellable through POS. Channel visibility must not override lifecycle status, variant availability, stock checks or authorization rules.
6. **Stock and reservation safeguards:** Do not archive or fully discontinue a product while it has unresolved sellable stock, active online reservations, pending orders, open purchase receipts, returns or other unresolved operational references. Require the relevant stock to be sold, transferred, returned, written off or otherwise dispositioned through the approved workflow. Never clear reservations merely to permit a status change.
7. **Historical snapshots and references:** Completed sales, invoices, online orders, purchase records, returns, stock movements and reports must remain readable with their original product name, SKU/barcode, variant attributes and transaction-time price/cost snapshots. Catalogue edits must not rewrite historical snapshots.
8. **Deletion policy:** Permanent deletion is exceptional and may be considered only when a product and all its variants have never been referenced by sales/orders, payments, purchases/receipts, inventory movements, reservations, returns, prescriptions, warranty/service records or other retained business history, and have no stock balance. Enforce this on the backend and database relationships; do not rely on a hidden or disabled UI button. Require owner-admin authorization and an audit record. Where any historical reference exists, retain and archive instead of deleting.
9. **Status transition controls:** Define allowed transitions and validate them server-side. Record previous and new status, channel visibility changes, actor, timestamp, reason and any approval. Prevent unauthorized staff from reactivating, discontinuing, archiving or deleting catalogue records.
10. **Search and reporting:** Normal product selection should default to active, channel-eligible products. Authorized staff must still be able to search inactive, discontinued and archived records when handling returns, warranty/service work, historical sales, stock counts and reports. Reports must not silently exclude historical transactions because a product is no longer active.
11. **Variant consistency:** Apply lifecycle and channel rules consistently to parent products and individual sellable variants. A parent product must not be offered through a channel if it has no eligible active variants; variant-level disablement must not delete the variant or its historical references.

This is a logical design decision only. It does not authorize catalogue status changes, permanent deletion, schema migration or application-code changes. Implementation requires separately reviewed product/variant status fields, channel-availability rules, transition permissions, historical foreign-key policies and tests for outstanding reservations and transaction references.

### 10.17 Barcode labels and scanning

The POS must support fast, reliable variant identification through barcode scanning while retaining NMK's own SKU as the stable internal identifier. A barcode is a lookup mechanism only; it does not establish stock availability or authorize a sale.

1. **Permanent NMK SKU:** Assign every sellable product variant a unique, stable NMK-generated SKU. Do not derive the SKU from editable product names, colour, size or other mutable attributes. A SKU must not be reassigned to another variant, including after discontinuation or archival.
2. **Barcode sources:** Support a supplier/manufacturer barcode where one is present and verified. If a variant has no usable supplier barcode, generate an NMK barcode value and encode it in a standard printable barcode format selected during implementation. Keep the barcode value distinct from the internal SKU, even if the printed label displays both.
3. **Variant-level mapping:** Resolve each accepted barcode to exactly one sellable variant. A parent product with multiple colours or sizes must not resolve ambiguously to the parent alone. Store the variant and product relationship and preserve historical barcode/SKU snapshots on transaction records where required.
4. **Uniqueness and normalization:** Normalize barcode input consistently, preserving meaningful leading zeroes and avoiding numeric conversion that can alter the value. Enforce uniqueness for active barcode mappings across the catalogue. Reject duplicate assignments and ambiguous matches. Define accepted barcode symbologies and check-digit validation before implementation.
5. **Multiple barcode aliases:** Allow more than one barcode to map to a variant only where there is a documented need, such as supplier and NMK labels. Each alias must remain unique, have a source/type and lifecycle state, and be auditable. Retired aliases must not be silently reassigned to a different variant if historical transactions may reference them.
6. **POS scan workflow:** A scan should look up the exact variant and return its product name, variant attributes, SKU, applicable price and channel eligibility. Before adding or completing a sale, the backend must revalidate that the product/variant is active for POS and that sufficient available inventory exists. Do not trust cached stock or a barcode match as stock authorization.
7. **Unknown or ambiguous scans:** If a barcode is unknown, duplicated, malformed or mapped to an unavailable/disabled variant, do not guess or automatically add a similar product. Show a clear error and allow an authorized staff member to search the catalogue manually or resolve the mapping through the controlled administration workflow.
8. **Label contents and printing:** Provide printable labels containing the barcode, human-readable SKU and enough product/variant description to distinguish similar items (for example, model, colour and size). Label layout, paper size, printer selection and optional price display should be configurable. Do not encode customer data, prescription information or other personal information in product barcodes.
9. **Scanner compatibility:** The initial POS should support common USB and Bluetooth scanners that operate as keyboard input and submit a scan using a configured suffix such as Enter. Avoid dependence on a particular scanner brand or proprietary SDK. The POS input workflow must handle rapid input, focus management and accidental duplicate scans safely.
10. **Printing and reprinting permissions:** Permit authorized staff to print or reprint labels. Record the actor, variant, barcode, quantity and timestamp where label printing history is operationally significant. Reprinting must not generate a new barcode or SKU for an existing variant.
11. **Barcode lifecycle:** When a variant is inactive, discontinued or archived, retain its barcode mapping for historical lookup and controlled returns/service workflows, but prevent ordinary new POS sales according to the product lifecycle rules. Do not reuse its barcode for a different variant.
12. **Fallback and resilience:** Permit manual SKU or product search when a scanner or barcode label is unavailable. Barcode lookup failure must not bypass server-side price, status, permission or inventory checks. Scanner input should be treated as untrusted input and validated like any other request.

This is a logical design decision only. It does not authorize purchase of scanner/printer hardware, barcode assignment to live products, label printing, schema migration or application-code changes. Implementation requires a separately reviewed barcode/alias data model, accepted symbologies and check-digit rules, label dimensions/printer tests, and end-to-end variant lookup and stock-validation tests.

