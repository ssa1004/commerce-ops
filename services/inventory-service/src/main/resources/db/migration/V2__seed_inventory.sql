-- 데모용 시드 재고. order-service의 sample 요청과 productId 일치.
INSERT INTO inventories (product_id, available_quantity) VALUES
    (1001, 100),
    (1002, 50),
    (1003, 25);
