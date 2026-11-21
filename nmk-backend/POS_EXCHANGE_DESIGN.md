# POS Exchange Workflow — Implementation Design

Status: design approved for implementation; NOT implemented yet.

This document is the implementation contract for an exchange. Treating an exchange as an ordinary refund plus an unrelated sale would corrupt the audit trail and cash/payment reporting.

## Business invariants
1. An exchange references one completed original sale and creates one replacement sale.
2. Returned quantities must be available on the original sale after subtracting all prior completed returns.
3. Returned goods use RESTOCK / DAMAGED / DISCARD rules. Only RESTOCK changes sellable on-hand stock.
4. Return and replacement sale are one database transaction. If validation, payment, inventory movement, or persistence fails, neither side commits.
5. Exchange credit is non-cash value. It must never create CASH_SALE, CASH_REFUND, UPI refund, or CARD refund ledger movement.
6. Exchange credit is generated and consumed only inside the authenticated exchange service. Public checkout requests cannot submit or mint exchange credit.
7. Each exchange has an idempotency key and immutable request fingerprint scoped to authenticated staff. Same-request replay returns the original exchange; changed details or another staff identity are rejected.
8. Lock order matches existing POS mutations: inventory location -> shift -> original sale -> inventory rows. Exchange entrypoints use READ_COMMITTED to avoid stale return aggregates after waiting on the sale lock.

## Value and settlement rules
Let R be the calculated net value of returned original lines after original-sale discount allocation. Let N be the replacement sale total after its approved discount.
- N > R: exchange credit covers R; customer pays exactly N - R using CASH / UPI / CARD. Only actual tender enters payment settlement and the cash drawer.
- N = R: exchange is settled by exchange credit; no cash or provider payment is recorded.
- N < R: excess R - N is a real customer refund. Record exchange credit consumed for N and actual refund for R - N, using ordinary refund requirements (cash drawer availability or provider-confirmed reference). Never silently turn the excess into store credit.
All money is DECIMAL(12,2), validated to at most two decimal places. No negative balances or implicit rounding differences are permitted.

## Persistence model
Add an immutable pos_exchanges header linking original sale, exchange return, replacement sale, shift, authenticated staff, returned value, replacement value, actual refund, collected difference, idempotency key, request fingerprint, completion timestamp and reason.
Enforce unique idempotency key, unique return link and unique replacement-sale link. Add restrictive foreign keys and indexes for original sale / creation time.
Add an exchange-credit settlement record (or equivalent constrained payment linkage) recording the exact return credit consumed by the replacement sale. It must be unique per exchange and cannot be created by ordinary checkout. A free-form client-supplied payment method is not authorization.
Keep pos_returns.refund_total as the value reversed from the original sale so existing net-sales calculations remain meaningful. Keep actual money refunded in explicitly typed refund-payment rows. Reports must distinguish sales reversals (including exchange credit), actual money refunded by CASH / UPI / CARD, exchange credit consumed, replacement sale value, and net sales after returns.
Never label exchange credit as a cash/provider refund in the UI or payment reconciliation.

## Service transaction design
Implement a dedicated PosExchangeService.exchange(...) transaction. Refactor return and checkout internals into helpers that accept already locked entities and do not start nested independent transactions. Calling public service methods is not a substitute for one transaction.
Within the exchange transaction:
1. Validate request, role, original sale and shift.
2. Lock location, shift, then original sale in the global lock order.
3. Recheck exchange idempotency and returnable quantities after locks.
4. Calculate returned line value using existing discount allocation and partial-return remainder rules.
5. Validate replacement catalog, variant ownership, active status, prices, discounts and stock.
6. Calculate exact exchange-credit / tender / refund split.
7. Persist return, replacement sale, exchange header and credit linkage.
8. Apply inventory restock and replacement stock deduction using distinct exchange-scoped movement keys.
9. Record only actual cash collected/refunded in the shift cash ledger.
10. Return original invoice, return number, replacement invoice, returned value, replacement value, credit applied, amount collected and actual refund.

## Required tests
- Equal-value exchange: no drawer movement; one return, one replacement sale, stock movements exactly once.
- Higher-value replacement: only difference is collected; correct cash/UPI/card and split-tender behaviour.
- Lower-value replacement: only difference is actually refunded; provider reference / cash drawer checks apply.
- Partial quantity and multiple sale lines with discounts: returned value matches existing return allocation rules.
- DAMAGED / DISCARD do not increase sellable stock; RESTOCK does.
- Inactive or mismatched replacement variants are rejected without partial writes.
- Insufficient stock rejects the entire exchange without committing the return.
- Same idempotency key and same staff/request replays one exchange; changed request or different staff is rejected.
- Concurrent exchanges cannot claim the same remaining sold quantity.
- Failures after return persistence, replacement sale persistence, and during inventory movements roll back all exchange records and stock movements.
- Reports distinguish sales reversals from actual payment refunds and never count exchange credit as money moved.
- API authorization enforces the existing manager-approval policy.

## Release gates
Do not enable the exchange UI until the MySQL integration suite proves rollback, idempotency, stock correctness, and concurrency behaviour. Do not run the new manual migration against a client database until it has been reviewed and tested on a disposable schema matching that database.