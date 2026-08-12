ALTER TABLE products
    ADD COLUMN manufacturer_article VARCHAR(128),
    ADD COLUMN sku                  VARCHAR(64)   DEFAULT NULL;

-- Generate SKU from name for existing records that have NULL sku
UPDATE products
SET sku = LOWER(
        REPLACE(
            REGEXP_REPLACE(name, '[^a-zA-Zа-яА-Я0-9 ]', '', 'g'),
            ' ', '-'
        )
    ) || '-' || id
WHERE sku IS NULL;

-- Ensure not-null for any records with empty string
UPDATE products
SET sku = 'product-' || id
WHERE sku IS NULL OR sku = '';

ALTER TABLE products
    ALTER COLUMN sku SET NOT NULL,
    ADD CONSTRAINT uq_products_sku UNIQUE (sku);
