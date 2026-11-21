# NMK Opticals POS API

All endpoints require an authenticated staff JWT unless marked as public. The login endpoint returns the staff role.

## 1. Load POS locations

`GET /api/admin/pos/locations`

Returns active inventory locations. Use the selected location ID for catalog and checkout.

## 2. Load POS catalog and live stock

`GET /api/admin/pos/catalog?locationId=1`

Returns active products with active variants, SKU/barcode, server selling price, on-hand, reserved and available quantity for that location. Variants without a balance are returned with zero stock.

The checkout endpoint remains authoritative for price and stock. Never trust prices or stock quantities sent by the browser.

## 3. Receive stock

`POST /api/admin/inventory/receive`

Example:

```json
{
  "locationId": 1,
  "variantId": 42,
  "quantity": 10,
  "idempotencyKey": "receipt-2026-0001",
  "reason": "Supplier delivery"
}
```

The authenticated admin identity is recorded in the stock movement audit. Reusing the same key for the same receipt is safe; reusing it for a different operation is rejected. This endpoint records a normal stock receipt; migration 005 does not create opening stock.

## 4. Complete a POS checkout

`POST /api/admin/pos/checkout`

Example cash sale:

```json
{
  "locationId": 1,
  "shiftId": 12,
  "customerName": "Walk-in Customer",
  "customerPhone": null,
  "discountAmount": 50.00,
  "idempotencyKey": "pos-sale-unique-client-generated-key",
  "items": [
    { "productId": 10, "variantId": 42, "quantity": 2 }
  ],
  "payments": [
    {
      "paymentMethod": "CASH",
      "amount": 450.00,
      "tenderedAmount": 500.00,
      "transactionReference": null
    }
  ]
}
```

Supported payment methods are `CASH`, `UPI`, and `CARD`. For split tender, send multiple payment rows, one per method, and ensure their amounts add up exactly to the grand total. Cash rows require `tenderedAmount`; non-cash rows must omit it.

Every new checkout must reference the authenticated cashier's OPEN shift at the same location. The client must generate a fresh idempotency key for each new checkout and reuse that same key when retrying the same checkout after a network timeout. Do not reuse a key for a different sale.

The server calculates prices from product and variant records, validates discounts and payment totals, locks inventory in a stable order, writes sale/item/payment snapshots, deducts stock and appends stock movements in one transaction. Failed stock deduction rolls back the sale and payments.

## 5. POS sales history

- `GET /api/admin/pos/sales?page=0&size=20`
- Optional location filter: `GET /api/admin/pos/sales?page=0&size=20&locationId=1`
- Sale details: `GET /api/admin/pos/sales/{saleId}`

Page size is limited to 100.

## 6. Audited stock adjustment

`POST /api/admin/inventory/adjust`

Use only for verified physical count corrections, damage/write-offs, loss or documented discrepancies. Supplier deliveries must use the receipt endpoint.

```json
{
  "locationId": 1,
  "variantId": 42,
  "quantityDelta": 5,
  "idempotencyKey": "stock-count-adjustment-2026-0001",
  "reason": "Physical count correction; five additional units verified"
}
```

Positive `quantityDelta` adds stock; negative removes it. Zero is rejected. Adjustments cannot reduce on-hand below reserved stock. The balance update and append-only stock movement are transactional. A retry must reuse the exact payload and key.

## 8. Registers, cashier shifts and cash drawer reconciliation

The migration provisions one `MAIN` register for each active inventory location. Additional registers can be created by an ADMIN.

- `GET /api/admin/pos/registers?locationId=1` — list active registers.
- `POST /api/admin/pos/registers` — ADMIN only; body: `{"locationId":1,"registerCode":"MAIN-2","name":"Second Register"}`.
- `GET /api/admin/pos/shifts/current?registerId=1` — current open shift visible to its cashier, ADMIN or MANAGER.
- `POST /api/admin/pos/shifts/open` — open a shift.

```json
{
  "registerId": 1,
  "openingFloat": 1500.00,
  "idempotencyKey": "shift-open-device-generated-key"
}
```

