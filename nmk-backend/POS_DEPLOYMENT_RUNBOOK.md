# NMK Opticals POS Deployment Runbook

This runbook is a controlled deployment checklist. The POS feature branch is not permission to run migrations against a live database. Do not deploy until the client database credentials, an approved maintenance window, a restorable backup, and the accountant's tax decisions are available.

## 1. Current migration behavior

- Hibernate is configured with `spring.jpa.hibernate.ddl-auto=validate`; it must not create or alter the production schema.
- Flyway is intentionally disabled (`spring.flyway.enabled=false`). The SQL files under `src/main/resources/db/manual` are manual scripts, not automatically ordered migrations.
- Never run all files 001 through 004 as one sequence. File 004 is an alternative reconciliation for the already partially migrated staging schema and overlaps parts of 001. It is not the next step after 001-003.
- Never rerun an `ALTER TABLE ... ADD COLUMN` script without first inspecting the target schema; most scripts are not rerun-safe.

## 2. Before touching a database

1. Obtain written client approval and the correct database credentials. Confirm host, database name, MySQL version, TLS mode and timezone.
2. Take a full logical backup and verify that it can be restored to a separate database.
3. Restore a copy to a disposable staging database. Do not use the live database for experiments.
4. Compare the staging schema with the entity mappings and each SQL script. Record which columns, tables, indexes and constraints already exist.
5. Choose exactly one baseline path below. If the schema does not match either path, stop and prepare a new, schema-specific migration; do not improvise.

## 3. Choose the correct baseline

### Path A — legacy schema, not yet variant-inventory migrated

Use only when the inspected database has the original `products`, `customer_orders` and `order_items` schema and does not already contain the corresponding migration columns/tables:

1. `001-legacy-order-and-product-columns.sql`
2. `002-create-variant-inventory-tables.sql`
3. `003-create-main-store-location.sql`
4. `005-create-pos-sales-tables.sql` through `013-audit-stock-count-cancellations.sql`, in numeric order.

Run each script individually, inspect its output, and stop immediately on any error. Do not continue to the next script after a partial failure.

### Path B — the specific partially migrated staging schema

Use `004-staging-schema-reconciliation.sql` only if schema inspection confirms the exact preconditions documented in that file: variant/inventory tables already exist, the existing `AVP-01` location is present, and only the listed legacy order/product columns are missing.

After 004 succeeds and its verification queries match expectations, apply 005 through 013 in numeric order.

Path B is a staging-specific reconciliation, not a generic production migration. Do not assume a new client database matches it.

## 4. Verify schema before starting the application

- Confirm all POS tables, foreign keys, unique idempotency constraints, indexes and required columns exist.
- Confirm the application can start with `ddl-auto=validate` against the staging database.
- Confirm no duplicate register codes, SKU values, barcodes, location codes or idempotency keys exist.
- Confirm a failed startup does not cause Hibernate schema changes.
- Test the rollback/restore procedure on the disposable database.

## 5. Product variants and opening stock

The schema scripts deliberately do **not** invent product variants or opening stock. Existing `products.stock_quantity` values are preserved as legacy data; POS availability is calculated from `product_variants` and `inventory_balances`.

Before the first live sale:

1. Review every active product and decide its real sellable SKU/variant and barcode. Do not blindly create one default variant for every optical product if frame size, colour, power, lens or other attributes distinguish saleable stock.
2. Create the variants through the admin product-variant workflow.
3. Perform a physical opening count at the shop, map counted units to the correct variants, and load them into the MAIN_STORE location using the audited inventory receipt/opening-balance workflow. Use a unique idempotency key per opening movement and a clear reason such as `Approved opening stock count YYYY-MM-DD`.
4. Reconcile the resulting variant balances against the signed physical count. Do not add legacy `stock_quantity` on top of a count that already includes the same units.
5. Keep the signed opening-count worksheet and movement export as the audit record.

No online or POS checkout should be enabled until the active sellable variants and opening balances are verified.

## 6. Staff, register and cashier setup

