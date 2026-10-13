-- ============================================================
-- mini-sync 测试库初始化脚本
-- 用途：建源库、目标库、10 万行测试数据
-- ============================================================

DROP DATABASE IF EXISTS mini_sync_src;
DROP DATABASE IF EXISTS mini_sync_dst;

CREATE DATABASE mini_sync_src DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE mini_sync_dst DEFAULT CHARACTER SET utf8mb4;

-- ---------------- 源表 ----------------
USE mini_sync_src;

CREATE TABLE t_order (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64)   NOT NULL,
    user_id     BIGINT        NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      TINYINT       NOT NULL DEFAULT 0,
    remark      VARCHAR(255)  DEFAULT NULL,
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 目标表 ----------------
-- 注意：id 不带 AUTO_INCREMENT，由源端带入，保证 upsert 能匹配上
USE mini_sync_dst;

CREATE TABLE t_order (
    id          BIGINT        NOT NULL,
    order_no    VARCHAR(64)   NOT NULL,
    user_id     BIGINT        NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      TINYINT       NOT NULL DEFAULT 0,
    remark      VARCHAR(255)  DEFAULT NULL,
    create_time DATETIME      NOT NULL,
    update_time DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 造 10 万行测试数据 ----------------
USE mini_sync_src;

DROP PROCEDURE IF EXISTS gen_orders;

DELIMITER $$
CREATE PROCEDURE gen_orders(IN n INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    SET autocommit = 0;
    WHILE i < n DO
        INSERT INTO t_order (order_no, user_id, amount, status, remark, update_time)
        VALUES (CONCAT('NO', LPAD(i, 10, '0')),
                FLOOR(1 + RAND() * 10000),
                ROUND(1 + RAND() * 999, 2),
                FLOOR(RAND() * 4),
                CONCAT('remark-', i),
                DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 365) DAY));
        SET i = i + 1;
        IF i % 5000 = 0 THEN
            COMMIT;
        END IF;
    END WHILE;
    COMMIT;
    SET autocommit = 1;
END$$
DELIMITER ;

CALL gen_orders(100000);
DROP PROCEDURE gen_orders;

SELECT COUNT(*) AS src_total FROM t_order;
SELECT COUNT(*) AS dst_total FROM mini_sync_dst.t_order;
