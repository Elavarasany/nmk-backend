# POS production deployment readiness

Status: configuration review in progress. This document is a deployment gate, not approval to deploy.

## Verified repository configuration

- Activate the production profile with `SPRING_PROFILES_ACTIVE=prod`.
- The base datasource requires `AIVEN_HOST`, `AIVEN_PORT`, `AIVEN_DATABASE`, `AIVEN_USER`, and `AIVEN_PASSWORD`. JDBC uses `sslMode=REQUIRED`; confirm the selected database provider's TLS requirements and certificate-validation policy before launch.
- `JWT_SECRET` is required. The application builds an HMAC signing key with JJWT; provision a cryptographically random key of at least 32 bytes (prefer 32+ random bytes encoded as Base64) and keep it outside source control. Rotating it invalidates existing sessions.
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` are required by the base configuration. Use live credentials only in the production secret store, never in frontend `VITE_*` variables.
- `APP_CORS_ALLOWED_ORIGINS` is required by the production profile. Supply only the exact storefront/admin HTTPS origin(s), comma-separated; do not include paths, wildcard origins, localhost, or preview domains. The security configuration rejects wildcard origins and rejects non-HTTPS origins when the production profile is active.
- Production disables SQL display, enables Hibernate schema validation (not schema mutation), disables Flyway auto-run, disables open-in-view, and suppresses detailed error messages/stack traces.
- The frontend's `.env.example` documents `VITE_API_BASE_URL`. Set it at build time to the deployed backend API base ending in `/api`. If it is omitted from a separately hosted production frontend, the frontend falls back to same-origin relative API paths and will likely call the wrong host.
- `AdminDataInitializer` supports a one-time initial administrator via `ADMIN_BOOTSTRAP_USERNAME` and `ADMIN_BOOTSTRAP_PASSWORD`. It creates the account only if that username does not already exist. Remove both variables from the deployment environment immediately after verifying the first administrator can sign in and create/manage staff.
- Demo website-image seeding is now controlled by `website-images.bootstrap.enabled`. It defaults to enabled for existing development/test behavior and is explicitly disabled in `application-prod.properties`; production will not silently populate demo placeholder image URLs.

## Required deployment environment

Backend:
- `SPRING_PROFILES_ACTIVE=prod`
- `AIVEN_HOST`, `AIVEN_PORT`, `AIVEN_DATABASE`, `AIVEN_USER`, `AIVEN_PASSWORD`
- `JWT_SECRET` (random, high entropy, at least 32 bytes)
- `APP_CORS_ALLOWED_ORIGINS` (exact HTTPS origins)
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
- Temporary only: `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD`

Frontend build:
- `VITE_API_BASE_URL=https://<backend-host>/api` (replace with the actual backend URL; this is a public API address, not a secret)

SMS remains disabled in the current base configuration. Do not enable it until client MSG91 credentials, sender/template approval, and delivery tests are available.

## Deployment checks that remain mandatory

1. Configure secrets in the hosting provider's secret manager; verify they are not committed, printed in logs, or embedded in frontend build artifacts.
2. Confirm the production profile is active in the actual running service and verify the effective CORS allowlist from a real browser origin. Do not infer this from a successful backend build.
3. Terminate TLS at the hosting platform/reverse proxy, enforce HTTPS redirects at the edge, and verify forwarded-protocol handling for any secure-cookie or absolute-URL behavior. The application config reviewed here does not itself prove edge TLS or redirect behavior.
4. Confirm the database provider's TLS mode, network allowlist, backups, restore procedure, connection limits, and monitoring. The repository currently does not set explicit Hikari pool sizing/timeouts; agree these against the selected DB plan and expected concurrency before launch.
5. Production schema changes are not automatically applied: Hibernate uses `validate` and Flyway is disabled. Review and rehearse each SQL migration against a disposable schema matching the client baseline. Keep a signed execution record with migration filename and SHA-256, target DB, operator, approval, backup ID, timestamps, result, and post-migration verification. MySQL DDL may implicitly commit; a failed multi-statement migration may leave partial schema changes.
6. Do not run migrations against the client's live database until credentials, a verified backup/restore, baseline inspection, and an approved rehearsal plan exist.
7. Verify the initial administrator, remove bootstrap variables, rotate any temporary credentials, and confirm role restrictions through authenticated API tests.
8. Upload client-approved storefront assets through the admin workflow before making the storefront public; demo image seeding is disabled in production.
9. Configure monitoring/alerts for startup failures, database connection failures, payment webhook failures/manual review, and backup failures. No claim is made here that these operational integrations are already configured.
10. Run browser acceptance, physical register/receipt tests, payment-provider test/live-mode checks, and the outstanding manual release checklist before launch.

## Known limitations / items not proven by this repository review

- No production host, domain, DNS, TLS certificate, reverse-proxy policy, or hosting secret store has been verified.
- No client database credentials or production database have been accessed.
- No production backup/restore or migration rehearsal has been performed.
- No explicit connection-pool sizing/timeouts are configured in the inspected properties.
- No deployment-specific actuator/management exposure review can be completed until the actual hosting/runtime configuration is known.
- The frontend build-time API URL must be supplied by the deployment pipeline; repository defaults are development-oriented.
- Payment provider settlement, refund processing, bank reconciliation, GST/tax approval, and manual POS browser acceptance remain separate release gates.

## Change made in this review

- Production profile sets `website-images.bootstrap.enabled=false`.
- The initializer skips demo image records when that property is false, while retaining its existing one-time admin bootstrap behavior.
- Development/test default remains enabled, preserving existing local behavior.

No deployment, live database access, migration execution, PR merge, or production credential change was performed.
