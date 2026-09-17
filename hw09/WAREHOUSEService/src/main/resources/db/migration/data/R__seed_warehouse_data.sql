DO $$
DECLARE
    v_prod_id BIGINT;
    v_stock_id BIGINT;
    v_available INTEGER;
BEGIN
    INSERT INTO products (manufacturer_article, sku, name, description, price)
    VALUES
        ('910-005443', 'mouse-logitech-m170',  'Мышь Logitech M170',  'Беспроводная мышь, 2.4 ГГц, компактный дизайн, черный цвет', 990.00),
        ('K2-MAC-ANS', 'keyboard-keychron-k2',  'Клавиатура Keychron K2', 'Механическая клавиатура, RGB подсветка, Hot-swappable, Bluetooth', 8990.00),
        ('LC27G55TQWNXEN', 'monitor-samsung-odyssey-g5', 'Монитор Samsung Odyssey G5', '27 дюймов, 165 Гц, 1 ms, QHD, изогнутый экран', 24990.00),
        ('WD10EZEX',   'hdd-wd-blue-1tb',       'Жесткий диск WD Blue 1TB', 'Внутренний жёсткий диск, 7200 об/мин, SATA III, кэш 64 МБ', 3490.00),
        ('TUF-B550PLUS-WIFI', 'mobo-asus-tuf-b550', 'Материнская плата ASUS TUF B550-PLUS', 'AMD B550, сокет AM4, DDR4, PCIe 4.0, Wi-Fi 6', 12490.00),
        ('RZ01-03540100-R3U1', 'mouse-razer-da-v3', 'Мышь Razer DeathAdder V3', 'Игровая проводная мышь, 30 000 DPI, сенсор Focus Pro, эргономичная', 7990.00);

    FOR v_prod_id, v_available IN
        SELECT id,
            CASE ROW_NUMBER() OVER (ORDER BY id)
                WHEN 1 THEN 150
                WHEN 2 THEN 75
                WHEN 3 THEN 40
                WHEN 4 THEN 200
                WHEN 5 THEN 60
                WHEN 6 THEN 90
            END
        FROM products
    LOOP
        INSERT INTO product_stock (available_quantity, reserved_quantity)
        VALUES (v_available, 0)
        RETURNING id INTO v_stock_id;

        UPDATE products
        SET product_stock_id = v_stock_id
        WHERE id = v_prod_id;
    END LOOP;
END $$;
