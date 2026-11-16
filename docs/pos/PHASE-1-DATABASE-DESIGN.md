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