- `POST /api/admin/pos/shifts/{shiftId}/cash-movements` — record `CASH_IN` or `CASH_OUT`, with positive amount, unique idempotency key and mandatory reason.
- `GET /api/admin/pos/shifts/{shiftId}/cash-movements` — view the append-only drawer ledger (cashier's own shift; ADMIN/MANAGER can review any shift).
- `POST /api/admin/pos/shifts/{shiftId}/close` — body: `{"closingCashCount":1475.00,"reason":"Short by ₹25 after recount"}`.

Expected drawer cash is calculated from opening float + cash sale tender allocations + cash-in − cash-out. Cash sales add the allocated payment amount, not the higher tendered amount; change returned to the customer is therefore not counted as drawer income. Cash-out cannot exceed the current expected drawer balance. A closing count different from expected cash requires an explanation and is stored with the signed variance.

Only one OPEN shift can exist per register. The register row is locked during shift opening, and the shift row is locked during checkout/cash movement/close so a checkout cannot race with shift closure. Cashiers can operate their own shift; ADMIN/MANAGER can review and close other shifts. A cashier cannot close another cashier's shift.

The checkout transaction writes its cash tender movements in the same transaction as sale/payment/inventory changes. A failed sale therefore cannot leave a cash drawer entry behind. Every new POS sale is linked to the shift that accepted it.

## 9. Staff accounts and roles

All staff endpoints below require an ADMIN role. Passwords are BCrypt-hashed and are never returned by the API.

- `GET /api/admin/staff` — list staff identities without password hashes.
- `POST /api/admin/staff` — create a staff account.

```json
{
  "username": "cashier.one",
  "password": "use-a-unique-12-plus-character-secret",
  "role": "CASHIER"
}
```

- `PATCH /api/admin/staff/{id}/role` — body: `{"role":"MANAGER"}`.
- `PATCH /api/admin/staff/{id}/status` — body: `{"enabled":false}`.

Roles are `ADMIN`, `MANAGER`, and `CASHIER`. Usernames are normalized to lowercase. The last active ADMIN cannot be demoted or disabled, and a user cannot disable their own account. Disabled accounts are rejected at login and previously issued JWTs stop working on their next request.

### Current authorization boundary

| Capability | ADMIN | MANAGER | CASHIER |
|---|---:|---:|---:|
| POS catalogue and location selection | Yes | Yes | Yes |
| Complete POS checkout on own open shift | Yes | Yes | Yes |
| Open/close own shift and cash in/out | Yes | Yes | Yes |
| Review any shift and cash ledger | Yes | Yes | No |
| Browse all POS sales history | Yes | Yes | No |
| Inventory receipt and adjustment APIs | Yes | Yes | No |
| Create POS registers | Yes | No | No |
| Staff account administration | Yes | No | No |
| Other existing admin APIs | Yes | No | No |

This is the first authorization layer. Per-register assignment, supervisor approval thresholds, role-permission tables and refund approvals remain future work.

## 10. Customer returns and refunds

Only ADMIN and MANAGER can process a return. The original sale and sale lines are never edited or deleted.

- `GET /api/admin/pos/sales/{saleId}/returns` — return history for the original sale.
- `POST /api/admin/pos/sales/{saleId}/returns` — create an audited return/refund.
- `POST /api/admin/pos/sales/{saleId}/void` — manager/admin-only full void of a completed sale that has no prior return.

Example:

```json
{
  "shiftId": 12,
  "reason": "Customer selected the wrong frame size",
  "idempotencyKey": "return-unique-device-key",
  "items": [
    {
      "saleItemId": 55,
      "quantity": 1,
      "restockDisposition": "RESTOCK",
      "dispositionReason": null
    }
  ],
  "refundPayments": [
    {
      "paymentMethod": "CASH",
      "amount": 950.00,
      "transactionReference": null
    }
  ]
}
```

The server locks the original sale while validating cumulative returned quantities. A sale line cannot be returned beyond the quantity originally sold. Refund values are calculated from the original line-price snapshot and a proportional allocation of the original sale discount; the client cannot choose the refund amount. A full return of the remaining line quantity receives any rounding remainder so partial returns cannot over-refund the line. Cumulative refund value cannot exceed the original sale total.

Restock dispositions:

- `RESTOCK`: only for physically received, inspected, resaleable goods; creates an append-only `CUSTOMER_RETURN_RESTOCK` stock movement.
- `DAMAGED`: no stock increase; a disposition reason is mandatory.
- `DISCARD`: no stock increase; a disposition reason is mandatory.

Refund tender allocations must add up exactly to the server-calculated refund total. CASH refunds create an OUT cash movement on the current open shift and are rejected if the drawer does not contain enough expected cash. UPI/card refund records require a transaction reference.

**Provider limitation:** this POS endpoint records UPI/card refund references; it does not call a UPI/card provider or Razorpay to move funds. The refund must be completed and verified through the relevant provider before recording the reference. Do not treat a saved reference as proof of provider settlement.

The return idempotency key is scoped to the original sale and request fingerprint. Replaying the same request returns the prior return; changing the sale or request while reusing the key is rejected. Returns, refund payments and resaleable stock movements commit in one database transaction.


### Full sale void

A full void is distinct from a partial/customer return. It restores every original sale line as resaleable stock, creates one `VOID` audit record, and requires refund allocations to equal the original sale total. It is rejected if any return has already been processed for that sale. The original sale remains immutable and is not deleted or rewritten.

Example:

```json
{
  "shiftId": 12,
  "reason": "Duplicate sale entered by cashier before customer left",
  "idempotencyKey": "void-unique-device-key",
  "refundPayments": [
    { "paymentMethod": "CASH", "amount": 950.00, "transactionReference": null }
  ]
}
```

Cash refunds are recorded as an OUT movement on the open shift and cannot exceed expected drawer cash. UPI/card refunds require a provider-confirmed reference; this API does not execute the provider refund. A full void uses the same transaction, locking and idempotency protections as customer returns. Only ADMIN and MANAGER may call it; CASHIER requests are forbidden.

## 11. Frozen physical stock counts

All stock count operations require ADMIN or MANAGER authority.

- `GET /api/admin/inventory/stock-counts/current?locationId=1` — retrieve the active count, if any.
- `POST /api/admin/inventory/stock-counts/start` — create a snapshot and freeze stock-changing operations at that location.

```json
{
  "locationId": 1,
  "reason": "Monthly physical stock count"
}
```

- `PATCH /api/admin/inventory/stock-counts/{sessionId}/lines/{lineId}` — body: `{"countedQuantity":7}`.
- `POST /api/admin/inventory/stock-counts/{sessionId}/submit` — submit after every line has a count.
- `POST /api/admin/inventory/stock-counts/{sessionId}/post` — post the verified differences as `COUNT_VARIANCE` stock movements.
- `POST /api/admin/inventory/stock-counts/{sessionId}/cancel` — cancel without posting and unfreeze the location.

The session snapshots every product variant at the selected location, including variants with no existing balance (expected on-hand zero). The location row is locked while the snapshot is created. While the session is COUNTING or READY_FOR_REVIEW, inventory receipts, adjustments, POS sales, customer return restocks and online stock reservations are blocked. Completed idempotent movements remain safely replayable.

The location remains frozen until the session is posted or cancelled. Posting holds the location lock while applying all variance movements and releasing the freeze in the same transaction. A failed variance adjustment rolls back the post and leaves the location frozen. Negative variances cannot reduce on-hand below reserved stock. Positive variances can create a missing balance for a physically discovered variant.

The stock count line stores the snapshot quantity, counted quantity, variance, user and timestamp. The stock ledger records each non-zero variance with a `STOCK_COUNT` reference. Repeated submit/post/cancel requests are safe to retry.

## 12. Suppliers, purchase orders and goods movements

All procurement endpoints require ADMIN or MANAGER authority.

- `GET /api/admin/procurement/suppliers`
- `POST /api/admin/procurement/suppliers` — create a supplier with name and optional contact details.
- `PATCH /api/admin/procurement/suppliers/{supplierId}/active` — body: `{"active":false}`.
- `GET /api/admin/procurement/purchase-orders`
- `POST /api/admin/procurement/purchase-orders` — create a DRAFT purchase order.
- `GET /api/admin/procurement/purchase-orders/{purchaseOrderId}`
- `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/submit` — DRAFT → ORDERED.
- `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/receive` — record a full or partial goods receipt.
- `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/supplier-returns` — return previously received goods to the supplier.

Create purchase order example:

```json
{
  "supplierId": 2,
  "locationId": 1,
  "notes": "Frames replenishment",
  "idempotencyKey": "po-create-device-unique-key",
  "items": [
    { "variantId": 15, "orderedQuantity": 20, "unitCost": 425.00 }
  ]
}
```

Goods receipt example:

```json
{
  "reason": "Supplier delivery checked against packing slip",
  "idempotencyKey": "gr-device-unique-key",
  "items": [
    { "purchaseOrderItemId": 44, "quantity": 12 }
  ]
}
```

A purchase order begins as DRAFT and must be submitted before goods can be received. Receipts can be partial. The backend locks the purchase order and its item rows, rejects over-receipt, records an immutable goods receipt and adds inventory in the same transaction. The PO status moves to PARTIALLY_RECEIVED or RECEIVED as appropriate. Goods receipt idempotency keys are scoped to the PO and request fingerprint.

Supplier returns can only include quantities already received and not previously returned. The backend locks the purchase order and item rows, creates a supplier-return record, and writes a negative `SUPPLIER_RETURN` stock movement. A return fails if it would reduce on-hand below reserved stock. Goods receipt and supplier return stock movements are blocked during a frozen physical stock count.

Supplier returns are inventory and supplier audit records only; they do not create supplier credit notes, accounts-payable entries or automatic bank refunds. Purchase cost is stored for purchasing visibility; GST and landed-cost accounting are intentionally not calculated until the shop's accountant confirms the required rules.

## 13. Stock movement history

Managers and administrators can review the append-only stock ledger for a location:

- `GET /api/admin/inventory/movements?locationId=1&page=0&size=50`
- Optional variant filter: `GET /api/admin/inventory/movements?locationId=1&variantId=42&page=0&size=50`

The endpoint returns a newest-first page of movement snapshots, including quantity and reservation deltas, resulting on-hand/reserved balances, movement type, reference, staff actor ID, reason and timestamp. Page size is limited to 100. The endpoint is read-only; ledger entries are never edited or deleted through POS.

## 14. Reorder thresholds and low-stock alerts

- `PATCH /api/admin/inventory/reorder-level` — configure a threshold for one variant at one location.
- `GET /api/admin/inventory/low-stock?locationId={id}` — list configured variants whose available quantity is at or below their threshold.

Example:

```json
{
  "locationId": 1,
  "variantId": 25,
  "reorderLevel": 5
}
```

A reorder level of zero disables alerts. Alerts compare `availableQuantity` (on-hand minus reserved), not raw on-hand, so stock already committed to online orders is not treated as sellable. Threshold changes record the staff username and timestamp on the inventory balance. Thresholds are location-specific; they do not automatically create purchase orders.

## 15. POS sales and refund reports

Managers and administrators can request a location/date-scoped report:

- `GET /api/admin/pos/reports/sales?locationId=1&from=2026-11-01&to=2026-11-21`

The response includes transaction count, subtotal, discounts, sales after discount, returns/refunds recorded during the selected period, net after refunds, tender totals, refund tender totals, cashier totals, daily totals and the top ten products by gross line total. Refunds are grouped by the date the return was processed, not backdated to the original sale date. The report is limited to 367 calendar days per request.

This report does not calculate GST, profit, supplier liabilities or bank settlement. POS sales totals are not a substitute for accountant-approved financial statements.

## 16. Full sale voids

Managers and administrators can void a completed sale only when no earlier return has been recorded:

- `POST /api/admin/pos/sales/{saleId}/void`

Example:

```json
{
  "shiftId": 9,
  "reason": "Sale cancelled before customer leaves",
  "idempotencyKey": "void-device-unique-key",
  "refundPayments": [
    { "paymentMethod": "CASH", "amount": 900.00 }
  ]
}
```

A full void records a `VOID` return record, restocks all original sale lines, and records the full refund allocation. It is idempotent and cannot be applied after any partial/full return. The action must be processed against an open shift at the same location. Managers/admins may use another cashier's open shift; cashiers may use only their own. Cash drawer refunds are limited by expected drawer cash. UPI/card refunds require the actual provider/bank refund reference.

This endpoint records a refund; it does not initiate a UPI/card refund with the payment provider. Complete the external refund first and enter its real reference. Only use a full void when the goods are physically returned in resaleable condition before leaving the shop; use the normal return workflow for goods that are damaged or discarded.

## Important operational boundaries

- No GST/tax calculation is included pending the shop's tax configuration.
- Completed sales are immutable through the current API. Returns/refunds must be separate audited operations; never edit or delete completed sales.
- There is no atomic linked-exchange endpoint yet. Do not process an exchange as though a return and a new sale were one transaction; confirm the shop's price-difference collection/refund policy before enabling that workflow.
- Manual migrations are under `src/main/resources/db/manual/`. The current POS migration order includes 002 (variants/inventory), 005 (sales), 006 (staff roles), 007 (registers/shifts/cash) 008 (returns/refunds) and 009 (physical stock counts) and 010 (suppliers, purchase orders and goods movements) and 011 (reorder threshold audit fields) and 012 (distinguish full sale voids). Review and test on a disposable staging database with a backup first. Do not execute against the client's live database without a separately approved migration plan.
- Production deployments must activate `SPRING_PROFILES_ACTIVE=prod` and provide `APP_CORS_ALLOWED_ORIGINS` as an explicit HTTPS origin allowlist. The production profile enforces Hibernate `validate`, disables automatic schema changes and hides exception details. Keep manual migrations separate from application startup.
- No database migration is executed by these code changes.
