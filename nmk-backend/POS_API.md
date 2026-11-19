# NMK Opticals POS API

All endpoints require an authenticated admin JWT:

`Authorization: Bearer <admin-jwt>`

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

The authenticated admin ID is recorded in the stock movement audit. Reusing the same key for the same receipt is safe; reusing it for a different operation is rejected. This endpoint records a normal stock receipt; migration 005 does not create opening stock.

## 4. Complete a POS checkout

`POST /api/admin/pos/checkout`

Example cash sale:

```json
{
  "locationId": 1,
  "customerName": "Walk-in Customer",
  "customerPhone": null,
  "discountAmount": 50.00,
  "idempotencyKey": "pos-sale-unique-client-generated-key",
  "items": [
    {
      "productId": 10,
      "variantId": 42,
      "quantity": 2
    }
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

The client must generate a fresh idempotency key for each new checkout and reuse that same key when retrying the same checkout after a network timeout. Do not reuse a key for a different sale.

The server:
- Calculates prices from the product and variant records; client prices are not accepted.
- Applies the direct discount and validates the final payment total.
- Locks the location, products and variants in a stable order.
- Saves sale, item and payment snapshots and deducts inventory in one database transaction.
- Rejects unavailable stock; failed stock deduction rolls back the sale and payments.
- Writes an audited stock movement for every sold variant.
- Returns the invoice number, sale details, payment breakdown and cash change.

## 5. POS sales history

- `GET /api/admin/pos/sales?page=0&size=20`
- Optional location filter: `GET /api/admin/pos/sales?page=0&size=20&locationId=1`
- Sale details: `GET /api/admin/pos/sales/{saleId}`

Page size is limited to 100.

## Important v1 boundaries

- No GST/tax calculation is included pending the shop's tax configuration.
- Completed sales are immutable through this API. Void, return and refund flows must be implemented as separate audited operations; do not edit or delete completed sales.
- The manual database migration is `src/main/resources/db/manual/005-create-pos-sales-tables.sql`. Review and test it against a disposable staging database with a backup first. Do not execute it against the client's live database without a separate approved migration plan.
- No migration is executed by this code change.
