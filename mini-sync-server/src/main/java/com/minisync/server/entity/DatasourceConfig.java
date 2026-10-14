package com.minisync.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源配置（对应 {@code datasource_config} 表）。
 *
 * <p>注意 {@code databaseName} 这个字段的语义<b>随 dsType 变化</b>：
 * MySQL / PostgreSQL 是逻辑库名，SQLite 是 {@code .db} 文件路径。
 * 这是刻意的"字段复用"，让驱动工厂的签名保持稳定。</p>
 */
@Data
@TableName("datasource_config")
public class DatasourceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsName;

    /** MYSQL / POSTGRESQL / SQLITE / ... 由已加载的插件决定 */
    private String dsType;

    private String host;

    private Integer port;

    /** MySQL/PG 为库名；SQLite 为数据库文件路径 */
    private String databaseName;

    private String username;

    private String password;

    private String extraParams;

    private String remark;

    private LocalDateTime createTime;
}
