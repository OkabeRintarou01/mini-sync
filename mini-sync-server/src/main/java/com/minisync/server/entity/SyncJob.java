package com.minisync.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步任务配置（对应 {@code sync_job} 表）。
 *
 * <p><b>关于水位的类型，容易搞反，务必注意：</b></p>
 * <ul>
 *   <li>{@code lastOffset} 是「水位字段的值」，通常是时间戳 → 数据库里是 VARCHAR</li>
 *   <li>{@code lastPk} 是「主键的值」→ 数据库里是 BIGINT</li>
 * </ul>
 * <p>两者是复合游标 {@code (last_offset, last_pk)} 的两个分量，
 * 类型不能互换，否则水位比较会出错。</p>
 */
@Data
@TableName("sync_job")
public class SyncJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobName;

    private Long sourceDatasourceId;

    private String sourceTable;

    private Long targetDatasourceId;

    private String targetTable;

    /** FULL 全量 / INCREMENT 增量 */
    private String syncType;

    /** 增量字段名，如 update_time。全量模式为 null */
    private String incrementField;

    /** 主键字段名，默认 id。复合游标的第二分量 */
    private String cursorField;

    private Integer batchSize;

    /** 水位字段值（时间戳字符串） */
    private String lastOffset;

    /** 主键值（数字） */
    private Long lastPk;

    private LocalDateTime lastSyncTime;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
