package com.minisync.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minisync.server.entity.SyncLog;
import org.apache.ibatis.annotations.Mapper;

/** 同步日志 Mapper。 */
@Mapper
public interface SyncLogMapper extends BaseMapper<SyncLog> {
}
