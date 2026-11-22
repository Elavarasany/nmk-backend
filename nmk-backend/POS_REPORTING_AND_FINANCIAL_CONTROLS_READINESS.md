# POS Reporting and Financial Controls Readiness

Branch: `feature/pos-operations-v1`

This document defines what the current POS reports measure and what they do not establish. These are operational reports, not audited accounts, tax reports, bank reconciliation, inventory valuation, or profitability statements.

## Sales report

The sales report is scoped to one inventory location and an inclusive `from` / `to` date range. The query uses `LocalDateTime` boundaries: start date at 00:00 inclusive and the day after the end date at 00:00 exclusive. There is no timezone stored in these timestamps; confirm the deployed server timezone before relying on date cutoffs.

- **Transactions / subtotal / discounts / sales after discount:** completed POS sales whose sale creation timestamp falls inside the selected period. Sales after discount is the sum of sale grand totals before any later returns or voids.
- **Refunds:** completed customer returns plus completed full-sale voids processed during the selected period. They are grouped by the return/void processing date, not the original sale date.
- **Full void reversals:** completed records whose return type is `VOID` only. Ordinary customer returns are not full-sale reversals.
- **Recorded refund allocations:** refund tender allocations recorded in POS, grouped by method. This is not evidence that a noncash refund reached the customer's account.
- **Net after returns and voids:** sales after discount for sales created in the period minus all completed return/void refund totals processed in the period. Because sales and refunds are grouped by their own event dates, this is a period activity measure, not a cohort-based lifetime net value of the sales created in the same period. It can be negative in a period with refunds for older sales.
- **Exchange credit applied:** non-cash credit applied to replacement sales for exchanges created during the period. It is shown separately and is not included in refund tender totals. Do not add it to sales or subtract it again from net sales without a separate accounting policy.
- **Sales by payment method:** tender allocations recorded against completed sales in the period. UPI/card references are staff-entered operational references; they are not provider-confirmed transactions or bank-cleared funds.
- **Refunds by payment method:** tender allocations recorded on completed returns/voids processed during the period; provider settlement is not confirmed by this report.
- **Cashier totals:** completed sales attributed to the cashier on the sale record; later returns/voids are not allocated back to cashier totals.
- **Top products:** gross quantity and line total from sale items in the period. These figures do not allocate sale-level discounts, returns, voids, or exchange dispositions back to individual products.

The report currently does not calculate tax breakdowns, COGS, gross margin, operating expenses, net profit, receivables, payables, payment-provider fees, chargebacks, or bank settlement.

## Inventory report

The inventory report shows recorded on-hand, reserved, available, and low-stock quantities by variant at a location. It does not assign a monetary value to stock. Do not describe quantity multiplied by retail price as inventory valuation.

## Procurement cost report

The procurement cost report sums the unit costs stored on purchase-order lines for goods receipt quantities, then subtracts the same recorded unit costs for supplier-return quantities, based on receipt and supplier-return processing timestamps.

It is a **net recorded procurement-cost activity measure** only. It is not proof of supplier invoice amounts, amounts paid, supplier credit notes, accounts payable, landed cost, freight, tax, purchase discounts, inventory valuation, or COGS. Purchase order unit cost is entered operationally and is copied to receipt/return records; it is not independently matched to supplier invoices or payment records.

## Cash and payment controls

- Cash drawer expected balance is based on recorded cash movements only. UPI/card amounts do not belong in the cash drawer.
- Shift close and cash-refund operations both lock the shift row before changing drawer state. A MySQL concurrency regression test now races a cash refund against shift close and verifies that either the refund is included before close or is rejected after close, with no post-close drawer mutation.

- Cash-in/cash-out entries require a reason and are recorded against the shift with the acting username. A cashier may record movements on their own shift; a manager/admin may manage another staff member's shift. Cash-out is capped at the current expected drawer balance, but cash-in has no configured ceiling.
- There is no separate fresh-password approval, supervisor threshold, attachment/evidence field, or second-person confirmation for cash-in/cash-out. A large or unsupported cash-in can inflate expected drawer cash, while an otherwise permitted cash-out can remove cash without independent approval. The actor/reason audit trail is useful but does not prove the physical movement occurred.
- Before production, the owner must define cash-in/out limits, permitted reasons, approval requirements, evidence retention, and who may authorize movements on another cashier's shift. Do not invent monetary thresholds without that policy.
- UPI/card sale and refund references are operator-entered. Verify transactions in the merchant/provider portal independently.
- POS does not initiate or verify provider refunds. Complete and verify noncash refunds externally before recording them.
- Reconcile daily sales tender totals against provider transaction exports and provider net settlements against bank credits. Investigate fees, timing differences, reversals, chargebacks, and unmatched references.
- Exchange credit is a separate settlement component and must not be mistaken for a cash refund or provider refund.

