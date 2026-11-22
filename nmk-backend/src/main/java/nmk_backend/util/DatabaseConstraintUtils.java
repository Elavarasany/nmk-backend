package nmk_backend.util;

import java.sql.SQLException;
import java.util.Locale;

public final class DatabaseConstraintUtils {

    private DatabaseConstraintUtils() {
    }

    public static boolean isUniqueConstraintViolation(
            Throwable exception,
            String expectedConstraint
    ) {
        if (exception == null
                || expectedConstraint == null
                || expectedConstraint.isBlank()) {
            return false;
        }

        String expected = expectedConstraint.toLowerCase(Locale.ROOT);

        Throwable current = exception;

        while (current != null) {

            if (current instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String actual = violation.getConstraintName();

                if (actual != null
                        && actual.toLowerCase(Locale.ROOT).contains(expected)) {
                    return true;
                }
            }

            if (current instanceof SQLException sqlException) {
                String message = sqlException.getMessage();

                boolean duplicateError =
                        "23505".equals(sqlException.getSQLState())
                                || sqlException.getErrorCode() == 1062;

                if (duplicateError
                        && message != null
                        && message.toLowerCase(Locale.ROOT).contains(expected)) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }
}