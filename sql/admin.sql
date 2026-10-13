-- ============================================================
-- mini-sync 平台管理库（V2）
--   1. 数据源配置表：支持用户在前端新建数据源
--   2. 同步任务表：关联源数据源与目标数据源
--   3. 执行日志表
-- ============================================================

CREATE DATABASE IF NOT EXISTS mini_sync_admin DEFAULT CHARACTER SET utf8mb4;
USE mini_sync_admin;

-- ---------------- 数据源配置 ----------------
DROP TABLE IF EXISTS datasource_config;
CREATE TABLE datasource_config (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    ds_name       VARCHAR(64)  NOT NULL COMMENT '数据源名称',
    ds_type       VARCHAR(32)  NOT NULL DEFAULT 'MYSQL' COMMENT 'MYSQL / POSTGRESQL',
    host          VARCHAR(128) NOT NULL DEFAULT '127.0.0.1',
    port          INT          NOT NULL DEFAULT 3306,
    database_name VARCHAR(64)  NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password      VARCHAR(128) NOT NULL,
    extra_params  VARCHAR(255) DEFAULT NULL COMMENT '附加连接参数',
    remark        VARCHAR(255) DEFAULT NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置';

-- ---------------- 同步任务 ----------------
DROP TABLE IF EXISTS sync_job;
CREATE TABLE sync_job (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    job_name             VARCHAR(128) NOT NULL COMMENT '任务名称',
    source_datasource_id BIGINT       NOT NULL COMMENT '源数据源 ID',
    source_table         VARCHAR(128) NOT NULL DEFAULT 't_order',
    target_datasource_id BIGINT       NOT NULL COMMENT '目标数据源 ID',
    target_table         VARCHAR(128) NOT NULL DEFAULT 't_order',
    sync_type            VARCHAR(16)  NOT NULL DEFAULT 'FULL',
    increment_field      VARCHAR(64)  DEFAULT NULL,
    cursor_field         VARCHAR(64)  DEFAULT 'id',
    batch_size           INT          NOT NULL DEFAULT 1000,
    last_offset          VARCHAR(64)  DEFAULT NULL,
    last_pk              BIGINT       DEFAULT 0,
    status               TINYINT      NOT NULL DEFAULT 1,
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务配置';

-- ---------------- 执行日志 ----------------
DROP TABLE IF EXISTS sync_log;
CREATE TABLE sync_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    job_id      BIGINT       NOT NULL,
    job_name    VARCHAR(128) DEFAULT NULL,
    sync_mode   VARCHAR(16)  DEFAULT NULL,
    total_count INT          DEFAULT 0,
    cost_ms     BIGINT       DEFAULT 0,
    success     TINYINT      NOT NULL DEFAULT 1,
    error_msg   VARCHAR(512) DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步执行日志';

-- ---------------- 用户（登录用，演示级） ----------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(128) NOT NULL,
    nickname    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

-- ---------------- 初始化数据 ----------------
INSERT INTO sys_user (username, password, nickname) VALUES
('admin', 'admin123', '舒卫东');

INSERT INTO datasource_config
    (ds_name, ds_type, host, port, database_name, username, password, remark) VALUES
('本地源库',        'MYSQL',      '127.0.0.1', 3306, 'mini_sync_src', 'root',     's123456', 'MySQL 5.7 测试源'),
('本地目标库',      'MYSQL',      '127.0.0.1', 3306, 'mini_sync_dst', 'root',     's123456', 'MySQL 5.7 测试目标'),
('PostgreSQL 测试库', 'POSTGRESQL', '127.0.0.1', 5432, 'mini_sync_pg',  'postgres', 's123456', 'Docker 中的 PG 16');

INSERT INTO sync_job
    (job_name, source_datasource_id, source_table, target_datasource_id, target_table,
     sync_type, cursor_field, batch_size, status) VALUES
('MySQL 源库 → 目标库', 1, 't_order', 2, 't_order', 'FULL', 'id', 1000, 1),
('MySQL 源库 → PostgreSQL', 1, 't_order', 3, 't_order', 'FULL', 'id', 1000, 1);
