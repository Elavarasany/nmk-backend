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
