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
-- 管理库 schema 与开发者/环境无关的应用登录示例。
-- 注意：数据源连接参数及同步任务不在 SQL 中预填：
-- 1. 请登录控制台，通过界面手动创建数据源（口令不会写入仓库）
-- 2. 再通过界面创建同步任务
-- 3. sys_user 密码当前是 demo 级明文验证，生产环境应改用 BCrypt
INSERT INTO sys_user (username, password, nickname) VALUES
('admin', 'admin123', 'mini-sync admin');

-- 数据源与任务刻意留空，避免把任何数据库账号密码提交到版本库。
