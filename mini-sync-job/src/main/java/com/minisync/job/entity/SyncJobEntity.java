package com.minisync.job.entity;

import lombok.Data;

import java.util.Date;

/**
 * 同步任务配置实体。
 *
 * <p>这里用普通 POJO 而非 JPA 实体，是为了让执行器可以被独立测试 ——
 * 执行逻辑不该与持久化框架耦合。</p>
 */
@Data
public class SyncJobEntity {

    private Long id;

    private String jobName;

    /** 源端数据源 ID */
    private Long sourceDatasourceId;

    /** 源表名 */
    private String sourceTable;

    /** 目标端数据源 ID */
    private Long targetDatasourceId;

    /** 目标表名 */
    private String targetTable;

    /** 同步类型：FULL 全量 / INCREMENT 增量 */
    private String syncType;

    /** 增量同步使用的水位字段，如 update_time。全量同步可为空。 */
    private String incrementField;

    /**
     * 上次同步的水位值。
     *
     * <p>与 {@link #lastPk} 共同构成复合游标，单独使用会导致边界重复或丢数据。</p>
     */
    private String lastOffset;

    /**
     * 上次同步到达的主键值，复合游标的第二分量。
     */
    private Long lastPk;

    /**
     * 全量同步的分页游标字段。
     *
     * <p>必须是单调递增的主键或唯一索引列，为空时默认使用 id。</p>
     */
    private String cursorField;

    /** 批次大小，为空时使用默认值 1000。 */
    private Integer batchSize;

    /** 0=停用，1=启用 */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
