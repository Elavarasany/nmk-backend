-- Manual migration: apply only after verifying the target database.
-- Adds a distinct admin actor reference without changing the staff actor FK.
ALTER TABLE stock_movements
    ADD COLUMN actor_admin_id BIGINT NULL,
    ADD KEY idx_stock_movements_actor_admin (actor_admin_id),
    ADD CONSTRAINT fk_stock_movements_actor_admin
        FOREIGN KEY (actor_admin_id)
        REFERENCES admins(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT;
