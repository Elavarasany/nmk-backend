# POS payment and settlement reconciliation readiness

## Audit scope

Reviewed the feature branch payment records, POS checkout/return/exchange services, sales report aggregation, Razorpay payment verification and webhook processing. This is a code-level readiness assessment, not a live Razorpay or bank settlement reconciliation.

## What is currently enforced

### Walk-in POS tenders

- A sale can use CASH, UPI and CARD, with up to three distinct methods.
- Each non-cash allocation must include a transaction reference (maximum 150 characters).
- The allocations must add up exactly to the sale grand total.
- The backend stores method, amount, reference, cash tendered and change in `pos_payments`.
- The backend does not verify that a UPI UTR/card reference exists at the provider, belongs to this shop, is successful, or matches the amount. The reference is operator-entered evidence only.
- Cash allocations update the shift cash ledger. UPI/card allocations do not change drawer cash.

### POS refunds and exchanges

- UPI/card refund allocations require a reference and are stored in `pos_refund_payments`; the backend does not initiate or verify the provider refund.
- Exchange collection and refund allocations follow the same operational-recording model. Cash portions affect the drawer; non-cash portions do not.
- The return/refund report separately exposes refund totals and recorded refund-payment totals. This distinction is useful: a refund record is not proof that money reached the customer.

### Online Razorpay orders

- Checkout signatures are verified, and payment confirmation fetches the payment from Razorpay to confirm captured status, matching Razorpay order ID and exact amount.
- Signed webhook events are durably stored and deduplicated by event ID. Only `payment.captured` can confirm an online order; the worker verifies provider state before confirmation and sends conflicts/late captures to manual review.
- Other signed non-refund event types are stored/processed for webhook audit. Signed `refund.*` events are now routed to `MANUAL_REVIEW` rather than marked `PROCESSED`, because no provider-refund-to-POS-ledger reconciliation exists yet. They do not automatically update a POS refund or online-order refund state.
- The online-order Razorpay payment flow is separate from the walk-in POS UPI/card tender records. A POS transaction reference is not automatically linked to a Razorpay order/payment ID.

### Webhook retry and worker recovery controls

- Webhook event IDs have a database uniqueness constraint; duplicate deliveries are acknowledged without creating a second event record.
- A worker claims an event under a short row-locking transaction, then performs provider calls outside that transaction. Finalization checks the worker's claim token so a worker whose lease was reclaimed cannot overwrite the newer worker's result.
- A processing lease expires after five minutes. Retryable provider/processing failures use bounded backoff and stop after eight claims.
- If a worker repeatedly disappears after claiming an event, stale claims are also counted toward the eight-attempt limit; an event at the limit is moved to `MANUAL_REVIEW` rather than reclaimed indefinitely.
- `MANUAL_REVIEW` is a terminal operational queue state in the current implementation. It requires an operator to inspect the provider dashboard and local order/refund records, document the outcome, and resolve the case through an approved operational procedure. A read-only manager/admin API queue now lists paginated MANUAL_REVIEW events with event metadata and extracted provider resource/payment/order IDs, amount, currency and provider status. It deliberately does not expose raw webhook JSON. A manager/admin-only Payment exceptions tab is now available in the POS frontend and consumes this read-only API. There is still no resolution action, retry/reconciliation workflow, or audited outcome record in this branch.
- Operations must monitor `RETRY`, stale `PROCESSING`, and `MANUAL_REVIEW` records. The current scheduler logs processing failures but does not send alerts or provide a dashboard/metrics view.
- The retry/claim SQL scripts are manual staging-first schema changes. The POS migration chain does not create the pre-existing webhook event table; verify that table and all expected columns/indexes exist during the client-schema inspection. Do not apply these scripts to a live database without backup, exact schema comparison and an approved migration record.

### Manual-review queue API

