package com.minisync.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步执行日志（对应 {@code sync_log} 表）。
 *
 * <p>每次触发同步写入一条，用于「执行历史」页面展示。</p>
 */
@Data
@TableName("sync_log")
public class SyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String jobName;

    /** 本次实际使用的模式：FULL / INCREMENT */
    private String syncMode;

    private Integer totalCount;

    private Long costMs;

    /** 1 成功 / 0 失败 */
    private Integer success;

    private String errorMsg;

    private LocalDateTime createTime;
}
