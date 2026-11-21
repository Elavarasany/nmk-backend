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

The client must generate a fresh idempotency key for each new checkout and reuse that same key when retrying the same checkout after a network timeout. Do not reuse a key for a different sale.

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

## 7. Staff accounts and roles

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
| Complete POS checkout | Yes | Yes | Yes |
| Browse all POS sales history | Yes | Yes | No |
| Inventory receipt and adjustment APIs | Yes | Yes | No |
| Staff account administration | Yes | No | No |
| Other existing admin APIs | Yes | No | No |

This is the first authorization layer. Shift ownership, per-register restrictions, refund approvals and granular permission codes are not yet implemented.

## Important operational boundaries

- No GST/tax calculation is included pending the shop's tax configuration.
- Completed sales are immutable through the current API. Returns/refunds must be separate audited operations; never edit or delete completed sales.
- Manual migrations are under `src/main/resources/db/manual/`. Review and test on a disposable staging database with a backup first. Do not execute against the client's live database without a separately approved migration plan.
- No database migration is executed by these code changes.