- `GET /api/admin/pos/payments/webhook-review?page=0&size=25` lists only `MANUAL_REVIEW` webhook events, oldest first; page size is capped at 100.
- Access is restricted to `ROLE_ADMIN` and `ROLE_MANAGER`; cashiers and unauthenticated users must be denied.
- The entity mapping declares a composite `(status, received_at)` index for queue ordering. Existing production databases need this index included in an approved schema migration; production Hibernate schema validation does not create it. Do not apply ad hoc DDL to the client database.
- Response includes event ID/type/status/time/attempt count/error and safely extracted provider resource ID, payment ID, order ID, amount in paise, currency and provider status. Raw payload is never returned.
- This is visibility only. It does not initiate a refund, mark a refund paid, retry an event, or change a review status. Reconciliation remains a documented manual process until a provider-linked ledger and audited resolution workflow are designed.

### Interim manual handling procedure

1. Open POS → Payment exceptions using a manager/admin account. Do not share screenshots containing customer or payment identifiers outside approved business channels.
2. Match the event ID and provider refund/payment ID in the Razorpay merchant dashboard. Confirm the exact amount, currency, original payment, current provider status and event timestamp.
3. Locate the corresponding local order or POS return using the payment/order identifiers and the recorded POS return reference. If there is no reliable match, stop and escalate; do not guess based on amount alone.
4. Preserve the provider dashboard/export evidence and record the reviewer, review time, event ID, local sale/return reference, provider refund ID, confirmed amount/status and discrepancy/outcome in the shop's approved reconciliation log.
5. Do not mark a local refund complete, issue a second refund, or alter the event status through SQL. The queue is intentionally read-only until an audited resolution workflow and provider linkage are implemented.

This procedure is a temporary control, not an automated reconciliation feature. Agree the evidence location, retention period and responsible reviewer with the client before go-live.

## What is not implemented

1. Provider-backed validation of POS UPI/card payments.
2. Durable mapping from POS tender rows to provider account, provider payment ID, order ID, capture status and settlement batch.
3. Automated Razorpay refund creation, refund ID storage, provider status polling/webhook reconciliation and idempotent retry. Refund webhook events currently require manual review; they are not proof that a POS or online-order refund ledger was updated.
4. Bank payout/settlement import and matching of gross captured amounts, fees, taxes on fees, refunds, chargebacks and net deposits.
5. Exception queues for unmatched, duplicate, short-paid, late, reversed or disputed transactions.
6. A daily settlement reconciliation report that proves POS tender totals against provider settlement statements and bank credits.

The current POS payment-method report is an operational total of amounts entered at checkout. It is not a Razorpay settlement report, bank reconciliation, or proof of cleared funds.

## Required operating controls until reconciliation is built

- Cash: reconcile expected drawer cash against the physical count at shift close; investigate and document variance.
- UPI/card: independently confirm successful payment in the merchant/provider app before completing the sale. Record the actual provider transaction reference, not a customer screenshot or arbitrary note.
- Refunds: complete and verify the refund through the provider/bank first; only then record the confirmed reference and allocation in POS. Do not treat a POS refund record as evidence of provider transfer.
- Daily close: compare POS UPI/card totals against provider transaction exports, then compare provider net settlement against bank credits after fees/refunds. Keep the provider statement and reconciliation evidence.
- Escalate any amount/reference mismatch; do not silently edit or delete a completed POS sale.

## Design decisions required before implementation

- Confirm the actual merchant acquiring setup: Razorpay, direct UPI QR, bank POS terminal, or a combination.
- Confirm whether UPI and card receipts can be fetched through provider APIs and whether all in-store transactions share one merchant account.
- Confirm provider fee/GST treatment, settlement frequency, refund timelines and chargeback handling with the merchant/accountant.
- Approve the reconciliation ownership, exception workflow and evidence-retention policy.

## Release status

POS cash drawer controls are implemented, but non-cash POS tender and refund records remain operator-entered operational records. Do not describe POS financial close as fully reconciled or enable automated provider refunds until provider integration, settlement matching and exception handling are implemented and tested.

No provider credentials, client bank data or live settlement files were accessed during this review. No live database was changed.
