-- NMK Opticals — migration 016: append-only, hash-chained administrator audit events.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Do not run against the client's live database without explicit approval and a
-- verified backup. Flyway remains disabled until the legacy schema is baselined.
--
-- Run once. This creates the audit event table, a singleton serialization row,
-- and database triggers that reject UPDATE/DELETE against recorded events.
-- The application writes SHA-256(previous_hash + canonical event fields) and
-- advances the singleton state row in the same transaction.

CREATE TABLE IF NOT EXISTS admin_audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_username VARCHAR(100) NULL,
    actor_authorities VARCHAR(300) NULL,
    http_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(500) NOT NULL,
    response_status INT NOT NULL,
    outcome VARCHAR(12) NOT NULL,
    remote_address VARCHAR(64) NULL,
    user_agent VARCHAR(300) NULL,
    request_id VARCHAR(100) NULL,
    error_type VARCHAR(160) NULL,
    created_at DATETIME(6) NOT NULL,
    previous_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_audit_created (created_at, id),
    KEY idx_admin_audit_actor (actor_username, created_at),
    KEY idx_admin_audit_path (request_path, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_audit_chain_state (
    id INT NOT NULL,
    last_event_id BIGINT NULL,
    last_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_admin_audit_chain_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO admin_audit_chain_state (id, last_event_id, last_hash)
VALUES (1, NULL, '0000000000000000000000000000000000000000000000000000000000000000');

DELIMITER //

CREATE TRIGGER trg_admin_audit_events_no_update
BEFORE UPDATE ON admin_audit_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Admin audit events are append-only';
END//

CREATE TRIGGER trg_admin_audit_events_no_delete
BEFORE DELETE ON admin_audit_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Admin audit events are append-only';
END//

DELIMITER ;