1. Confirm the owner/admin account and test that disabled accounts cannot authenticate.
2. Create individual MANAGER and CASHIER accounts; do not share logins.
3. Create/verify the MAIN register for the active location.
4. Open the first cashier shift with the physically counted opening float.
5. Test checkout, split tender, cash change, cash-in/out, shift close and variance explanation using disposable staging records.
6. Confirm each cashier can access only their own shift operations and cannot browse all sales, process returns or apply discounts.

## 7. Application hosting configuration

Backend secrets must be configured in the hosting provider, never committed to Git:

- `AIVEN_HOST`, `AIVEN_PORT`, `AIVEN_DATABASE`, `AIVEN_USER`, `AIVEN_PASSWORD`
- a strong random `JWT_SECRET`
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
- `APP_CORS_ALLOWED_ORIGINS`: exact frontend origin(s), comma-separated, no wildcard
- `APP_CORS_REQUIRE_HTTPS=true` in production

Frontend build-time variable:

- `VITE_API_BASE_URL`: deployed backend origin ending in `/api`, for example `https://api.example.com/api`. Vite embeds this at build time, so rebuild/redeploy after changing it. The POS no longer silently falls back to localhost in production.

Use HTTPS for both frontend and API. Verify TLS/database connectivity, webhook signature verification, health/startup logs, and browser CORS behavior before enabling staff.

## 8. POS release acceptance checks

Run these checks on staging after migration and seed data are complete:

- Cash, UPI, card and split-tender checkout; exact cash change; duplicate retry with same key; reject changed request with reused key.
- Two concurrent attempts to sell the final unit; stock never becomes negative.
- Open/close shift races against checkout; cash ledger and expected drawer reconcile.
- Receipt, partial return, full return, damaged/discard dispositions, cash refund, recorded non-cash refund reference, and full void after a sale with no prior returns.
- Concurrent return attempts; cumulative returned quantity and refund never exceed the original sale.
- Stock receipt, adjustment, low-stock alert, stock count freeze/review/post/cancel, and movement history.
- Purchase order, partial goods receipt, over-receipt rejection, supplier return and stock ledger reconciliation.
- ADMIN/MANAGER/CASHIER authorization for every endpoint and direct API calls (not just hidden UI controls).
- Daily sales, return, refund, net-after-refund and payment-method totals reconcile to sale/return/shift ledgers.
- Frontend build/lint and backend Java + MySQL integration/concurrency CI pass on the exact commits proposed for release.

Do not test production by creating disposable sales, refunds or stock movements. Use staging.

## 9. Known external decisions and unsupported accounting behavior

These are genuine release gates, not items to guess in code:

- **GST/HSN and statutory invoice rules:** obtain accountant-approved HSN/tax classification per sellable product, applicable rates, inclusive/exclusive pricing, intra/inter-state treatment, customer invoice fields and rounding rules. Current POS totals are not a certified GST invoice or tax return.
- **Provider-side refunds:** UPI/card refund records require a provider-confirmed reference. The POS does not initiate or verify settlement with Razorpay/UPI/card providers. Obtain merchant API permissions and implement/test the provider refund adapter before describing refunds as automated.
- **Exchanges:** there is no atomic linked return-plus-replacement-sale workflow. Do not simulate an exchange by separately processing a full refund and a full new sale; that can misstate cash movement and payment settlement. Until the linked workflow is implemented and verified, process a return and a new sale as two separately approved transactions under the documented return/refund procedure.
- **Supplier accounting:** purchase orders, goods receipts and supplier stock returns do not constitute supplier credit notes, accounts payable, supplier payment, landed-cost accounting or bank settlement. Obtain the client's accounting workflow and required fields before implementing these ledgers.
- **Profit reporting:** gross sales/product totals are not profit. Cost history and approved accounting treatment are not yet available for a reliable POS profit report.

## 10. Release decision

Do not merge the draft POS pull requests or point staff at production until:
- the client database migration has been reviewed and tested on a restorable clone;
- product variants and opening stock have been physically reconciled;
- the client's tax/invoice requirements are approved;
- refund and exchange operating procedures are explicitly accepted (or the missing integrations/workflows are implemented);
- exact-commit CI and staging acceptance checks pass; and
- the owner signs off on the cutover and rollback plan.

The feature branches and draft PRs intentionally remain separate from `main`/`master` until those gates are satisfied.
