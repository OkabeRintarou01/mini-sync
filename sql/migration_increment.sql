-- ============================================================
-- 增量同步闭环所需的最小数据库改动
--
-- 背景：sync_job 表已具备增量所需的大部分字段：
--   sync_type         FULL / INCREMENT
--   increment_field   增量字段名，如 update_time
--   cursor_field      主键字段名，默认 id
--   last_offset       水位值（注意：是 VARCHAR，存时间戳字符串）
--   last_pk           主键值（BIGINT）
-- 唯一缺少的是「上次同步完成时间」，本脚本补齐它。
--
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 的扩展），
--       所以本脚本只能执行一次；重复执行会报
--       "Duplicate column name 'last_sync_time'"，属正常情况，可忽略。
-- ============================================================

ALTER TABLE sync_job
    ADD COLUMN last_sync_time DATETIME NULL DEFAULT NULL
    COMMENT '上次同步完成时间（增量任务用于界面展示）';