## Required before financial/accounting use

1. Accountant-approved tax configuration and statutory invoice requirements.
2. Supplier invoice matching, credit-note handling, freight/tax/discount allocation, and landed-cost policy.
3. Provider payment/refund integration or documented manual reconciliation with evidence and exception ownership.
4. Bank settlement reconciliation, fees, chargebacks, and payment timing rules.
5. Inventory costing method and controlled opening-stock valuation.
6. COGS and returns accounting policy, including damaged/discarded stock and exchange treatment.
7. Explicit business timezone and financial-period boundaries.
8. Accounting review and test cases against representative real-world transactions.

Until those controls exist, label these screens as operational POS reports. Do not use them as audited financial statements or claim profit from them.


## POS idempotency-key scope and retention

- The database enforces uniqueness within each owning table, not across the entire POS domain: sales, returns, exchanges, shifts and cash movements each have their own unique idempotency-key constraint. This is appropriate for replay within each operation type, but it is not one global key registry.
- Checkout, return, exchange and shift keys are trimmed and limited to 100 characters by service validation, while the corresponding SQL columns allow 150 characters. Cash movement keys are also limited to 100 characters; internally generated movement keys add operation prefixes and suffixes but remain within the 150-character column limit.
- Exchange creates linked return and replacement-sale keys by prefixing the supplied exchange key (POS-EXCHANGE-RETURN: and POS-EXCHANGE-SALE:). Checkout accepts arbitrary caller-provided keys in the same pos_sales namespace. Therefore, a checkout key deliberately or accidentally matching a reserved exchange-generated key can block that exchange through the unique constraint. The current design relies on prefixes rather than a typed/global key namespace.
- Cash movement records also share one key namespace across manual cash-in/out and internally generated opening-float, cash-sale and refund movements. Replay validation checks shift, actor, movement type, amount and reason, which prevents a mismatched request from silently replaying, but a conflicting key still causes an operation to fail.
- There is no automatic TTL or cleanup job for POS idempotency records. These keys are stored on durable financial/operational records and should not be deleted independently: doing so can remove the database's replay guard and allow an old request to be processed again. Retention/deletion must be governed by an approved financial-record retention policy, with dependent records and backups considered together.
- Before production, reserve and validate internal prefixes for client-supplied keys (or introduce operation-specific key namespaces), test conflicting-key behavior, and document retention. Do not add automatic deletion of sales/returns/exchanges/cash movements merely to reduce table size.


## POS location isolation and multi-location authorization

- POS checkout is location-consistent at the transaction level: it locks the requested active inventory location, requires the selected open shift to belong to that location and the authenticated cashier, then sells stock against that location. Return/void and exchange similarly derive the source sale location and require the selected shift to belong to it. These checks prevent using a shift from another location for those transactions.
- The staff account model currently contains username, password, global role and enabled status only. It has no assigned-location relationship or permitted-location list. Consequently, the application does not enforce location-scoped staff access.
- Cashiers, managers and admins can list all active locations and request the catalog for any active location. The catalog intentionally includes the global active product/variant catalogue while showing stock quantities for the requested location.
- Managers/admins can query sales without a location filter (all locations), fetch a sale by ID, view its returns/exchanges, query shift history for any location, and request reports for any location. Manager/admin role is global rather than location-scoped. This is an authorization boundary gap if the business later operates multiple branches or grants managers access to only one branch.
- Register and shift operations validate register/location consistency for checkout, return and exchange, but opening a shift accepts any active register ID and there is no staff-to-register/location assignment. A cashier can therefore open a shift at any active location's register if they know its ID.
- No evidence of a current exploit against the single-shop deployment is established by this review. For the planned one-shop operation, this is a future multi-location readiness gap rather than a reason to invent branch restrictions. Before adding branches, add explicit staff-location assignments, enforce them in every location/sale/register/shift/report endpoint and service, scope queries by authorized location, and test cross-location ID substitution. Until then, do not treat this POS as securely partitioned for multiple branches.
