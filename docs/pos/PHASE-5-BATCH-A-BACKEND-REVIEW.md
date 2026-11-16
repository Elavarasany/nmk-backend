# Phase 5 — Batch A Backend Review

**Status:** Inspection complete; implementation intentionally held pending baseline and compatibility checks.  
**Repository:** `Elavarasany/nmk-backend`, branch `master`  
**Inspected files:** `nmk-backend/pom.xml`, `nmk-backend/src/main/resources/application.properties`  
**Inspection source:** GitHub `master` at the time of this review.

## 1. Findings

### Maven project

- Spring Boot parent: `4.1.2`
- Java target: `27`
- MySQL Connector/J: present, runtime scope.
- Spring Data JPA: present.
- H2: present for tests.
- Flyway: no Flyway dependency is currently declared.
- No explicit Flyway version or MySQL Flyway database module is present.

### Application configuration

Current relevant settings:

```properties
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.datasource.url=jdbc:mysql://${AIVEN_HOST}:${AIVEN_PORT}/${AIVEN_DATABASE}?sslMode=REQUIRED
```

The datasource requires TLS. Database credentials are environment placeholders and must remain outside source control.

## 2. Why Batch A is not being activated yet

Adding Flyway dependencies alone is not sufficient. Flyway must know which migration version represents the existing live schema. The live database is not empty: the Phase 1 inspection found 10 existing application tables.

An incorrect baseline can cause Flyway to treat existing objects as absent or can attempt to create tables that already exist. Enabling Flyway before the baseline decision and staging proof is therefore prohibited.

Likewise, switching Hibernate away from `ddl-auto=update` without first validating the complete entity/schema relationship could break application startup or leave expected schema objects absent. The switch must be staged and separately reviewed.

## 3. Required Batch A sequence

1. Inspect the complete backend resource/configuration structure and all active profiles.
2. Confirm the Spring Boot 4.1.2 dependency-management versions for Flyway and its MySQL module. Pin versions only if the Boot dependency management does not provide a suitable compatible version.
3. Decide whether to use the Flyway Spring Boot starter or the core Flyway integration plus the MySQL database module; do not add both paths redundantly.
4. Create a sanitized staging copy of the existing schema and representative non-sensitive test fixtures.
5. Compare the staging schema against a documented baseline. The baseline must represent the actual pre-existing schema, not an assumed empty database.
6. Test Flyway baseline behavior on the disposable staging copy and prove it does not recreate or mutate existing tables.
7. Prove the current application starts and its existing APIs work with the chosen migration configuration.
8. Only after these checks, prepare a reviewed code change that adds migration support and prevents Hibernate from being the production schema migration authority.
9. Keep all migration execution pointed at staging until production approval is explicitly given.

## 4. Immediate no-change rules

Until the above sequence is complete:

- Do not add `spring.flyway.baseline-on-migrate=true` as a blind default.
- Do not choose a baseline version without a schema comparison and staging proof.
- Do not add an empty baseline migration that falsely claims to describe the existing schema.
- Do not change `spring.jpa.hibernate.ddl-auto=update` in the live deployment yet.
- Do not run Flyway against Aiven.
- Do not create POS tables or alter existing product/order/payment tables.
- Do not initialize inventory balances from `products.stockQuantity`.

## 5. Logging configuration note

`spring.jpa.show-sql=true` is currently enabled. Before production migration work, review SQL logging and application log levels to ensure parameters, personal data, prescription details and credentials are not exposed. Do not enable verbose SQL/parameter logging in production as a troubleshooting shortcut.

## 6. Batch A acceptance criteria

Batch A is accepted only when:

- The exact Flyway integration and versions are documented.
- The baseline version and procedure are justified by the existing schema.
- A sanitized staging restore has been tested.
- Flyway validation and application startup succeed on staging.
- Existing website order, payment, tracking, admin and appointment behavior passes regression tests.
- Hibernate no longer performs uncontrolled production schema updates, with the change proven on staging.
- No Aiven production migration has been executed as part of this review.

**Current result:** inspection only. No code, dependency, configuration or database changes were made in this review.


## 7. Verified Spring Boot 4.1.2 Flyway dependency guidance

The official Spring Boot 4.1.2 dependency coordinates list both:
- `org.springframework.boot:spring-boot-starter-flyway`
- `org.flywaydb:flyway-mysql`

The Spring Boot 4.1.2 managed dependency coordinates list Flyway `12.4.0`, including `flyway-mysql`. Therefore the preferred initial dependency approach, subject to a local Maven resolution/build check, is to use the Spring Boot-managed versions rather than hardcoding an unrelated Flyway version.

For this MySQL application, the intended dependency pair is:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

Do not add a separate `flyway-core` dependency unless the resolved dependency graph proves it is needed; avoid redundant declarations. Do not commit these dependencies yet: first run Maven dependency resolution/build locally and verify the project's exact Boot 4.1.2 dependency management.

Official references:
- Spring Boot 4.1.2 managed coordinates: https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html
- Spring Boot database initialization guidance: https://docs.spring.io/spring-boot/4.2/how-to/data-initialization.html

## 8. Configuration and profile inspection

The repository's `src/main/resources` directory currently contains `application.properties` and a `static` directory; no `application-<profile>.properties` file was listed there. The active deployment environment and any externally supplied configuration still need checking before a migration feature is enabled.

The current `ddl-auto=update` setting remains a release blocker for production Flyway activation. The intended target is a controlled Hibernate validation mode after schema parity is proven on staging, but no mode change is authorized by this review.

