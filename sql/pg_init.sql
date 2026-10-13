-- ============================================================
-- PostgreSQL 端测试表
-- 用途：验证 mini-sync 的 PostgreSQL 数据源驱动
-- ============================================================

CREATE TABLE IF NOT EXISTS t_order (
    id          BIGINT        NOT NULL,
    order_no    VARCHAR(64)   NOT NULL,
    user_id     BIGINT        NOT NULL,
    amount      NUMERIC(10,2) NOT NULL,
    status      SMALLINT      NOT NULL DEFAULT 0,
    remark      VARCHAR(255)  DEFAULT NULL,
    create_time TIMESTAMP     NOT NULL,
    update_time TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_update_time ON t_order (update_time);

-- 造 5 万行测试数据（PG 用 generate_series 比存储过程简洁得多）
TRUNCATE TABLE t_order;

INSERT INTO t_order (id, order_no, user_id, amount, status, remark, create_time, update_time)
SELECT
    g,
    'PG' || LPAD(g::text, 10, '0'),
    (random() * 10000)::bigint + 1,
    ROUND((random() * 999 + 1)::numeric, 2),
    (random() * 4)::int,
    'pg-remark-' || g,
    NOW() - (random() * 365) * INTERVAL '1 day',
    NOW() - (random() * 365) * INTERVAL '1 day'
FROM generate_series(1, 50000) AS g;

SELECT COUNT(*) AS pg_total FROM t_order;
