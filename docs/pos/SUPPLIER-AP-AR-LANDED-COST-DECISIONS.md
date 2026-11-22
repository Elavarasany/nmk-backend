# NMK Opticals POS — Supplier AP, Customer AR and Landed-Cost Rules

**Status:** Provisional implementation policy; accountant/owner review required before production posting  
**Scope:** Supplier bills/payables, supplier settlements, customer receivables and inventory acquisition cost  
**Source plan:** POS canonical schema and Phase 5 migration plan

## 1. Existing implementation boundary

The current codebase supports supplier records, purchase orders, goods receipts, supplier returns and a procurement cost report. Those records do **not** yet constitute accounts payable: there is no supplier invoice/bill ledger, payment allocation ledger, or authoritative outstanding supplier balance. The procurement cost report is purchase/receipt cost less supplier-return cost, not profit or a general ledger.

## 2. Provisional accounting model

Use append-only, source-linked records. Do not derive a supplier's balance from purchase-order totals alone.

- A supplier bill records the supplier's invoice/reference, invoice date, due date, currency, gross amount, linked PO/receipt where known, and an immutable line/charge snapshot.
- Posting a bill creates a payable credit.
- A confirmed supplier payment creates a payable debit and is allocated to one or more bills.
- A supplier credit note/approved return credit reduces payable; it is not treated as cash received.
- Partial payments are allowed.
- Unallocated payment amounts remain supplier credit/on-account and must be visible; never silently discard or allocate them.
- Payment attempts are not successful payments. A bill balance changes only when payment is confirmed.
- Cash payments must reference an open POS shift and create exactly one cash-out movement. Bank/UPI payments require a reference/evidence field and do not alter drawer cash.
- Supplier balances are calculated from posted bills, confirmed payments, approved credits and allocations. Do not edit a posted amount; correct it with a reversal/credit/debit record.

## 3. Customer accounts receivable

The approved POS scope says POS sales are fully paid and split tender is allowed; online orders are paid online. Therefore the initial release has **no customer credit / accounts receivable**.

- Do not let a cashier complete a sale with an unpaid balance.
- Do not create a customer receivable merely because the customer profile exists.
- Do not treat an online order awaiting payment as a receivable; it remains a pending order/payment attempt.
- Any future store-credit, layaway, instalment or pay-later feature requires a separately approved credit policy, limits, collections/aging process, refund treatment and accounting review.

## 4. Provisional landed-cost allocation

For implementation planning, use a transparent and reproducible provisional method:

1. Only direct, attributable acquisition costs are eligible (e.g. inbound freight and handling). General overhead, selling expenses, finance charges and unrelated operating costs are excluded.
2. Allocate a receipt-level landed-cost charge to accepted, sellable receipt lines in proportion to each line's pre-allocation goods value (accepted quantity × supplier unit cost).
3. Round line allocations to two decimal places; assign any rounding remainder deterministically to the largest eligible line, with variant ID as a stable tie-breaker.
4. Quarantined/rejected goods do not receive sellable inventory cost until accepted. Do not allocate a charge to units that were not accepted into inventory.
5. Supplier returns reverse the recorded landed cost associated with the returned units, not a newly recalculated current average.
6. Track original goods cost, allocated landed cost and resulting landed unit cost separately for auditability.
7. Maintain weighted-average cost by variant/location as the provisional valuation method. Cost updates and stock receipt must be atomic and idempotent.

This is a provisional costing method, not an assertion that it is the legally/accounting-required method for NMK. Do not present gross margin/profit reports as authoritative until the accountant approves the valuation policy and opening-cost reconciliation.

## 5. GST and tax treatment

The existing project decision says NMK is not GST-registered. Do not charge output GST, claim input tax credit, or treat a supplier GSTIN/invoice as proof that NMK can claim ITC. Preserve supplier invoice tax details as source evidence only if required, but exclude tax from recoverable inventory cost/ITC treatment until an accountant confirms the applicable legal treatment. If registration changes, implement a separately reviewed tax-policy migration from the effective date.

## 6. Required accountant / owner confirmations

Before enabling production AP postings or valuation reports, obtain written confirmation of:

- Whether bills should be recorded at supplier invoice date, goods-receipt date, or both with a controlled accrual workflow.
- Whether supplier payments may be made from the POS cash drawer, and approval limits/roles.
- Treatment of supplier advances, debit notes, credit notes, discounts, rebates, freight, insurance, customs/duties and payment fees.
- Whether tax shown on supplier invoices is included in cost, separately recorded, or otherwise treated while NMK is not registered.
- Weighted-average valuation acceptance and the treatment of negative/zero inventory, stock write-offs and returns.
- Whether landed costs are allocated at PO, shipment or goods-receipt level and how late freight invoices adjust already-sold stock/COGS.
- Supplier statement reconciliation frequency and required audit evidence.
- Whether any customer credit/AR facility is intended; if yes, define a separate policy before implementation.

## 7. Release gate

Code may enforce the provisional rules and expose clearly labelled operational balances, but production accounting reports and tax treatment remain gated on accountant/owner review. No live database migration or financial opening-balance posting is authorized by this document.
