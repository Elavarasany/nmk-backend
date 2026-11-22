package nmk_backend.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConstraintUtilsTest {

    @Test
    void recognizesExpectedMysqlDuplicateKeyConstraint() {
        SQLException cause = new SQLException(
                "Duplicate entry 'abc' for key 'uk_pos_sales_idempotency_key'",
                "23000",
                1062
        );

        assertTrue(DatabaseConstraintUtils.isUniqueConstraintViolation(
                cause, "uk_pos_sales_idempotency_key"
        ));
    }

    @Test
    void doesNotTreatForeignKeyViolationAsIdempotencyConflict() {
        SQLException cause = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails",
                "23000",
                1452
        );

        assertFalse(DatabaseConstraintUtils.isUniqueConstraintViolation(
                cause, "uk_stock_movements_idempotency"
        ));
    }

    @Test
    void doesNotTreatDifferentUniqueConstraintAsIdempotencyConflict() {
        SQLException cause = new SQLException(
                "Duplicate entry 'abc' for key 'uk_pos_sales_invoice_number'",
                "23000",
                1062
        );

        assertFalse(DatabaseConstraintUtils.isUniqueConstraintViolation(
                cause, "uk_pos_sales_idempotency_key"
        ));
    }
}
