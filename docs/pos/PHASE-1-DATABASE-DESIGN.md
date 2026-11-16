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
- No assumption about GST registration, GST rates or invoice compliance until the business confirms its status and requirements.

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

The exact scheduled reservation-cleanup entry point still needs to be located and reviewed before implementation.

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

1. Confirm GST registration and invoice requirements.
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

- Is NMK GST-registered? What GSTIN and invoice rules should apply? Do not infer GST registration or tax rates.
- Should customer phone numbers be mandatory for creating a saved customer profile? Walk-in sales should still be possible without one.
- What is the desired invoice prefix and numbering policy?
- Which staff roles are required initially, and how do they map to the existing admin authentication model?
- What are the physical stock count and outstanding online reservations at cutover?
- What is the actual scheduled cleanup component for expired reservations?
- Are multiple branches/locations required? Initial design assumes one shop location but should avoid blocking future expansion.

## 8. Immediate next audit task

Before creating entities or migrations, locate and inspect:
- The scheduled job that calls expired reservation cleanup.
- Every service/controller that changes `stockQuantity`.
- The order status transitions after successful payment, cancellation, refund and fulfilment.
- Existing admin/customer endpoints and authentication/role structure.
- Product SKU/barcode fields and product variant/size representation.

**No application code, schema, database records, credentials, deployment settings or other repository files are changed by this design document.**
